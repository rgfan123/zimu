#!/usr/bin/env python3
"""聚福宝「待发货订单」拉取脚本（基于 supplier-apis.jufubao.cn 抓包复刻）

链路契约见 docs/research/jufubao-supplier-export-api.md:
  GET  https://g.jufubao.cn/                              前端页面，种会话 cookie JFB_SESSION_CID
  POST /idaas-auth/v1/login-by-username                   登录，Set-Cookie 下发 3 个 JWT
       （JFB-ADMIN-ACCESS-TOKEN 访问令牌 ~12.8h / JFB-ADMIN-REFRESH-TOKEN 刷新 15 天 / CSRF）
  POST /order-supplier/v1/orders/query                    订单查询（JSON），tab=no_delivery 即待发货

聚福宝无 Excel 导出接口，走 JSON 直连（与彩食鲜/飞象的导出模式不同）。

用法（推荐，自动登录）:
  JFUBAO_USERNAME=<账号> JFUBAO_PASSWORD=<密码> python3 scripts/jufubao_fetch_orders.py \
      --begin 2026-07-18 --end 2026-08-18

用法（已有 cookie 直连）:
  JFUBAO_COOKIE="JFB_SESSION_CID=xxx; JFB-ADMIN-ACCESS-TOKEN=yyy" python3 scripts/jufubao_fetch_orders.py

注意: 凭据只从环境变量读取，绝不落盘、不打日志。
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import sys
import time
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import requests

log = logging.getLogger("jufubao")

API_BASE = "https://supplier-apis.jufubao.cn"
PORTAL_BASE = "https://g.jufubao.cn"
LOGIN_PATH = "/idaas-auth/v1/login-by-username"
ORDERS_QUERY_PATH = "/order-supplier/v1/orders/query"

TAB_NO_DELIVERY = "no_delivery"  # 待发货（默认）
TAB_DELIVERED = "delivered"      # 已发货
TAB_ALL = "all"                  # 全部


class JufubaoError(RuntimeError):
    """业务错误。"""


@dataclass
class JufubaoConfig:
    username: str = ""
    password: str = ""
    cookie: str = ""                     # 直连模式：完整 Cookie 字符串
    tab: str = TAB_NO_DELIVERY
    begin: str = ""                      # yyyy-MM-dd，转 epoch 秒
    end: str = ""
    page_size: int = 20
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
                    "Accept": "application/json, text/plain, */*",
                    "User-Agent": (
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36 Edg/148.0.0.0"
                    ),
                    "X-Jfb-Project-Id": "supplier",  # 抓包实测必带
                }
            )
            if self.cookie:
                s.headers["Cookie"] = self.cookie
                # 直连模式：从 cookie 字符串解析 CSRF token 补头（抓包实测 JFB-CSRF-TOKEN 头必带）
                csrf = _cookie_value(self.cookie, "JFB-ADMIN-CSRF-TOKEN")
                if csrf:
                    s.headers["JFB-CSRF-TOKEN"] = csrf
            self._session = s
        return self._session


# ---------------------------------------------------------------- API 方法

def api_login(cfg: JufubaoConfig) -> None:
    """1) 访问前端页面种 JFB_SESSION_CID；2) 表单登录，session 自动保存 3 个 JWT cookie。"""
    s = cfg.session()
    log.info("初始化会话（前端页面）...")
    r = s.get(PORTAL_BASE + "/", timeout=cfg.timeout)
    r.raise_for_status()

    log.info("登录: user=%s", cfg.username)
    r = s.post(
        API_BASE + LOGIN_PATH,
        data={"username": cfg.username, "password": cfg.password, "system": "supplier"},
        headers={"Origin": PORTAL_BASE, "Referer": PORTAL_BASE + "/",
                 "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"},
        timeout=cfg.timeout,
    )
    r.raise_for_status()
    body = r.json()
    if body.get("access_token_cookie_key") != "JFB-ADMIN-ACCESS-TOKEN":
        raise JufubaoError(f"登录失败: {json.dumps(body, ensure_ascii=False)[:300]}")
    if not r.cookies.get("JFB-ADMIN-ACCESS-TOKEN"):
        raise JufubaoError("登录成功但未收到 JFB-ADMIN-ACCESS-TOKEN cookie")
    csrf = r.cookies.get("JFB-ADMIN-CSRF-TOKEN")
    if csrf:
        s.headers["JFB-CSRF-TOKEN"] = csrf  # 抓包实测：业务请求必带 CSRF 头
    log.info("登录成功（access_token %ss 有效）", body.get("access_token_expire_in"))


def query_orders(cfg: JufubaoConfig) -> list[dict[str, Any]]:
    """分页拉取订单，返回订单列表（list 字段拼接）。"""
    start_ts = _to_epoch(cfg.begin)
    end_ts = _to_epoch(cfg.end)
    body = {
        "tab": cfg.tab,
        "filter": {"created_time_range": {"start_time": start_ts, "end_time": end_ts}},
        "page_token": "1",
        "page_size": cfg.page_size,
        "system": "supplier",
    }
    orders: list[dict[str, Any]] = []
    page = 0
    while True:
        page += 1
        r = cfg.session().post(
            API_BASE + ORDERS_QUERY_PATH,
            json=body,
            headers={"Origin": PORTAL_BASE, "Referer": PORTAL_BASE + "/"},
            timeout=cfg.timeout,
        )
        r.raise_for_status()
        payload = r.json()
        if "list" not in payload:
            raise JufubaoError(f"orders/query 异常响应: {json.dumps(payload, ensure_ascii=False)[:300]}")
        batch = payload.get("list") or []
        orders.extend(batch)
        log.info("第 %d 页: +%d 单（累计 %d，total_size=%s）", page, len(batch), len(orders), payload.get("total_size"))
        next_token = payload.get("next_page_token")
        if not next_token or not batch:
            break
        body["page_token"] = next_token
    return orders


# ---------------------------------------------------------------- 主流程

def run(cfg: JufubaoConfig) -> Path | None:
    if cfg.username and cfg.password:
        api_login(cfg)
    elif not cfg.cookie:
        raise JufubaoError("缺少凭据: 设置 JFUBAO_USERNAME+JFUBAO_PASSWORD（自动登录）或 JFUBAO_COOKIE（直连）")

    orders = query_orders(cfg)
    log.info("共拉取订单 %d 条（tab=%s）", len(orders), cfg.tab)

    if cfg.dry_run:
        log.info("[dry-run] 跳过保存")
        return None

    cfg.out_dir.mkdir(parents=True, exist_ok=True)
    fname = f"聚福宝订单-{cfg.tab}-{cfg.end}.json"
    dest = cfg.out_dir / fname
    if dest.exists() and not cfg.force:
        log.info("已存在同名文件，跳过（--force 覆盖）: %s", dest)
        return dest
    payload = {
        "tab": cfg.tab,
        "range": {"begin": cfg.begin, "end": cfg.end},
        "fetched_at": datetime.now(timezone.utc).isoformat(),
        "total": len(orders),
        "orders": orders,
    }
    dest.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    log.info("已保存: %s", dest)
    return dest


# ---------------------------------------------------------------- 工具

def _to_epoch(day: str) -> int:
    """yyyy-MM-dd -> epoch 秒（Asia/Shanghai 当日 00:00）。"""
    dt = datetime.strptime(day, "%Y-%m-%d").replace(tzinfo=timezone(timedelta(hours=8)))
    return int(dt.timestamp())


def _cookie_value(cookie_str: str, name: str) -> str | None:
    """从 'A=1; B=2' 格式 cookie 字符串中取指定键值。"""
    for part in cookie_str.split(";"):
        part = part.strip()
        if part.startswith(name + "="):
            return part[len(name) + 1:]
    return None


def _default_range() -> tuple[str, str]:
    today = date.today()
    return (today - timedelta(days=30)).isoformat(), today.isoformat()


def main() -> int:
    parser = argparse.ArgumentParser(description="聚福宝待发货订单拉取（JSON 直连版）")
    parser.add_argument("--begin", help="开始日期 yyyy-MM-dd（默认近 30 天）")
    parser.add_argument("--end", help="结束日期 yyyy-MM-dd（默认今天）")
    parser.add_argument("--tab", default=TAB_NO_DELIVERY,
                        choices=[TAB_NO_DELIVERY, TAB_DELIVERED, TAB_ALL], help="订单分类（默认 no_delivery 待发货）")
    parser.add_argument("--page-size", type=int, default=20, help="每页数量（默认 20）")
    parser.add_argument("--out-dir", default="data-local", help="输出目录（默认 data-local/）")
    parser.add_argument("--force", action="store_true", help="覆盖已存在的同名文件")
    parser.add_argument("--dry-run", action="store_true", help="拉取但不保存")
    parser.add_argument("--verbose", action="store_true", help="DEBUG 日志")
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )

    cfg = JufubaoConfig(
        username=os.environ.get("JFUBAO_USERNAME", ""),
        password=os.environ.get("JFUBAO_PASSWORD", ""),
        cookie=os.environ.get("JFUBAO_COOKIE", ""),
        tab=args.tab,
        begin=args.begin or "",
        end=args.end or "",
        page_size=args.page_size,
        out_dir=Path(args.out_dir),
        force=args.force,
        dry_run=args.dry_run,
    )
    if not cfg.begin or not cfg.end:
        cfg.begin, cfg.end = _default_range()

    try:
        dest = run(cfg)
    except (JufubaoError, requests.RequestException) as exc:
        log.error("失败: %s", exc)
        return 1

    if dest is not None:
        log.info("完成: %s", dest)
    return 0


if __name__ == "__main__":
    sys.exit(main())
