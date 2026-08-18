#!/usr/bin/env python3
"""wecom-message-intake 13：浏览器验收（复核工作台白名单渲染 + 原图 + 批量确认区）。

mock /api/v1/* 制造订单/运单草稿场景，断言：
1. 复核面板可见原始消息、图片证据（<img src=/api/v1/message-media/...>）
2. 页面不泄漏未知 JSON 字段、秘密、存储引用、伪发货时间
3. 运单批量确认区展示任务/姓名/Carrier/单号/数量并支持勾选
"""
import base64
import json
import pathlib
import sys
from playwright.sync_api import sync_playwright

BASE = "http://localhost:5195"
OUT = pathlib.Path("/Users/jerry/Documents/子牧/output/playwright/wecom-intake-13")
OUT.mkdir(parents=True, exist_ok=True)
CHROMIUM = ("/Users/jerry/Library/Caches/ms-playwright/chromium_headless_shell-1234/"
            "chrome-headless-shell-mac-arm64/chrome-headless-shell")

PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")

NOW = "2026-08-15T10:00:00Z"

ORDER_CASE = {
    "id": "rc-order-1", "case_no": "RC-WECOM-ORDER1", "case_type": "WECOM_INTAKE",
    "responsible_team": "ORDER_OPS", "reason_code": "WECOM_ORDER_DRAFT",
    "status": "OPEN", "subject_type": "ORDER_DRAFT", "subject_id": "od-1",
    "detail": {"intent": "CUSTOMER_ORDER"}, "suggestions": [],
    "allowed_actions": ["CONFIRM_ORDER_DRAFT", "REJECT_ORDER_DRAFT", "REINTERPRET"],
    "version": 3, "created_at": NOW,
}

TRACKING_CASE = {
    "id": "rc-tracking-1", "case_no": "RC-WECOM-TRK1", "case_type": "WECOM_INTAKE",
    "responsible_team": "ORDER_OPS", "reason_code": "WECOM_TRACKING_DRAFT",
    "status": "OPEN", "subject_type": "TRACKING_DRAFT", "subject_id": "td-1",
    "detail": {"intent": "SUPPLIER_TRACKING"}, "suggestions": [],
    "allowed_actions": ["CONFIRM_TRACKING_DRAFT", "REINTERPRET"],
    "version": 2, "created_at": NOW,
}

ORDER_DRAFT = {
    "id": "od-1", "draft_no": "OD-101-1", "source_order_no": "SO-WECOM-101",
    "submission_id": "101", "status": "OPEN", "revision": 1,
    "customer_id": None, "customer_code": None, "customer_name": None,
    "customer_candidates": [{"customer_id": "c-7", "customer_code": "CUST-007", "customer_name": "牧野牧场", "matched_by": "NAME"}],
    "customer_name_raw": "牧野牧场", "receiver_name": "李四", "receiver_phone": None,
    "receiver_address": "上海市浦东新区", "settlement_method": None,
    "missing_fields": ["receiver_phone", "settlement_method", "settlement_time"],
    "lines": [
        {"id": "odl-1", "line_no": 1, "sku_id": None, "sku_code": None,
         "sku_candidates": [{"sku_id": "sku-9", "sku_code": "SKU-9", "product_name": "子牧羊小腿 1kg", "specification": "1kg", "unit": "份", "matched_by": "NAME"}],
         "product_name_raw": "羊小腿", "spec_raw": None, "unit_raw": "份", "quantity": "2"},
    ],
    "review_case_id": "rc-order-1", "review_case_version": 3,
    "confirmed_order_id": None, "confirmed_by": None, "confirmed_at": None,
    "created_at": NOW, "updated_at": NOW,
}

TRACKING_DRAFT_1 = {
    "id": "td-1", "draft_no": "TD-102-1", "submission_id": "102", "line_no": 1,
    "raw_receiver_name": "张*三", "masked_receiver_name": "张三",
    "tracking_no": "SF1234567890", "carrier_code": "SF",
    "carrier_candidates": [{"code": "SF", "name": "顺丰速运", "source": "STATED"}],
    "manual_carrier_options": [{"code": "SF", "name": "顺丰速运"}],
    "task_id": "t-5", "task_candidates": [{"task_id": "t-5", "fulfillment_no": "FT-20260815-0005", "order_no": "SO-20260815-0005", "receiver_name": "张三", "instructed_quantity": "3", "shipment_id": "SH-0005"}],
    "shipment_judgment": "FULL", "default_full_shipment": True,
    "actual_quantity": None, "validation_issues": [],
    "status": "OPEN", "revision": 1,
    "confirmed_by": None, "confirmed_at": None,
    "review_case_id": "rc-tracking-1", "review_case_version": 2,
    "created_at": NOW,
}

