#!/usr/bin/env python3
"""飞象供应商平台「待发货订单」自动拉表脚本（基于 ziyousupplier.wowcarp.com 抓包复刻）

链路契约见 docs/research/feixiang-supplier-export-api.md:
  GET  /welcome/index/                     引导会话（ThinkPHP 首次访问种下 fxqf_sess cookie）
  POST /welcome/index/                     登录（表单 username/password）→ 302 → product_library/publish_list
  GET  /order/deliveryExport?start_time=..&end_time=..   直接返回 xlsx（不经过任务/轮询）

认证（2026-08-18 抓包确认）:
  - cookie `fxqf_sess`（ThinkPHP session），由 requests.Session 自动携带与续期
  - 登录 POST 返回 302，Location 指向业务页即成功；失败会回到 /welcome/index
  - 抓包中登录 302 响应头无 Set-Cookie（会话 cookie 在首次访问页面时种下），
    故脚本先 GET 登录页引导会话，再提交表单

下载文件（2026-08-18 抓包确认）:
  - Content-Type: application/vnd.ms-excel；Content-Disposition 误命名 `批量发货<ts>.csv`
  - 内容实为 OOXML XLSX（PK 魔数），21 列表头命中 excel-closed-loop-spec §3.2「飞象 v1」指纹
  - 样例（data-local/feixiang-delivery-export-sample-20260818.xlsx）仅含表头行，无数据行

用法（推荐）:
  FEIXIANG_USERNAME=<账号> FEIXIANG_PASSWORD=<密码> python3 scripts/feixiang_fetch_orders.py
  FEIXIANG_USERNAME=<账号> FEIXIANG_PASSWORD=<密码> python3 scripts/feixiang_fetch_orders.py \
      --begin 2026-06-18 --end 2026-08-18 --out-dir data-local --force
  FEIXIANG_USERNAME=<账号> FEIXIANG_PASSWORD=<密码> python3 scripts/feixiang_fetch_orders.py --dry-run

注意: 凭据只从环境变量读取，登录表单/日志/文件名一律不出现密码，token 与 cookie 只进内存。
"""

from __future__ import annotations

import argparse
import logging
import os
import re
import sys
from dataclasses import dataclass, field
from datetime import date, timedelta
from pathlib import Path
from typing import Any

import requests

log = logging.getLogger("feixiang")

BASE_URL = "https://ziyousupplier.wowcarp.com"
LOGIN_PATH = "/welcome/index/"
EXPORT_PATH = "/order/deliveryExport"
DELIVERY_PAGE_PATH = "/order/delivery"

# 下载文件实为 XLSX（Content-Disposition 误命名 .csv），以 PK 魔数判定
XLSX_MAGIC = b"PK"

# 默认导出区间：近一个月（与人工导表习惯一致）
DEFAULT_RANGE_DAYS = 30

# 默认落盘文件名（按拉表截止日期）: 飞象待发货订单-YYYYMMDD.xlsx
DEFAULT_OUT_NAME = "飞象待发货订单-{end}.xlsx"

# 认证 header（可选，仅提升兼容性；服务端实际只认 cookie）
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36 Edg/148.0.0.0"
)


class FeixiangError(RuntimeError):
    """业务错误（登录失败、响应不是 xlsx 等）。"""


@dataclass
class FeixiangConfig:
    username: str = ""
    password: str = ""
    begin: str = ""          # start_time, yyyy-MM-dd
    end: str = ""            # end_time, yyyy-MM-dd
    out_dir: Path = Path("data-local")
    force: bool = False
    dry_run: bool = False
    timeout: float = 30.0
    _session: requests.Session = field(default=None, repr=False, init=False)  # type: ignore[assignment]

    def session(self) -> requests.Session:
        if self._session is None:
            s = requests.Session()
            s.headers.update(
                {
                    "Origin": BASE_URL,
                    "Referer": BASE_URL + LOGIN_PATH,
                    "User-Agent": USER_AGENT,
                    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                }
            )
            self._session = s
        return self._session


# ---------------------------------------------------------------- API 方法

def api_bootstrap_session(cfg: FeixiangConfig) -> None:
    """GET 登录页引导会话：ThinkPHP 首次访问时以 Set-Cookie 种下 fxqf_sess。

    抓包中登录请求已携带既有 fxqf_sess（由更早的页面访问种下），本步骤为
    全新会话补上这一环；若服务器在 POST 时才种 cookie 也无副作用（Session 都会收）。
    """
    cfg.session().get(BASE_URL + LOGIN_PATH, timeout=cfg.timeout)


