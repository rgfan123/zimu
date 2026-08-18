#!/usr/bin/env python3
"""wecom-message-intake 13：MCP 运行时验收（JSON-RPC 2.0 over stdio，真实 jar 进程）。

验收点：
1. initialize 握手
2. tools/list 工具发现：17 个允许工具在列，19 个终局工具缺席
3. 实际调用一个只读工具（get_message_submission 不存在 → 业务 NOT_FOUND，证明协议可用）
4. 认证语义：agent-identity 由环境注入，请求里无 operator 参数
"""
import json
import pathlib
import pathlib
import os
import subprocess
import sys

REPO = str(pathlib.Path(__file__).resolve().parents[3])
ENV_FILE = os.path.join(REPO, "backend/.env.acceptance.local")
JAR = os.path.join(REPO, "backend/target/fulfillment-hub-0.1.0-SNAPSHOT.jar")

env = dict(os.environ)
with open(ENV_FILE) as f:
    for line in f:
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k] = v
env["SERVER_PORT"] = "8082"
env["MCP_ENABLED"] = "true"
env["MCP_AGENT_IDENTITY"] = "acceptance-agent"
env["WECOM_ENABLED"] = "false"  # MCP 验收不需要真实企微连接

proc = subprocess.Popen(
    ["java", "-jar", JAR],
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.DEVNULL,
    env=env,
)

results = []


def read_json():
    while True:
        line = proc.stdout.readline()
        if not line:
            raise RuntimeError("MCP 进程 stdout 关闭")
        line = line.strip()
        if line:
            try:
                return json.loads(line)
            except json.JSONDecodeError:
                continue


def rpc(req_id, method, params=None):
    payload = {"jsonrpc": "2.0", "id": req_id, "method": method}
    if params is not None:
        payload["params"] = params
    proc.stdin.write((json.dumps(payload) + "\n").encode())
    proc.stdin.flush()
    return read_json()


try:
    resp = rpc(1, "initialize", {"protocolVersion": "2024-11-05", "capabilities": {}, "clientInfo": {"name": "acceptance", "version": "1"}})
    ok_init = resp.get("result", {}).get("serverInfo", {}).get("name") == "fulfillment-hub-mcp"
    results.append(("initialize 握手", ok_init, resp.get("result", {}).get("serverInfo")))

    resp = rpc(2, "tools/list", {})
    tools = resp.get("result", {}).get("tools", [])
    names = {t["name"] for t in tools}

    allowed = {
        "list_channel_messages", "get_channel_message", "get_message_submission",
        "list_interpretations", "list_message_media",
        "list_order_drafts", "get_order_draft",
        "list_tracking_drafts", "get_tracking_draft",
        "get_order_draft_candidates", "get_tracking_draft_candidates",
        "list_review_cases", "get_review_case",
        "reinterpret_submission", "submit_order_draft_suggestion",
        "submit_supplementary_material", "submit_review_request",
    }
    forbidden = {
        "confirm_order", "confirm_order_draft", "confirm_tracking_draft",
        "confirm_tracking_drafts", "batch_confirm_tracking_drafts",
        "batch_confirm_tracking", "create_customer", "bind_channel_identity",
        "close_review_case", "dismiss_review_case", "reject_order_draft",
        "resolve_review_case", "resolve_customer", "resolve_sku",
        "revise_order", "modify_order", "cancel_order",
        "shipment_jd_outbound_submit",
    }
    missing = allowed - names
    leaked = names & forbidden
    results.append(("允许工具 17 个全在（缺失: %s）" % (sorted(missing) or "无"), not missing, len(names)))
    results.append(("终局工具缺席（泄漏: %s）" % (sorted(leaked) or "无"), not leaked, None))

    full_text = json.dumps(resp)
    no_leak = all(s not in full_text for s in ("SECRET", "TOKEN", "PASSWORD", "MCP_ENABLED", "MCP_AGENT_IDENTITY"))
    results.append(("工具发现不泄漏凭据/配置名", no_leak, None))

    resp = rpc(3, "tools/call", {"name": "get_message_submission", "arguments": {"submission_id": "999999"}})
    body = resp.get("result", {}).get("content", [{}])[0].get("text", "")
    ok_call = "NOT_FOUND" in body or "business_code" in body
    results.append(("只读工具实际调用（不存在的提交 → 业务 NOT_FOUND）", ok_call, body[:120]))

finally:
    proc.stdin.close()
    proc.terminate()
    try:
        proc.wait(timeout=15)
    except subprocess.TimeoutExpired:
        proc.kill()

print("=" * 70)
all_ok = True
for name, ok, detail in results:
    all_ok = all_ok and ok
    print("[%s] %s %s" % ("PASS" if ok else "FAIL", name, "" if detail is None else "| %s" % detail))
print("=" * 70)
print("MCP 运行时验收：%s" % ("全部通过" if all_ok else "存在失败"))
sys.exit(0 if all_ok else 1)
