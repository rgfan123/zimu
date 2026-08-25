#!/usr/bin/env python3
"""彩食鲜「发货结果回填」在线推送脚本（基于 wapi.freshfood.cn 抓包复刻）

链路契约见 docs/research/caishixian-scc-wapi-export-api.md §6:
  POST /ucenter/login/scc                       登录，响应头返回新 login-token
  POST /scc/bbc/order/importDeliverExcl         上传回填 xlsx（multipart file 字段）

用法:
  CSX_USERNAME=<手机号> CSX_PASSWORD=<密码> python3 scripts/caishixian_push_shipments.py \
      --file <回填文件.xlsx> --out <结果.json>

注意: 凭据只从环境变量读取，token 只进内存，绝不落盘、不打日志。
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import sys
from pathlib import Path

import requests

log = logging.getLogger("caishixian-push")

BASE_URL = "https://wapi.freshfood.cn"
ORIGIN = "https://scc.freshfood.cn"
BUSINESS_CODE = "fe-web-scc"
AUTH_HEADER_NAME = "login-token"
SUPPLIER_CODE_HEADER = "supplier-code"
DEFAULT_SUPPLIER_CODE = "20075684"


class CsxError(RuntimeError):
    """业务错误（非 200000 的 code 或上传失败）：平台明确拒绝或本地可确定未受理，可安全重试。"""


class CsxUnknownError(RuntimeError):
    """结果未知（响应非 JSON / 网络中断等）：平台可能已受理，需人工核实后再决定是否重推。"""


def login(session: requests.Session, username: str, password: str) -> str:
    """登录并返回新 login-token（登录响应头返回）。"""
    body = {"username": username, "password": password, "businessCode": BUSINESS_CODE}
    resp = session.post(f"{BASE_URL}/ucenter/login/scc", json=body, timeout=30)
    resp.raise_for_status()
    _unwrap(resp.json())
    new_token = resp.headers.get(AUTH_HEADER_NAME)
    if not new_token:
        raise CsxError("登录成功但响应头没有 login-token")
    return new_token


def push_shipments(session: requests.Session, file_path: Path) -> dict:
    """上传回填 xlsx 到 importDeliverExcl。"""
    log.info("上传回填: %s", file_path.name)
    with open(file_path, "rb") as fh:
        resp = session.post(
            f"{BASE_URL}/scc/bbc/order/importDeliverExcl",
            files={"file": (file_path.name, fh,
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")},
            timeout=60,
        )
    resp.raise_for_status()
    try:
        payload = resp.json()
    except ValueError:
        # 响应不是 JSON：请求可能已被平台受理，结果未知（outcome=unknown）。
        raise CsxUnknownError(f"响应不是 JSON: {resp.text[:200]}")
    code = payload.get("code")
    if code not in (200000, 200):
        # 平台明确拒绝（有平台 code/message）：outcome=rejected，可安全重推/人工处理。
        return {"success": False, "outcome": "rejected", "code": str(code),
                "message": str(payload.get("message") or "平台拒绝上传")}
    return {"success": True, "outcome": "accepted", "platform_ref": str(payload.get("data") or ""),
            "message": "上传成功"}


def _unwrap(payload: dict) -> any:
    code = payload.get("code")
    if code not in (200000, 200):
        raise CsxError(f"接口返回错误: code={code} message={payload.get('message')}")
    return payload.get("data")


def main() -> int:
    parser = argparse.ArgumentParser(description="彩食鲜发货结果回填在线推送")
    parser.add_argument("--file", required=True, help="回填 xlsx 文件路径")
    parser.add_argument("--out", required=True, help="结果 JSON 输出路径")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s %(message)s")

    username = os.environ.get("CSX_USERNAME", "")
    password = os.environ.get("CSX_PASSWORD", "")
    supplier_code = os.environ.get("CSX_SUPPLIER_CODE", DEFAULT_SUPPLIER_CODE)
    if not username or not password:
        log.error("缺少凭据: 请设置 CSX_USERNAME + CSX_PASSWORD")
        return 2

    result = {"success": False, "outcome": "unknown", "code": "", "message": ""}
    try:
        session = requests.Session()
        session.headers.update({
            "Origin": ORIGIN,
            "Referer": ORIGIN + "/",
            "Accept": "application/json, */*",
            "User-Agent": ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                           "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36 Edg/148.0.0.0"),
            SUPPLIER_CODE_HEADER: supplier_code,
        })
        token = login(session, username, password)
        session.headers[AUTH_HEADER_NAME] = token
        result = push_shipments(session, Path(args.file))
    except CsxUnknownError as exc:
        # 结果未知：上传可能已被平台受理，需人工在平台核实后再决定是否重推。
        result = {"success": False, "outcome": "unknown", "code": "SCRIPT_ERROR", "message": str(exc)}
        log.error("结果未知: %s", exc)
    except CsxError as exc:
        # 平台明确拒绝（登录被拒等）：未产生受理副作用，可安全重推。
        result = {"success": False, "outcome": "rejected", "code": "PLATFORM_REJECTED",
                  "message": str(exc)}
        log.error("平台拒绝: %s", exc)
    except (requests.RequestException, OSError) as exc:
        # 网络/超时/未知：outcome=unknown，需人工核实。
        result = {"success": False, "outcome": "unknown", "code": "SCRIPT_ERROR", "message": str(exc)}
        log.error("失败: %s", exc)

    Path(args.out).write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
    return 0 if result.get("success") else 1


if __name__ == "__main__":
    sys.exit(main())