def api_login(cfg: FeixiangConfig) -> str:
    """POST 表单登录，返回登录后最终 URL（302 跟随后的落点）。

    成功: 302 -> /product_library/publish_list（随后 200）
    失败: 通常会回到 /welcome/index（行为未抓包验证，见契约文档风险表）
    """
    data = {"username": cfg.username, "password": cfg.password}
    log.info("登录: user=%s", cfg.username)  # 只打账号，绝不打印密码
    resp = cfg.session().post(
        BASE_URL + LOGIN_PATH,
        data=data,
        allow_redirects=True,
        timeout=cfg.timeout,
    )
    resp.raise_for_status()
    if "/welcome/index" in resp.url:
        raise FeixiangError(
            f"登录失败（仍停留在登录页 {resp.url}）: 请检查 FEIXIANG_USERNAME/FEIXIANG_PASSWORD"
        )
    log.info("登录成功: 302 -> %s", resp.url)
    return resp.url


def fetch_delivery_export(cfg: FeixiangConfig) -> tuple[bytes, str]:
    """GET /order/deliveryExport 拉取导出文件，返回 (字节, 服务器文件名)。

    服务端 Content-Disposition 误命名 `批量发货<unix>.csv`，实际内容是 XLSX；
    用 PK 魔数校验，未登录被重定向时会得到 HTML 从而在此报错。
    """
    params = {"start_time": cfg.begin, "end_time": cfg.end}
    log.info("导出: start_time=%s end_time=%s", cfg.begin, cfg.end)
    resp = cfg.session().get(
        BASE_URL + EXPORT_PATH,
        params=params,
        timeout=cfg.timeout * 4,  # 大文件放宽
    )
    resp.raise_for_status()
    if not resp.content.startswith(XLSX_MAGIC):
        raise FeixiangError(
            f"响应不是 xlsx（魔数 {resp.content[:8]!r}，Content-Type={resp.headers.get('Content-Type')}）"
            "——可能未登录被重定向到登录页，或导出参数不被接受"
        )
    server_name = _content_disposition_filename(resp.headers.get("Content-Disposition", ""))
    return resp.content, server_name


# ---------------------------------------------------------------- 主流程

def run(cfg: FeixiangConfig) -> Path | None:
    api_bootstrap_session(cfg)
    api_login(cfg)

    content, server_name = fetch_delivery_export(cfg)
    log.info("导出响应: 服务器文件名=%s 大小=%d B（实为 XLSX）", server_name, len(content))

    dest = cfg.out_dir / DEFAULT_OUT_NAME.format(end=cfg.end.replace("-", ""))
    if cfg.dry_run:
        log.info("[dry-run] 跳过保存: %s (%d B)", dest, len(content))
        return None

    cfg.out_dir.mkdir(parents=True, exist_ok=True)
    if dest.exists() and not cfg.force:
        log.info("已存在同名文件，跳过（--force 覆盖）: %s", dest)
        return dest
    dest.write_bytes(content)
    log.info("已保存: %s (%d B)", dest, len(content))
    return dest


# ---------------------------------------------------------------- 工具

def _content_disposition_filename(header: str) -> str:
    """从 Content-Disposition 解析 filename（兼容 filename= 与 filename*=UTF-8''）。"""
    if not header:
        return ""
    m = re.search(r"filename\*=UTF-8''([^;]+)", header, re.I)
    if m:
        from urllib.parse import unquote

        return unquote(m.group(1).strip())
    m = re.search(r'filename="?([^";]+)"?', header, re.I)
    return m.group(1).strip() if m else ""


def _default_date_range() -> tuple[str, str]:
    today = date.today()
    return (today - timedelta(days=DEFAULT_RANGE_DAYS)).isoformat(), today.isoformat()


def main() -> int:
    parser = argparse.ArgumentParser(description="飞象供应商平台待发货订单自动拉表（抓包复刻版）")
    parser.add_argument("--begin", help="开始日期 yyyy-MM-dd（默认近 %d 天）" % DEFAULT_RANGE_DAYS)
    parser.add_argument("--end", help="结束日期 yyyy-MM-dd（默认今天）")
    parser.add_argument("--out-dir", default="data-local", help="输出目录（默认 data-local/）")
    parser.add_argument("--force", action="store_true", help="覆盖已存在的同名文件")
    parser.add_argument("--dry-run", action="store_true", help="登录+拉取但不落盘，只打印将保存的路径")
    parser.add_argument("--verbose", action="store_true", help="DEBUG 日志")
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )

    cfg = FeixiangConfig(
        username=os.environ.get("FEIXIANG_USERNAME", ""),
        password=os.environ.get("FEIXIANG_PASSWORD", ""),
        begin=args.begin or "",
        end=args.end or "",
        out_dir=Path(args.out_dir),
        force=args.force,
        dry_run=args.dry_run,
    )
    if not (cfg.username and cfg.password):
        log.error("缺少凭据: 请设置环境变量 FEIXIANG_USERNAME 与 FEIXIANG_PASSWORD")
        return 2
    if not cfg.begin or not cfg.end:
        cfg.begin, cfg.end = _default_date_range()
        log.info("默认日期区间: %s ~ %s", cfg.begin, cfg.end)

    try:
        dest = run(cfg)
    except (FeixiangError, requests.RequestException) as exc:
        log.error("失败: %s", exc)
        return 1

    if dest is not None:
        log.info("完成: %s", dest)
    return 0


if __name__ == "__main__":
    sys.exit(main())
