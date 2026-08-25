#!/usr/bin/env python3
"""聚福宝「发货结果回填」在线推送脚本（基于 supplier-apis.jufubao.cn 抓包复刻）

链路契约见 docs/research/jufubao-supplier-export-api.md §4.1:
  GET  https://g.jufubao.cn/                              种会话 cookie JFB_SESSION_CID
  POST /idaas-auth/v1/login-by-username                   登录，Set-Cookie 下发 3 个 JWT
  GET  /order-public/v1/logistics-company/options         物流公司字典（label → value）
  POST /order-supplier/v1/logistics/multi-send            发货回传（JSON package_list）

输入为 Java 侧（POI）解析来源回填 xlsx 后产出的结构化 JSON（--payload）：
  [{"main_order_id","sub_order_id","product_id","product_name","product_sku_id",
    "num","logistics_number","carrier_name","receipt_username","receipt_phone_number",
    "address_detail"}, ...]
脚本按收货人快照分组构造 package_list 投递到 multi-send；物流公司按字典 label 映射
company_id（未命中整单拒绝并给出名单）。

契约事实（HAR 实测）：multi-send 平台响应是**单个** JSON 信封 {"code","message","request_id"}，
**没有逐行（package/product）响应结构**——受理是全有全无，失败时整单拒绝。因此脚本把平台
原始响应全文写入结果 JSON 的 platform_response 字段透出（不再只取 message 截断），Java 侧并入
push_error 供前端/人工核对；body 非 JSON/非对象/缺 code 一律归 outcome=unknown（结果未知，
需人工核实后再决定是否重推）。

用法:
  JFUBAO_USERNAME=<账号> JFUBAO_PASSWORD=<密码> python3 scripts/jufubao_push_shipments.py \
      --payload <rows.json> --out <结果.json>

注意: 凭据只从环境变量读取，绝不落盘、不打日志。
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import sys
from pathlib import Path

import requests

log = logging.getLogger("jufubao-push")

API_BASE = "https://supplier-apis.jufubao.cn"
PORTAL_BASE = "https://g.jufubao.cn"
LOGIN_PATH = "/idaas-auth/v1/login-by-username"
COMPANY_OPTIONS_PATH = "/order-public/v1/logistics-company/options"
MULTI_SEND_PATH = "/order-supplier/v1/logistics/multi-send"


class JufubaoError(RuntimeError):
    """业务错误。"""


def login(session: requests.Session, username: str, password: str) -> None:
    """1) 访问前端页面种 JFB_SESSION_CID；2) 表单登录，session 自动保存 3 个 JWT cookie。"""
    s = session
    s.get(PORTAL_BASE + "/", timeout=30)
    r = s.post(
        API_BASE + LOGIN_PATH,
        data={"username": username, "password": password, "system": "supplier"},
        headers={"Origin": PORTAL_BASE, "Referer": PORTAL_BASE + "/",
                 "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"},
        timeout=30,
    )
    r.raise_for_status()
    body = r.json()
    if body.get("access_token_cookie_key") != "JFB-ADMIN-ACCESS-TOKEN":
        raise JufubaoError(f"登录失败: {json.dumps(body, ensure_ascii=False)[:300]}")
    if not r.cookies.get("JFB-ADMIN-ACCESS-TOKEN"):
        raise JufubaoError("登录成功但未收到 JFB-ADMIN-ACCESS-TOKEN cookie")
    csrf = r.cookies.get("JFB-ADMIN-CSRF-TOKEN")
    if csrf:
        s.headers["JFB-CSRF-TOKEN"] = csrf
    log.info("登录成功")


def carrier_company_ids(session: requests.Session) -> dict[str, int]:
    """物流公司字典：名称 → company_id。"""
    r = session.get(API_BASE + COMPANY_OPTIONS_PATH,
                    headers={"Origin": PORTAL_BASE, "Referer": PORTAL_BASE + "/"}, timeout=30)
    r.raise_for_status()
    items = r.json().get("items") or []
    mapping = {}
    for item in items:
        label = str(item.get("label") or "").strip()
        value = item.get("value")
        if label and value is not None:
            mapping[label] = int(value)
    return mapping


def build_package_list(rows: list[dict], carrier_ids: dict[str, int]) -> tuple[list[dict], list[str]]:
    """按收货人快照分组构造 multi-send package_list；返回 (packages, 未映射物流公司列表)。"""
    packages: dict[tuple, dict] = {}
    unmapped: set[str] = set()
    for row in rows:
        receiver = (row.get("receipt_username") or "", row.get("receipt_phone_number") or "",
                    row.get("address_detail") or "")
        carrier_name = row.get("carrier_name") or ""
        company_id = carrier_ids.get(carrier_name)
        if company_id is None:
            unmapped.add(carrier_name)
        package = packages.setdefault(receiver, {
            "receipt_username": receiver[0],
            "receipt_phone_number": receiver[1],
            "address_detail": receiver[2],
            "subscribe_time": "",
            "comment": "",
            "company_id": company_id,
            "product_list": [],
        })
        if company_id is not None:
            package["company_id"] = company_id
        package["product_list"].append({
            "main_order_id": row.get("main_order_id") or "",
            "sub_order_id": row.get("sub_order_id") or "",
            "product_id": row.get("product_id") or "",
            "product_name": row.get("product_name") or "",
            "product_sku_id": row.get("product_sku_id") or "0",
            "num": _to_int(row.get("num")),
            "logistics_number": row.get("logistics_number") or "",
            "remarks": "",
        })
    return list(packages.values()), sorted(unmapped)


def push_multi_send(session: requests.Session, packages: list[dict]) -> dict:
    """POST multi-send；成功返回平台引用，失败/未知返回明细。

    平台响应契约（HAR 实测）：无论成功/失败都返回单个 JSON 信封
    {"code","message","request_id"}——**平台没有逐行（package/product）响应结构**，
    multi-send 是全有全无式受理。因此失败时把平台原始响应全文透出给 Java 侧
    （platform_response 字段，供 push_error 展示/按 request_id 在平台核对），
    不做不存在的逐行归因。

    响应判定（防误判）：
      - 响应不是 JSON / 不是对象 / 缺少 code 键 → outcome=unknown（平台可能已受理，
        结果未知，需人工核实后再决定是否重推；不再按当前逻辑误判为成功）。
      - code ∈ {"0","200"} → accepted（全有全无受理）。
      - 其他 code → rejected（平台明确拒绝，可安全重推/人工处理）。
    """
    payload = {
        "is_need_logistics": "Y",
        "company_id": packages[0].get("company_id"),
        "package_list_json": json.dumps(packages, ensure_ascii=False),
    }
    r = session.post(
        API_BASE + MULTI_SEND_PATH,
        json=payload,
        headers={"Origin": PORTAL_BASE, "Referer": PORTAL_BASE + "/"},
        timeout=60,
    )
    r.raise_for_status()
    try:
        body = r.json()
    except ValueError:
        # 响应不是 JSON：平台可能已受理但响应不可解析 → 结果未知。
        return {"success": False, "outcome": "unknown", "code": "SCRIPT_ERROR",
                "message": "multi-send 响应不是 JSON，结果未知",
                "platform_response": {"raw": (r.text or "")[:1000]}}
    if not isinstance(body, dict):
        # 契约上平台总是返回对象信封；非对象响应既不能判定受理也不能判定拒绝 → 结果未知。
        return {"success": False, "outcome": "unknown", "code": "SCRIPT_ERROR",
                "message": f"multi-send 响应不是 JSON 对象（{type(body).__name__}），结果未知",
                "platform_response": {"raw": json.dumps(body, ensure_ascii=False)[:1000]}}
    code = body.get("code")
    if code is None:
        # 响应缺 code 键：既不能判定受理也不能判定拒绝 → 结果未知（需人工核实后再决定是否重推）。
        return {"success": False, "outcome": "unknown", "code": "SCRIPT_ERROR",
                "message": "multi-send 响应缺少 code 字段，结果未知",
                "platform_response": body}
    if str(code) not in ("0", "200"):
        # 平台明确拒绝（有平台 code/message/request_id）：outcome=rejected，可安全重推/人工处理。
        # platform_response 透出平台原始响应全文（code/message/request_id），不截断。
        return {"success": False, "outcome": "rejected", "code": str(code),
                "message": str(body.get("message") or "平台拒绝回传"),
                "platform_response": body}
    # 平台已受理（code 0/200）：全有全无受理，无逐行结果；透出原始响应供审计核对。
    return {"success": True, "outcome": "accepted", "platform_ref": str(body.get("request_id") or ""),
            "message": "回传成功", "platform_response": body}


class InvalidQuantityError(ValueError):
    """数量字段非法或非正整数：整单拒绝（不静默置 0，A7）。"""


def _to_int(value) -> int:
    """严格数量解析：非法或非正整数抛 InvalidQuantityError，整个 payload 拒绝。"""
    try:
        n = int(value)
    except (TypeError, ValueError):
        raise InvalidQuantityError(f"数量非法（必须为正整数，无小数）: {value!r}")
    if n <= 0:
        raise InvalidQuantityError(f"数量必须为正整数: {value!r}")
    return n


def main() -> int:
    parser = argparse.ArgumentParser(description="聚福宝发货结果回填在线推送")
    parser.add_argument("--payload", required=True, help="结构化回填行 JSON 文件路径（Java POI 解析产物）")
    parser.add_argument("--out", required=True, help="结果 JSON 输出路径")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s %(message)s")

    username = os.environ.get("JFUBAO_USERNAME", "")
    password = os.environ.get("JFUBAO_PASSWORD", "")
    if not username or not password:
        log.error("缺少凭据: 请设置 JFUBAO_USERNAME + JFUBAO_PASSWORD")
        return 2

    result = {"success": False, "outcome": "unknown", "code": "", "message": ""}
    try:
        rows = json.loads(Path(args.payload).read_text(encoding="utf-8"))
        if not isinstance(rows, list) or not rows:
            result = {"success": False, "outcome": "rejected", "code": "EMPTY_PAYLOAD",
                      "message": "回填数据为空"}
        else:
            session = requests.Session()
            session.headers.update({
                "Accept": "application/json, text/plain, */*",
                "User-Agent": ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                               "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36 Edg/148.0.0.0"),
                "X-Jfb-Project-Id": "supplier",
            })
            login(session, username, password)
            carrier_ids = carrier_company_ids(session)
            packages, unmapped = build_package_list(rows, carrier_ids)
            if unmapped:
                result = {"success": False, "outcome": "rejected", "code": "CARRIER_UNMAPPED",
                          "message": "物流公司未在聚福宝字典: " + "、".join(unmapped)}
            elif not packages:
                result = {"success": False, "outcome": "rejected", "code": "EMPTY_PACKAGE",
                          "message": "未构造出包裹"}
            else:
                result = push_multi_send(session, packages)
    except InvalidQuantityError as exc:
        result = {"success": False, "outcome": "rejected", "code": "INVALID_QUANTITY",
                  "message": str(exc)}
        log.error("数量校验失败: %s", exc)
    except (JufubaoError, requests.RequestException, OSError, ValueError) as exc:
        # 网络/超时/未知：平台可能已受理但响应未达，outcome=unknown，需人工核实后再决定是否重推。
        result = {"success": False, "outcome": "unknown", "code": "SCRIPT_ERROR", "message": str(exc)}
        log.error("失败: %s", exc)

    Path(args.out).write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
    return 0 if result.get("success") else 1


if __name__ == "__main__":
    sys.exit(main())