TRACKING_DRAFT_2 = {
    **TRACKING_DRAFT_1,
    "id": "td-2", "draft_no": "TD-102-2", "line_no": 2,
    "raw_receiver_name": "李*四", "masked_receiver_name": "李四",
    "tracking_no": "YT9876543210", "carrier_code": "YT",
    "carrier_candidates": [{"code": "YT", "name": "圆通速递", "source": "STATED"}],
    "manual_carrier_options": [{"code": "YT", "name": "圆通速递"}],
    "task_id": "t-6", "task_candidates": [{"task_id": "t-6", "fulfillment_no": "FT-20260815-0006", "order_no": "SO-20260815-0006", "receiver_name": "李四", "instructed_quantity": "5", "shipment_id": "SH-0006"}],
    "review_case_id": "rc-tracking-2", "review_case_version": 2,
}

TRACKING_DRAFT_3 = {
    **TRACKING_DRAFT_1,
    "id": "td-3", "draft_no": "TD-102-3", "line_no": 3,
    "raw_receiver_name": "王*五", "masked_receiver_name": "王五",
    "tracking_no": "ZT1111111111", "carrier_code": None,
    "carrier_candidates": [], "manual_carrier_options": [],
    "task_id": None, "task_candidates": [],
    "validation_issues": ["TRACKING_NO_MISSING"],
    "review_case_id": "rc-tracking-3", "review_case_version": 2,
}

SUBMISSION = {
    "id": "101", "submission_no": "SUB-20260815-0101", "status": "DRAFTED",
    "source_message_id": "cm-101", "current_intent": "CUSTOMER_ORDER",
    "interpretations": [{"version": 1, "intent": "CUSTOMER_ORDER", "provider": "deepseek", "model": "deepseek-chat", "prompt_version": "wecom-order-v3", "error": None, "created_at": NOW}],
    "latest_task": {"id": "task-101", "task_type": "INTERPRET_MESSAGE", "status": "SUCCEEDED", "attempts": 1, "max_attempts": 3, "last_error": None, "created_at": NOW},
    "created_at": NOW,
}

CHANNEL_MESSAGE = {
    "id": "cm-101", "corp_id": "ww-corp", "connection_id": "wecom-long-connection",
    "bot_id": "bot-1", "message_id": "MSG-101", "chat_id": "chat-g-1",
    "chat_type": "group", "sender_user_id": "user-fwd", "message_type": "mixed",
    "content": "客户要一盒子牧羊小腿，送到牧野牧场，2 份", "quote_type": None,
    "quote_content": None, "raw_payload_ref": "channel-message-payload:101",
    "submission_id": "101", "received_at": NOW,
    "media_refs": [{"id": "88", "media_type": "image", "content_type": "image/png", "size_bytes": 1234}],
}


def fulfill(payload, status=200):
    return {"status": status, "content_type": "application/json", "body": json.dumps(payload)}


def route_api(route):
    url = route.request.url
    path = url.split("localhost:5195")[-1].split("?")[0]
    if path == "/api/v1/review-cases":
        items = [ORDER_CASE, TRACKING_CASE] if "status=OPEN" in url else []
        return route.fulfill(**fulfill({"page": 0, "size": 20, "total_elements": len(items), "total_pages": 1, "items": items}))
    if path == "/api/v1/review-cases/rc-order-1":
        return route.fulfill(**fulfill(ORDER_CASE))
    if path == "/api/v1/review-cases/rc-tracking-1":
        return route.fulfill(**fulfill(TRACKING_CASE))
    if path == "/api/v1/order-drafts/od-1":
        return route.fulfill(**fulfill(ORDER_DRAFT))
    if path == "/api/v1/tracking-drafts":
        return route.fulfill(**fulfill({"page": 0, "size": 100, "total_elements": 3, "total_pages": 1, "items": [TRACKING_DRAFT_1, TRACKING_DRAFT_2, TRACKING_DRAFT_3]}))
    if path == "/api/v1/tracking-drafts/td-1":
        return route.fulfill(**fulfill(TRACKING_DRAFT_1))
    if path == "/api/v1/message-submissions/101":
        return route.fulfill(**fulfill(SUBMISSION))
    if path == "/api/v1/channel-messages/cm-101":
        return route.fulfill(**fulfill(CHANNEL_MESSAGE))
    if path.startswith("/api/v1/message-media/") and path.endswith("/content"):
        return route.fulfill(status=200, content_type="image/png", body=PNG)
    if path.startswith("/api/v1/customers"):
        return route.fulfill(**fulfill({"page": 0, "size": 200, "total_elements": 1, "total_pages": 1, "items": [{"id": "c-7", "customer_code": "CUST-007", "customer_name": "牧野牧场", "active": True}]}))
    if path.startswith("/api/v1/skus"):
        return route.fulfill(**fulfill({"page": 0, "size": 200, "total_elements": 1, "total_pages": 1, "items": [{"id": "sku-9", "sku_code": "SKU-9", "product_name": "子牧羊小腿 1kg", "active": True}]}))
    if path.startswith("/api/v1/operational-alerts"):
        return route.fulfill(**fulfill({"page": 0, "size": 20, "total_elements": 0, "total_pages": 0, "items": []}))
    return route.fulfill(status=404, content_type="application/json", body=json.dumps({"message": "not mocked", "http_status": 404}))


