#!/usr/bin/env python3
"""彩食鲜「待发货订单」自动拉表脚本（基于 wapi.freshfood.cn 抓包复刻）

链路契约见 docs/research/caishixian-scc-wapi-export-api.md:
  POST /ucenter/login/scc                       登录，响应头返回新 login-token
  POST /scc/bbc/order/exportDeliverExcl         发起导出任务 -> data = 任务 ID
  GET  /task/task/my?taskType=...               轮询任务状态（完成判定 taskStatus==2 && progress==100）
  GET  /task/file/download?url=<COS URL>        下载生成的 xlsx（URL 取自 taskAttach[0].url）

认证（2026-08-18 抓包确认）:
  - 请求头 `login-token: <JWT>`（自定义头，无 Bearer 前缀），登录接口响应头返回新值
  - 请求头 `supplier-code: <供应商代码>`（业务接口必带，默认主供应商 20075684）

用法（推荐，自动登录）:
  CSX_USERNAME=<手机号> CSX_PASSWORD=<密码> python3 scripts/caishixian_fetch_orders.py \
      --pay-begin 2026-08-14 --pay-end 2026-08-17

用法（已有 token）:
  CSX_TOKEN=<JWT> python3 scripts/caishixian_fetch_orders.py \
      --pay-begin 2026-08-14 --pay-end 2026-08-17

注意: 凭据只从环境变量读取，token 只进内存，绝不落盘、不打日志。
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

log = logging.getLogger("caishixian")

BASE_URL = "https://wapi.freshfood.cn"
ORIGIN = "https://scc.freshfood.cn"
SYS_CODE = "TASK-SCHEDULING"
TASK_TYPE_SUPPLIER = "csx-b2b-supplier-schedule"  # 供应商排期（待发货导出）
TASK_TYPE_SETTLE = "csx-b2b-settle-schedul"       # 结算
TASK_TYPE_TMS = "csx-b2b-tms-schedule"            # TMS 物流
TASK_TYPE_SCM = "csx-b2b-scm-web-schedul"         # SCM Web

# 抓包确认的完成判定（taskStatus=2, progress=100, resultCode=200000, taskResult=2）
TASK_STATUS_DONE = 2
RESULT_CODE_OK = "200000"

# 认证（2026-08-18 抓包确认，见 docs/research/caishixian-scc-wapi-export-api.md）
AUTH_HEADER_NAME = "login-token"
SUPPLIER_CODE_HEADER = "supplier-code"
DEFAULT_SUPPLIER_CODE = "20075684"  # 主供应商「河北净菜（北京）物流有限公司」
BUSINESS_CODE = "fe-web-scc"        # 登录接口业务码（抓包实测）


class CsxError(RuntimeError):
    """业务错误（非 200000 的 code 或任务失败）。"""


@dataclass
class CsxConfig:
    token: str = ""
    username: str = ""
    password: str = ""
    supplier_code: str = DEFAULT_SUPPLIER_CODE
    pay_begin: str = ""
    pay_end: str = ""
    order_status: str = "3"
    mode: str = "export"               # export=任务导出Excel（默认）| json=JSON直连
    out_dir: Path = Path("data-local")
    force: bool = False
    dry_run: bool = False
    poll_interval: float = 5.0
    poll_max_rounds: int = 20
    timeout: float = 30.0
    _session: requests.Session = field(default=None, repr=False, init=False)  # type: ignore[assignment]

    def session(self) -> requests.Session:
        if self._session is None:
            s = requests.Session()
            s.headers.update(
                {
                    "Origin": ORIGIN,
                    "Referer": ORIGIN + "/",
                    "Accept": "application/json, */*",
                    "User-Agent": (
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36 Edg/148.0.0.0"
                    ),
                }
            )
            self._session = s
        return self._session

    def apply_auth(self) -> None:
        """按当前 token / supplier_code 设置认证头（登录前后调用）。"""
        h = self.session().headers
        if self.token:
            h[AUTH_HEADER_NAME] = self.token
        else:
            h.pop(AUTH_HEADER_NAME, None)
        if self.supplier_code:
            h[SUPPLIER_CODE_HEADER] = self.supplier_code
        else:
            h.pop(SUPPLIER_CODE_HEADER, None)


# ---------------------------------------------------------------- API 方法

def api_login(cfg: CsxConfig) -> str:
    """登录并返回新 login-token（登录响应头返回，见 2026-08-18 抓包）。"""
    body = {
        "username": cfg.username,
        "password": cfg.password,
        "businessCode": BUSINESS_CODE,
    }
    log.info("登录: user=%s", cfg.username)
    resp = cfg.session().post(f"{BASE_URL}/ucenter/login/scc", json=body, timeout=cfg.timeout)
    resp.raise_for_status()
    _unwrap(resp.json())
    new_token = resp.headers.get(AUTH_HEADER_NAME)
    if not new_token:
        raise CsxError("登录成功但响应头没有 login-token")
    return new_token


def export_deliver_excel(cfg: CsxConfig) -> int:
    """发起「企业购导出待发货订单」任务，返回任务 ID。"""
    body = {
        "payTimeBegin": cfg.pay_begin,
        "payTimeEnd": cfg.pay_end,
        "pageNum": 1,
        "pageSize": 10,
        "orderStatus": cfg.order_status,
    }
    log.info("发起导出: payTime=%s~%s orderStatus=%s supplier=%s",
             cfg.pay_begin, cfg.pay_end, cfg.order_status, cfg.supplier_code)
    resp = cfg.session().post(
        f"{BASE_URL}/scc/bbc/order/exportDeliverExcl",
        json=body,
        timeout=cfg.timeout,
    )
    resp.raise_for_status()
    return int(_unwrap(resp.json()))


def list_my_tasks(cfg: CsxConfig, task_type: str) -> list[dict[str, Any]]:
    """查询任务列表。"""
    resp = cfg.session().get(
        f"{BASE_URL}/task/task/my",
        params={"sysCode": SYS_CODE, "taskType": task_type},
        timeout=cfg.timeout,
    )
    resp.raise_for_status()
    return _unwrap(resp.json())


def download_file(cfg: CsxConfig, name: str, url: str, dest: Path) -> Path:
    """通过 /task/file/download 代理下载生成的文件。"""
    resp = cfg.session().get(
        f"{BASE_URL}/task/file/download",
        params={"name": name, "url": url},
        timeout=cfg.timeout * 4,  # 大文件放宽
    )
    resp.raise_for_status()
    if not resp.content.startswith(b"PK"):  # xlsx = ZIP/OOXML 魔数
        raise CsxError(f"下载内容不是 xlsx（魔数 {resp.content[:8]!r}，可能任务未完成）")
    dest.write_bytes(resp.content)
    log.info("已保存: %s (%d B)", dest, len(resp.content))
    return dest


def query_order_list(cfg: CsxConfig) -> dict[str, Any]:
    """JSON 直连：分页拉取订单列表（POST /scc/bbc/order/orderList，2026-08-18 抓包确认）。

    与 exportDeliverExcl 同款筛选参数；响应 data 含 pageNum/pageSize/totalNum/data/number（状态计数）。
    """
    orders: list[dict[str, Any]] = []
    page = 1
    page_size = 10
    total = None
    while True:
        body = {
            "payTimeBegin": cfg.pay_begin,
            "payTimeEnd": cfg.pay_end,
            "pageNum": page,
            "pageSize": page_size,
            "orderStatus": cfg.order_status,
        }
        resp = cfg.session().post(f"{BASE_URL}/scc/bbc/order/orderList", json=body, timeout=cfg.timeout)
        resp.raise_for_status()
        data = _unwrap(resp.json()) or {}
        batch = data.get("data") or []
        orders.extend(batch)
        total = data.get("totalNum", total)
        log.info("第 %d 页: +%d 单（累计 %d / totalNum=%s）", page, len(batch), len(orders), total)
        if not batch or len(orders) >= (total or 0) or len(batch) < page_size:
            break
        page += 1
    return {"total": total or len(orders), "orders": orders, "number": (data or {}).get("number")}


# ---------------------------------------------------------------- 轮询与主流程

def poll_task_until_done(cfg: CsxConfig, task_id: int) -> dict[str, Any]:
    """轮询任务直至完成/失败/超时，返回任务对象。"""
    for round_no in range(1, cfg.poll_max_rounds + 1):
        tasks = list_my_tasks(cfg, TASK_TYPE_SUPPLIER)
        match = next((t for t in tasks if int(t.get("id")) == task_id), None)
        if match is None:
            log.info("[%d/%d] 任务 %s 尚未出现在列表，%ss 后重试",
                     round_no, cfg.poll_max_rounds, task_id, cfg.poll_interval)
        else:
            status = match.get("taskStatus")
            log.info(
                "[%d/%d] 任务 %s status=%s progress=%s/%s result=%s msg=%s",
                round_no, cfg.poll_max_rounds, task_id, status,
                match.get("currProgress"), match.get("totalProgress"),
                match.get("taskResult"), match.get("taskMessage"),
            )
            if (
                status == TASK_STATUS_DONE
                and match.get("currProgress") == match.get("totalProgress") == 100
                and match.get("resultCode") == RESULT_CODE_OK
            ):
                return match
            if status == TASK_STATUS_DONE:  # 完成但非成功：失败任务
                raise CsxError(f"任务失败: result={match.get('taskResult')} msg={match.get('taskMessage')}")
        if round_no < cfg.poll_max_rounds:
            time.sleep(cfg.poll_interval)
    raise CsxError(f"轮询超时（{cfg.poll_max_rounds} 轮 × {cfg.poll_interval}s），任务 {task_id} 未完成")


def _task_attach(task: dict[str, Any]) -> tuple[str, str] | None:
    """从任务对象取 (文件名, 文件URL)。taskAttach 是 JSON 字符串（2026-08-18 实测），内容为 [{name,url}]。"""
    attach = task.get("taskAttach")
    if isinstance(attach, str) and attach.strip():
        try:
            attach = json.loads(attach)
        except json.JSONDecodeError:
            attach = None
    if isinstance(attach, list) and attach:
        item = attach[0]
        if isinstance(item, dict) and item.get("url"):
            return str(item.get("name") or "导出.xlsx"), str(item["url"])
    # 兜底：taskParam 内嵌（旧结构）
    try:
        param = json.loads(task.get("taskParam") or "{}")
    except json.JSONDecodeError:
        param = {}
    inner = param.get("taskParam") or {}
    if isinstance(inner, dict) and inner.get("url"):
        return str(inner.get("name") or "导出.xlsx"), str(inner["url"])
    return None


def run(cfg: CsxConfig) -> Path | None:
    if cfg.username and cfg.password:
        cfg.token = api_login(cfg)  # 登录请求本身不带认证头，成功后设置
    cfg.apply_auth()

    if cfg.mode == "json":
        return _run_json(cfg)

    task_id = export_deliver_excel(cfg)
    log.info("任务已创建: id=%s", task_id)
    task = poll_task_until_done(cfg, task_id)

    attach = _task_attach(task)
    if attach is None:
        raise CsxError("任务完成但响应中未找到文件 URL（taskAttach 为空），需补抓确认")
    name, file_url = attach

    if cfg.dry_run:
        log.info("[dry-run] 跳过下载: name=%s url=%s", name, file_url[:120])
        return None

    cfg.out_dir.mkdir(parents=True, exist_ok=True)
    dest = cfg.out_dir / name
    if dest.exists() and not cfg.force:
        log.info("已存在同名文件，跳过（--force 覆盖）: %s", dest)
        return dest
    return download_file(cfg, name, file_url, dest)


def _run_json(cfg: CsxConfig) -> Path | None:
    """JSON 直连模式：POST /scc/bbc/order/orderList 分页拉取，落 data-local/彩食鲜订单-YYYY-MM-DD.json。"""
    result = query_order_list(cfg)
    log.info("共拉取订单 %d 条（totalNum=%s）", len(result["orders"]), result["total"])
    if cfg.dry_run:
        log.info("[dry-run] 跳过保存")
        return None
    cfg.out_dir.mkdir(parents=True, exist_ok=True)
    dest = cfg.out_dir / f"彩食鲜订单-{cfg.pay_end}.json"
    if dest.exists() and not cfg.force:
        log.info("已存在同名文件，跳过（--force 覆盖）: %s", dest)
        return dest
    payload = {
        "range": {"payTimeBegin": cfg.pay_begin, "payTimeEnd": cfg.pay_end},
        "orderStatus": cfg.order_status,
        "fetched_at": datetime.now(timezone.utc).isoformat(),
        "total": result["total"],
        "number": result["number"],
        "orders": result["orders"],
    }
    dest.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    log.info("已保存: %s", dest)
    return dest


# ---------------------------------------------------------------- 工具

def _unwrap(payload: dict[str, Any]) -> Any:
    """统一业务包装 {code,message,data} 解包。"""
    code = payload.get("code")
    if code not in (200000, 200):
        raise CsxError(f"接口返回错误: code={code} message={payload.get('message')}")
    return payload.get("data")


def _default_date_range() -> tuple[str, str]:
    today = date.today()
    return (today - timedelta(days=1)).isoformat(), today.isoformat()


def main() -> int:
    parser = argparse.ArgumentParser(description="彩食鲜待发货订单自动拉表（抓包复刻版）")
    parser.add_argument("--pay-begin", help="支付开始日期 yyyy-MM-dd（默认昨天）")
    parser.add_argument("--pay-end", help="支付结束日期 yyyy-MM-dd（默认今天）")
    parser.add_argument("--order-status", default="3", help="订单状态筛选（3=待发货）")
    parser.add_argument("--mode", default="export", choices=["export", "json"],
                        help="export=任务导出Excel（默认）| json=orderList JSON直连")
    parser.add_argument("--supplier-code", default=DEFAULT_SUPPLIER_CODE,
                        help=f"供应商代码（默认 {DEFAULT_SUPPLIER_CODE} 主供应商）")
    parser.add_argument("--out-dir", default="data-local", help="输出目录（默认 data-local/）")
    parser.add_argument("--force", action="store_true", help="覆盖已存在的同名文件")
    parser.add_argument("--dry-run", action="store_true", help="发起+轮询但不下载")
    parser.add_argument("--poll-interval", type=float, default=5.0, help="轮询间隔秒数（默认 5）")
    parser.add_argument("--poll-rounds", type=int, default=20, help="最大轮询次数（默认 20）")
    parser.add_argument("--verbose", action="store_true", help="DEBUG 日志")
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )

    cfg = CsxConfig(
        username=os.environ.get("CSX_USERNAME", ""),
        password=os.environ.get("CSX_PASSWORD", ""),
        token=os.environ.get("CSX_TOKEN", ""),
        supplier_code=args.supplier_code,
        pay_begin=args.pay_begin or "",
        pay_end=args.pay_end or "",
        order_status=args.order_status,
        mode=args.mode,
        out_dir=Path(args.out_dir),
        force=args.force,
        dry_run=args.dry_run,
        poll_interval=args.poll_interval,
        poll_max_rounds=args.poll_rounds,
    )
    if not (cfg.username and cfg.password) and not cfg.token:
        log.error("缺少凭据: 请设置 CSX_USERNAME+CSX_PASSWORD（自动登录）或 CSX_TOKEN")
        return 2
    if not cfg.pay_begin or not cfg.pay_end:
        cfg.pay_begin, cfg.pay_end = _default_date_range()

    try:
        dest = run(cfg)
    except (CsxError, requests.RequestException) as exc:
        log.error("失败: %s", exc)
        return 1

    if dest is not None:
        log.info("完成: %s", dest)
    return 0


if __name__ == "__main__":
    sys.exit(main())