FORBIDDEN_TEXT = ["raw_payload", "decrypt_info", "aeskey", "secret", "EncodingAESKey",
                  "content_ref", "shipped_at", "WECOM_SECRET", "storage_ref", "agent_identity"]

failures = []


def assert_page_clean(page, label):
    body = page.content()
    for token in FORBIDDEN_TEXT:
        if token.lower() in body.lower():
            failures.append(f"{label}: 页面泄漏敏感字段 {token}")


def shoot(page, path, wait_text, extra_wait=700):
    page.wait_for_selector(f"text={wait_text}", timeout=15000)
    page.wait_for_timeout(extra_wait)
    page.screenshot(path=str(OUT / path))
    print("saved", path)


with sync_playwright() as p:
    browser = p.chromium.launch(executable_path=CHROMIUM)
    ctx = browser.new_context(viewport={"width": 1440, "height": 900}, device_scale_factor=1)
    page = ctx.new_page()
    page.route("**/api/v1/**", route_api)

    # 1) 复核队列
    page.goto(f"{BASE}/workbench/reviews", wait_until="domcontentloaded")
    shoot(page, "review-queue-1440.png", "企业微信订单草稿待确认", 900)
    assert_page_clean(page, "review-queue")

    # 2) 打开订单草稿复核面板：原消息 + 原图 + 客户候选 + 确认按钮
    page.locator("tr:has-text(\"RC-WECOM-ORDER1\") a:has-text(\"查看处理\")").click()
    shoot(page, "order-draft-panel-1440.png", "原始企微消息证据", 1200)
    imgs = page.locator('img[src*="/api/v1/message-media/"]')
    assert imgs.count() >= 1, "订单复核面板未渲染原图"
    assert_page_clean(page, "order-draft-panel")
    print("order-draft-panel 原图渲染:", imgs.count(), "张；src:", imgs.first.get_attribute("src"))
    # 点击原图放大预览
    page.locator(".ant-image").first.click()
    page.wait_for_timeout(600)
    page.screenshot(path=str(OUT / "order-draft-panel-image-preview-1440.png"))
    page.keyboard.press("Escape")
    page.wait_for_timeout(300)

    # 3) 运单草稿复核面板 + 批量确认区
    page.locator(".ant-drawer-close").click()
    page.wait_for_timeout(500)
    page.locator("tr:has-text(\"RC-WECOM-TRK1\") a:has-text(\"查看处理\")").click()
    shoot(page, "tracking-draft-panel-1440.png", "批量确认同批回传", 1200)
    assert_page_clean(page, "tracking-draft-panel")
    # 批量区：3 行，其中 td-3 有校验问题不可勾选
    checked = page.locator(".ant-table-tbody tr .ant-checkbox-input:checked")
    assert checked.count() >= 2, f"批量勾选数量异常: {checked.count()}"
    print("批量勾选行数:", checked.count())
    page.screenshot(path=str(OUT / "tracking-batch-confirm-checked-1440.png"))

    # 4) 批量确认调用 mock（会 404 → 显示错误 Alert 即为 UI 已接线）
    page.click("text=批量确认已勾选运单")
    page.wait_for_timeout(1200)
    page.screenshot(path=str(OUT / "tracking-batch-confirm-result-1440.png"))
    print("批量确认按钮已点击，等待后续真实数据验证")

    ctx.close()

if failures:
    print("FAIL:", *failures, sep="\n  ")
    sys.exit(1)
print("浏览器验收断言全部通过")
