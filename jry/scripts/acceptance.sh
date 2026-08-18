#!/usr/bin/env sh
set -eu

repo_root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
project_name="${ACCEPTANCE_PROJECT:-zimu-fulfillment-acceptance}"
app_port="${ACCEPTANCE_PORT:-18088}"
reference_date="${ACCEPTANCE_REFERENCE_DATE:-2026-08-12}"
base_url="http://127.0.0.1:$app_port"
persistent_credentials_file="${ACCEPTANCE_CREDENTIAL_FILE:-${TMPDIR:-/tmp}/${project_name}-acceptance-credentials}"
credentials_file="$persistent_credentials_file"
acceptance_secret_dir=""
ephemeral_credentials_file=""
state_file=""

cleanup_acceptance_secrets() {
  if [ -n "$ephemeral_credentials_file" ]; then
    rm -f "$ephemeral_credentials_file"
  fi
  if [ -n "$state_file" ]; then
    rm -f "$state_file"
  fi
  if [ -n "$acceptance_secret_dir" ]; then
    rmdir "$acceptance_secret_dir" 2>/dev/null || true
  fi
}
trap cleanup_acceptance_secrets EXIT HUP INT TERM

acceptance_secret_dir="$(mktemp -d "${TMPDIR:-/tmp}/${project_name}-acceptance.XXXXXX")"
chmod 700 "$acceptance_secret_dir"
state_file="$acceptance_secret_dir/state.json"

compose() {
  python3 "$repo_root/scripts/acceptance_compose.py" "$credentials_file" "$project_name" "$repo_root/docker-compose.yml" "$@"
}

export APP_PORT="$app_port"
APP_BIND_ADDRESS="127.0.0.1"
export APP_BIND_ADDRESS
export DEMO_SEED_REFERENCE_DATE="$reference_date"
# Docker Desktop may route Compose builds through Bake even when its gRPC
# session is unavailable. The internal builder is deterministic for this stack.
export COMPOSE_BAKE="${COMPOSE_BAKE:-false}"

if [ -n "${METABASE_ADMIN_EMAIL:-}" ] || [ -n "${METABASE_ADMIN_PASSWORD:-}" ] \
    || [ -n "${APP_ADMIN_USER:-}" ] || [ -n "${APP_ADMIN_PASSWORD:-}" ] \
    || [ -n "${POSTGRES_USER:-}" ] || [ -n "${POSTGRES_PASSWORD:-}" ]; then
  if [ -z "${METABASE_ADMIN_EMAIL:-}" ] || [ -z "${METABASE_ADMIN_PASSWORD:-}" ]; then
    echo "set both METABASE_ADMIN_EMAIL and METABASE_ADMIN_PASSWORD, or neither" >&2
    exit 1
  fi
  if [ -z "${APP_ADMIN_USER:-}" ] || [ -z "${APP_ADMIN_PASSWORD:-}" ]; then
    echo "set both APP_ADMIN_USER and APP_ADMIN_PASSWORD, or neither" >&2
    exit 1
  fi
  if [ -z "${POSTGRES_USER:-}" ] || [ -z "${POSTGRES_PASSWORD:-}" ]; then
    echo "set both POSTGRES_USER and POSTGRES_PASSWORD, or neither" >&2
    exit 1
  fi
  python3 "$repo_root/scripts/acceptance_credentials.py" --validate-environment
  ephemeral_credentials_file="$acceptance_secret_dir/explicit.credentials"
  python3 "$repo_root/scripts/acceptance_credentials.py" --write-environment "$ephemeral_credentials_file"
  credentials_file="$ephemeral_credentials_file"
else
  python3 "$repo_root/scripts/acceptance_credentials.py" "$credentials_file"
fi
unset METABASE_ADMIN_EMAIL METABASE_ADMIN_PASSWORD APP_ADMIN_USER APP_ADMIN_PASSWORD POSTGRES_USER POSTGRES_PASSWORD

echo "[acceptance] build and start $project_name on $base_url"
if [ "${ACCEPTANCE_SKIP_BUILD:-false}" = "true" ]; then
  compose up -d --wait
else
  compose up -d --build --wait
fi

BASE_URL="$base_url" \
REFERENCE_DATE="$reference_date" \
REPO_ROOT="$repo_root" \
ACCEPTANCE_STATE_FILE="$state_file" \
ACCEPTANCE_CREDENTIAL_FILE="$credentials_file" \
python3 - <<'PY'
import datetime as dt
import hashlib
import io
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

base = os.environ["BASE_URL"]
repo_root = Path(os.environ["REPO_ROOT"])
sys.path.insert(0, str(repo_root / "scripts"))
from acceptance_credentials import load_credentials

credentials = load_credentials(Path(os.environ["ACCEPTANCE_CREDENTIAL_FILE"]))
run_id = os.environ.get("ACCEPTANCE_RUN_ID") or uuid.uuid4().hex[:12]


def request(path, *, method="GET", body=None, headers=None, attempts=1):
    payload = None if body is None else body if isinstance(body, bytes) else json.dumps(body).encode()
    request_headers = {"Accept": "application/json", **(headers or {})}
    if payload is not None and not isinstance(body, bytes):
        request_headers["Content-Type"] = "application/json"
    last_error = None
    for _ in range(attempts):
        try:
            with urllib.request.urlopen(
                urllib.request.Request(base + path, data=payload, method=method, headers=request_headers),
                timeout=20,
            ) as response:
                return response.status, response.read(), dict(response.headers)
        except urllib.error.HTTPError as error:
            return error.code, error.read(), dict(error.headers)
        except (urllib.error.URLError, TimeoutError) as error:
            last_error = error
            time.sleep(2)
    raise AssertionError(f"request failed: {path}: {last_error}")


def get_json(path):
    status, body, _ = request(path)
    assert status == 200, (path, status, body[:300])
    return json.loads(body)


def post_json(path, body, *, status=200, headers=None):
    actual, raw, response_headers = request(path, method="POST", body=body, headers=headers)
    assert actual == status, (path, actual, raw[:500])
    return json.loads(raw), response_headers


def multipart(path, fields, filename, content, *, content_type, headers=None, status=201):
    boundary = "----zimu-acceptance-" + uuid.uuid4().hex
    chunks = []
    for name, value in fields.items():
        chunks.extend([
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode(),
            str(value).encode(),
            b"\r\n",
        ])
    chunks.extend([
        f"--{boundary}\r\n".encode(),
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode(),
        f"Content-Type: {content_type}\r\n\r\n".encode(),
        content,
        b"\r\n",
        f"--{boundary}--\r\n".encode(),
    ])
    actual, raw, response_headers = request(
        path,
        method="POST",
        body=b"".join(chunks),
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}", **(headers or {})},
    )
    assert actual == status, (path, actual, raw[:500])
    return json.loads(raw), response_headers


def error_json(path, *, method="POST", body=None, headers=None, status):
    actual, raw, _ = request(path, method=method, body=body, headers=headers)
    assert actual == status, (path, actual, raw[:500])
    return json.loads(raw)


def write_headers(key):
    return {
        "Authorization": "Bearer browser-forged-credential",
        "Idempotency-Key": key,
        "X-Operator": "forged-browser-operator",
    }


def feixiang_csv(order_ref, customer_ref, sku_ref, quantity="1.500"):
    header = ",".join([
        "订单号", "会员名称", "商品名称", "商品ID", "订单商品ID", "可发货数量",
        "收货人姓名", "收货人手机号", "收货人地址", "下单时间", "物流状态", "物流公司", "物流单号",
    ])
    row = ",".join([
        order_ref, customer_ref, "子牧羊小腿", sku_ref, order_ref + "-LINE", quantity,
        "验收用户", "13900000000", "上海市验收路1号", "2026-08-12 10:00:00", "", "", "",
    ])
    return ("\ufeff" + header + "\r\n" + row + "\r\n").encode()


def minimal_source_xlsx(sheet_name, headers, cells):
    """Create a small real XLSX for public fingerprint/lineage checks using standard-library OOXML."""
    def xml_text(value):
        return (str(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace('"', "&quot;").replace("'", "&apos;"))

    def column(index):
        result = ""
        while index:
            index, remainder = divmod(index - 1, 26)
            result = chr(65 + remainder) + result
        return result

    rows = []
    for row_index, values in enumerate((headers, [cells.get(header, "") for header in headers]), 1):
        rendered = "".join(
            f'<c r="{column(column_index)}{row_index}" t="inlineStr"><is><t>{xml_text(value)}</t></is></c>'
            for column_index, value in enumerate(values, 1)
        )
        rows.append(f'<row r="{row_index}">{rendered}</row>')
    sheet_xml = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        f'<sheetData>{"".join(rows)}</sheetData></worksheet>'
    )
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", zipfile.ZIP_DEFLATED) as workbook:
        workbook.writestr("[Content_Types].xml", '<?xml version="1.0" encoding="UTF-8"?>'
            '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
            '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
            '<Default Extension="xml" ContentType="application/xml"/>'
            '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
            '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
            '</Types>')
        workbook.writestr("_rels/.rels", '<?xml version="1.0" encoding="UTF-8"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
            '</Relationships>')
        workbook.writestr("xl/workbook.xml", '<?xml version="1.0" encoding="UTF-8"?>'
            '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
            'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
            f'<sheets><sheet name="{xml_text(sheet_name)}" sheetId="1" r:id="rId1"/></sheets></workbook>')
        workbook.writestr("xl/_rels/workbook.xml.rels", '<?xml version="1.0" encoding="UTF-8"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>'
            '</Relationships>')
        workbook.writestr("xl/worksheets/sheet1.xml", sheet_xml)
    return stream.getvalue()


def tracking_workbook(instruction, result, quantity, tracking_number):
    """Fill only mutable S:W cells in the generated XLSX using OOXML, without a test-only Excel dependency."""
    namespace = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    q = lambda name: f"{{{namespace}}}{name}"
    output = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(instruction), "r") as source, zipfile.ZipFile(output, "w") as target:
        for info in source.infolist():
            payload = source.read(info.filename)
            if info.filename == "xl/worksheets/sheet1.xml":
                root = ET.fromstring(payload)
                cells = {cell.attrib.get("r"): cell for cell in root.iter(q("c"))}
                values = {
                    "S2": result,
                    "T2": quantity,
                    "U2": "京东物流",
                    "V2": tracking_number,
                    "W2": "2026-08-12 12:00:00",
                }
                for reference, value in values.items():
                    cell = cells[reference]
                    for child in list(cell):
                        cell.remove(child)
                    cell.set("t", "inlineStr")
                    inline = ET.SubElement(cell, q("is"))
                    ET.SubElement(inline, q("t")).text = value
                payload = ET.tostring(root, encoding="utf-8", xml_declaration=True)
            target.writestr(info, payload)
    return output.getvalue()


for passwordless_path in (
    "/api/v1/orders?page=0&size=1",
    "/api/v1/channel-messages?page=0&size=1",
    "/dashboard",
):
    status, _, _ = request(passwordless_path)
    assert status == 200, (passwordless_path, status)

health = get_json("/actuator/health")
assert health["status"] == "UP", health

today = dt.date.fromisoformat(os.environ["REFERENCE_DATE"])
date_from = today - dt.timedelta(days=29)
dashboard = get_json(f"/api/v1/dashboard/summary?business_date={today}")
assert dashboard["business_date"] == str(today), dashboard
assert dashboard["order_count"] >= 7, dashboard
assert len(dashboard["trend"]) == 7, dashboard

channels = get_json(f"/api/v1/analytics/channels?date_from={date_from}&date_to={today}")
assert len(channels) == 120, len(channels)
assert {row["source_channel"] for row in channels} == {"CAISHIXIAN", "JUFUBAO", "FEIXIANG", "WECOM"}
assert len({row["metric_date"] for row in channels}) == 30

products = get_json(f"/api/v1/analytics/products?date_from={date_from}&date_to={today}")
assert products, "product analytics is empty"
fulfillments = get_json(f"/api/v1/analytics/fulfillments?date_from={date_from}&date_to={today}")
assert fulfillments, "fulfillment analytics is empty"
assert sum(row["procurement_ticket_count"] for row in fulfillments) > 0
assert sum(row["sync_failed_count"] for row in fulfillments) > 0

for status in ("OUT_OF_STOCK", "PROCUREMENT_PENDING", "FULFILLMENT_EXCEPTION", "SYNC_FAILED"):
    page = get_json(f"/api/v1/orders?order_status={status}&page=0&size=1")
    assert page["total_elements"] > 0, status

fresh = {
    "SEED-FRESH-RECEIVED": "RECEIVED",
    "SEED-FRESH-PROCUREMENT": "PROCUREMENT_PENDING",
    "SEED-FRESH-EXCEPTION": "FULFILLMENT_EXCEPTION",
}
for source_ref, expected_status in fresh.items():
    page = get_json("/api/v1/orders?" + urllib.parse.urlencode({"query": source_ref, "page": 0, "size": 20}))
    assert len(page["items"]) == 1, page
    assert page["items"][0]["order_status"] == expected_status, page["items"][0]

reviews = get_json("/api/v1/review-cases?status=OPEN&page=0&size=200")
assert reviews["total_elements"] > 0, reviews

# Channel evidence is a separate, authenticated workbench seam. Fresh acceptance has no real
# WeCom callback, so its honest initial state is an empty page rather than fabricated messages.
channel_messages = get_json("/api/v1/channel-messages?page=0&size=20")
assert channel_messages["total_elements"] == 0 and channel_messages["items"] == [], channel_messages

# Non-blocking operations: acknowledge a BUSINESS alert through the public seam.
alerts = get_json("/api/v1/operational-alerts?status=OPEN&page=0&size=200")
assert alerts["total_elements"] > 0, alerts
alert = alerts["items"][0]
alert_before_order = get_json(f'/api/v1/orders/{alert["order_id"]}') if alert.get("order_id") else None
acknowledged, _ = post_json(
    f'/api/v1/operational-alerts/{alert["id"]}/acknowledge',
    {"expected_version": alert["version"], "note": "本地端到端验收确认"},
    headers=write_headers(f"acceptance-alert-{run_id}"),
)
assert acknowledged["status"] == "ACKNOWLEDGED", acknowledged
assert acknowledged["acknowledged_by"] == credentials["APP_ADMIN_USER"], acknowledged
if alert_before_order is not None:
    alert_after_order = get_json(f'/api/v1/orders/{alert["order_id"]}')
    assert alert_after_order["order_status"] == alert_before_order["order_status"], (
        alert_before_order,
        alert_after_order,
    )
stale_alert = error_json(
    f'/api/v1/operational-alerts/{alert["id"]}/acknowledge',
    body={"expected_version": alert["version"], "note": "过期版本应被拒绝"},
    headers=write_headers(f"acceptance-alert-stale-{run_id}"),
    status=409,
)
assert stale_alert["business_code"] in {"VERSION_CONFLICT", "ALERT_NOT_OPEN"}, stale_alert
alert_audits = get_json(
    "/api/v1/audit-logs?" + urllib.parse.urlencode({
        "operation": "operational_alert.acknowledge", "operator": credentials["APP_ADMIN_USER"],
        "page": 0, "size": 20,
    })
)
assert alert_audits["total_elements"] >= 1, alert_audits
assert all(item["operator"] != "forged-browser-operator" for item in alert_audits["items"]), alert_audits

# Local procurement write seam: cancel one pending seeded ticket, then prove audit/operator and version gate.
pending_tickets = get_json("/api/v1/procurement-tickets?status=PENDING&page=0&size=200")
assert pending_tickets["total_elements"] > 0, pending_tickets
ticket = pending_tickets["items"][0]
cancelled, _ = post_json(
    f'/api/v1/procurement-tickets/{ticket["id"]}/cancel-remaining',
    {"expected_version": ticket["version"], "reason": "本地端到端验收取消剩余量"},
    headers=write_headers(f"acceptance-procurement-{run_id}"),
)
assert cancelled["status"] == "CANCELLED", cancelled
stale_ticket = error_json(
    f'/api/v1/procurement-tickets/{ticket["id"]}/cancel-remaining',
    body={"expected_version": ticket["version"], "reason": "过期版本应被拒绝"},
    headers=write_headers(f"acceptance-procurement-stale-{run_id}"),
    status=409,
)
assert stale_ticket["business_code"] in {"VERSION_CONFLICT", "PROCUREMENT_TERMINAL"}, stale_ticket
procurement_audits = get_json(
    "/api/v1/audit-logs?" + urllib.parse.urlencode({
        "operation": "procurement.cancel_remaining", "operator": credentials["APP_ADMIN_USER"],
        "page": 0, "size": 20,
    })
)
assert procurement_audits["total_elements"] >= 1, procurement_audits

# Source adapters: exercise real XLSX/CSV containers for every external channel and prove retained row lineage.
source_samples = {
    "CAISHIXIAN": (
        "caishixian.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        minimal_source_xlsx(
            "待发货",
            ["主订单编号", "子订单编号", "供应商编码", "站点编码", "商品编号", "下单数量", "商品名称", "收货人", "联系电话", "省", "市", "区", "详细地址"],
            {"主订单编号": f"E2E-CSX-{run_id}", "子订单编号": f"E2E-CSX-SUB-{run_id}", "供应商编码": "E2E-SUPPLIER", "站点编码": f"E2E-CSX-CUSTOMER-{run_id}", "商品编号": f"E2E-CSX-SKU-{run_id}", "下单数量": "1", "商品名称": "子牧羊小腿", "收货人": "验收用户", "联系电话": "13900000000", "省": "上海市", "市": "上海市", "区": "浦东新区", "详细地址": "验收路1号"},
        ),
    ),
    "JUFUBAO": (
        "jufubao.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        minimal_source_xlsx(
            "sheet1",
            ["主单号", "拆单号", "供货商", "渠道订单号", "结算方式", "需结算总额", "商品ID", "商品名称", "数量", "收货人姓名", "收货人电话", "收货地址"],
            {"主单号": f"E2E-JFB-{run_id}", "拆单号": f"E2E-JFB-SUB-{run_id}", "供货商": "E2E-SUPPLIER", "渠道订单号": f"E2E-JFB-CUSTOMER-{run_id}", "结算方式": "月结", "需结算总额": "100", "商品ID": f"E2E-JFB-SKU-{run_id}", "商品名称": "子牧羊小腿", "数量": "1", "收货人姓名": "验收用户", "收货人电话": "13900000000", "收货地址": "上海市验收路1号"},
        ),
    ),
    "FEIXIANG": (
        "feixiang.csv",
        "text/csv",
        feixiang_csv(f"E2E-FX-LINEAGE-{run_id}", f"E2E-FX-CUSTOMER-{run_id}", f"E2E-FX-SKU-{run_id}", "1.000"),
    ),
}
for expected_channel, (filename, content_type, sample) in source_samples.items():
    imported, _ = multipart(
        "/api/v1/import-batches/source-orders",
        {"import_mode": "NEW"},
        filename,
        sample,
        content_type=content_type,
        headers=write_headers(f"acceptance-lineage-{expected_channel.lower()}-{run_id}"),
    )
    assert imported["source_channel"] == expected_channel, imported
    assert imported["content_sha256"] and len(imported["content_sha256"]) == 64, imported
    rows = get_json(f'/api/v1/import-batches/{imported["id"]}/rows?page=0&size=20')
    assert rows["total_elements"] == 1 and len(rows["items"]) == 1, rows
    lineage = rows["items"][0]
    assert lineage["sheet_name"] and lineage["row_index"] == 2 and lineage["raw_cells"], lineage
    assert lineage["status"] == "NEED_REVIEW", lineage

# Human review + Excel/multi-package closure. Start with unknown mapping evidence; resolve it using
# existing active BUSINESS customer/SKU facts, then import a second mapped order for the file loop.
review_order_ref = f"E2E-FX-REVIEW-{run_id}"
review_customer_ref = f"E2E-FX-CUSTOMER-REVIEW-{run_id}"
review_sku_ref = f"E2E-FX-SKU-REVIEW-{run_id}"
review_import, _ = multipart(
    "/api/v1/import-batches/source-orders",
    {"import_mode": "NEW"},
    "review-order.csv",
    feixiang_csv(review_order_ref, review_customer_ref, review_sku_ref),
    content_type="text/csv",
    headers=write_headers(f"acceptance-review-import-{run_id}"),
)
review_order_page = get_json(
    "/api/v1/orders?" + urllib.parse.urlencode({"query": review_order_ref, "page": 0, "size": 20})
)
assert review_order_page["total_elements"] == 1, review_order_page
review_order_id = review_order_page["items"][0]["id"]
review_order = get_json(f"/api/v1/orders/{review_order_id}")
# B1: 公共导入路径按收货人姓名+手机号自动建档（ImportedCustomerService）并回写 customer_id，
# 不再产生 CUSTOMER_MATCH_REQUIRED；未映射 SKU 仍进入 SKU_MAPPING_REQUIRED（ExcelClosedLoopApiTest 锁定）。
assert review_order.get("customer_id") is not None, review_order
open_review_cases = [item for item in review_order["review_cases"] if item["status"] == "OPEN"]
assert {item["reason_code"] for item in open_review_cases} == {"SKU_MAPPING_REQUIRED"}, open_review_cases
third_party_provider = next(
    item for item in get_json("/api/v1/fulfillment-providers") if item["provider_type"] == "THIRD_PARTY"
)
third_party_skus = get_json("/api/v1/skus?page=0&size=20")
sku = next(
    item for item in third_party_skus["items"]
    if item["attributes"].get("provider_id") == third_party_provider["id"]
)
sku_case = next(item for item in open_review_cases if item["reason_code"] == "SKU_MAPPING_REQUIRED")
resolved_sku, _ = post_json(
    f'/api/v1/review-cases/{sku_case["id"]}/resolve-sku',
    {"expected_version": sku_case["version"], "sku_id": sku["id"], "source_channel": "FEIXIANG", "source_sku_ref": review_sku_ref, "quantity_multiplier": "2.000", "remark": "依验收主数据显式确认"},
    headers=write_headers(f"acceptance-review-sku-{run_id}"),
)
resolved_sku_replay, _ = post_json(
    f'/api/v1/review-cases/{sku_case["id"]}/resolve-sku',
    {"expected_version": sku_case["version"], "sku_id": sku["id"], "source_channel": "FEIXIANG", "source_sku_ref": review_sku_ref, "quantity_multiplier": "2.000", "remark": "依验收主数据显式确认"},
    headers=write_headers(f"acceptance-review-sku-{run_id}"),
)
assert resolved_sku["status"] == "RESOLVED" and resolved_sku["resolved_by"] == credentials["APP_ADMIN_USER"], resolved_sku
assert resolved_sku_replay == resolved_sku, (resolved_sku, resolved_sku_replay)
resolved_order = get_json(f"/api/v1/orders/{review_order_id}")
assert all(item["status"] == "RESOLVED" for item in resolved_order["review_cases"]), resolved_order
# B2: 复核关闭只把批次恢复到可导出（SKU_MAPPED / READY_TO_EXPORT），履约文件在批次级 confirm 时生成。
assert resolved_order["order_status"] == "SKU_MAPPED", resolved_order
assert {item["processing_stage"] for item in resolved_order["lines"]} == {"READY_TO_EXPORT"}, resolved_order
resolved_batch = get_json(f'/api/v1/import-batches/{review_import["id"]}')
assert resolved_batch["row_counts"] == {"total": 1, "accepted": 1, "need_review": 0, "rejected": 0}, resolved_batch
assert resolved_batch["generated_fulfillment_export_ids"] == [], resolved_batch
assert resolved_batch["confirmed_at"] is None, resolved_batch
review_confirmed, _ = post_json(
    f'/api/v1/import-batches/{review_import["id"]}/confirm',
    {},
    headers=write_headers(f"acceptance-review-confirm-{run_id}"),
)
review_confirmed_replay, _ = post_json(
    f'/api/v1/import-batches/{review_import["id"]}/confirm',
    {},
    headers=write_headers(f"acceptance-review-confirm-{run_id}"),
)
assert review_confirmed_replay == review_confirmed, (review_confirmed, review_confirmed_replay)
assert review_confirmed["confirmed_at"] is not None, review_confirmed
assert review_confirmed["status"] == "COMPLETED", review_confirmed
assert review_confirmed["row_counts"] == {"total": 1, "accepted": 1, "need_review": 0, "rejected": 0}, review_confirmed
assert len(review_confirmed["generated_fulfillment_export_ids"]) == 1, review_confirmed
confirmed_order = get_json(f"/api/v1/orders/{review_order_id}")
assert confirmed_order["order_status"] == "FULFILLING", confirmed_order
assert {item["processing_stage"] for item in confirmed_order["lines"]} == {"WAITING_PROVIDER"}, confirmed_order
stale_review = error_json(
    f'/api/v1/review-cases/{sku_case["id"]}/resolve-sku',
    body={"expected_version": sku_case["version"], "sku_id": sku["id"], "source_channel": "FEIXIANG", "source_sku_ref": review_sku_ref, "quantity_multiplier": "2.000", "remark": "过期处理"},
    headers=write_headers(f"acceptance-review-stale-{run_id}"),
    status=409,
)
assert stale_review["business_code"] in {"VERSION_CONFLICT", "REVIEW_CASE_NOT_OPEN"}, stale_review

file_order_ref = f"E2E-FX-MULTI-{run_id}"
file_import, _ = multipart(
    "/api/v1/import-batches/source-orders",
    {"import_mode": "NEW"},
    "multi-package.csv",
    feixiang_csv(file_order_ref, review_customer_ref, review_sku_ref),
    content_type="text/csv",
    headers=write_headers(f"acceptance-file-import-{run_id}"),
)
assert file_import["row_counts"] == {"total": 1, "accepted": 1, "need_review": 0, "rejected": 0}, file_import
assert file_import["generated_fulfillment_export_ids"] == [], file_import
assert file_import["confirmed_at"] is None, file_import
file_confirmed, _ = post_json(
    f'/api/v1/import-batches/{file_import["id"]}/confirm',
    {},
    headers=write_headers(f"acceptance-file-confirm-{run_id}"),
)
assert len(file_confirmed["generated_fulfillment_export_ids"]) == 1, file_confirmed
first_export_id = file_confirmed["generated_fulfillment_export_ids"][0]
status, first_instruction, first_headers = request(f"/api/v1/fulfillment-exports/{first_export_id}/file")
assert status == 200 and first_instruction[:2] == b"PK", (status, first_headers)
state_payload = json.dumps({
    "fulfillment_export_id": first_export_id,
    "file_sha256": hashlib.sha256(first_instruction).hexdigest(),
}).encode()
state_flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
if hasattr(os, "O_NOFOLLOW"):
    state_flags |= os.O_NOFOLLOW
descriptor = os.open(os.environ["ACCEPTANCE_STATE_FILE"], state_flags, 0o600)
try:
    os.fchmod(descriptor, 0o600)
    offset = 0
    while offset < len(state_payload):
        offset += os.write(descriptor, state_payload[offset:])
    os.fsync(descriptor)
finally:
    os.close(descriptor)
partial_tracking = tracking_workbook(first_instruction, "PARTIAL", "1.000", f"JDVA-E2E-FIRST-{run_id}")
first_tracking, _ = multipart(
    f"/api/v1/fulfillment-exports/{first_export_id}/tracking-imports",
    {"import_mode": "NEW"},
    "tracking-partial.xlsx",
    partial_tracking,
    content_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    headers=write_headers(f"acceptance-tracking-first-{run_id}"),
)
assert first_tracking["business_results"] == {"shipped": 0, "partial": 1, "failed": 0}, first_tracking
# B3: 首批 PARTIAL 立即开 MULTI_SHIPMENT_SOURCE_FOLLOWUP，回填文件在 followup 未关闭时不产出。
assert first_tracking["generated_source_return_export_ids"] == [], first_tracking
first_tracking_replay, _ = multipart(
    f"/api/v1/fulfillment-exports/{first_export_id}/tracking-imports",
    {"import_mode": "NEW"},
    "tracking-partial-replay.xlsx",
    partial_tracking,
    content_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    headers=write_headers(f"acceptance-tracking-first-replay-{run_id}"),
)
assert first_tracking_replay == first_tracking, (first_tracking, first_tracking_replay)
file_order_page = get_json(
    "/api/v1/orders?" + urllib.parse.urlencode({"query": file_order_ref, "page": 0, "size": 20})
)
file_order_id = file_order_page["items"][0]["id"]
after_partial = get_json(f"/api/v1/orders/{file_order_id}")
followup_case = next(item for item in after_partial["review_cases"] if item["reason_code"] == "MULTI_SHIPMENT_SOURCE_FOLLOWUP")
assert followup_case["status"] == "OPEN", followup_case
fulfillment_id = get_json(f'/api/v1/orders/{file_order_id}/shipments')[0]["items"][0]["fulfillment_id"]
partial_fulfillment = get_json(f"/api/v1/fulfillments/{fulfillment_id}")
assert partial_fulfillment["shipping_progress"] == "PARTIALLY_SHIPPED", partial_fulfillment
continuation, _ = post_json(
    f"/api/v1/fulfillments/{fulfillment_id}/continuation-exports",
    {"expected_version": partial_fulfillment["version"], "instructed_quantity": "2.000", "remark": "首批部分发货后本地验收续发"},
    status=201,
    headers=write_headers(f"acceptance-continuation-{run_id}"),
)
assert continuation["shipment_sequence"] == 2, continuation
continuation_replay, _ = post_json(
    f"/api/v1/fulfillments/{fulfillment_id}/continuation-exports",
    {"expected_version": partial_fulfillment["version"], "instructed_quantity": "2.000", "remark": "首批部分发货后本地验收续发"},
    status=201,
    headers=write_headers(f"acceptance-continuation-{run_id}"),
)
assert continuation_replay == continuation, (continuation, continuation_replay)
second_export_id = continuation["fulfillment_export_id"]
status, second_instruction, _ = request(f"/api/v1/fulfillment-exports/{second_export_id}/file")
assert status == 200 and second_instruction[:2] == b"PK", status
second_tracking_workbook = tracking_workbook(
    second_instruction, "SHIPPED", "2.000", f"JDVA-E2E-SECOND-{run_id}"
)
second_tracking, _ = multipart(
    f"/api/v1/fulfillment-exports/{second_export_id}/tracking-imports",
    {"import_mode": "NEW"},
    "tracking-second.xlsx",
    second_tracking_workbook,
    content_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    headers=write_headers(f"acceptance-tracking-second-{run_id}"),
)
assert second_tracking["business_results"] == {"shipped": 1, "partial": 0, "failed": 0}, second_tracking
assert second_tracking["generated_source_return_export_ids"] == [], second_tracking
second_tracking_replay, _ = multipart(
    f"/api/v1/fulfillment-exports/{second_export_id}/tracking-imports",
    {"import_mode": "NEW"},
    "tracking-second-replay.xlsx",
    second_tracking_workbook,
    content_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    headers=write_headers(f"acceptance-tracking-second-replay-{run_id}"),
)
assert second_tracking_replay == second_tracking, (second_tracking, second_tracking_replay)
shipments = get_json(f"/api/v1/orders/{file_order_id}/shipments")
assert [item["shipment_sequence"] for item in shipments] == [1, 2], shipments
assert all(item.get("tracking") for item in shipments), shipments
# B3: 多 Shipment 来源回填延后——followup 未关闭不产出回填文件；人工完成 followup 后订单直接 CLOSED，
# 当前流程不自动补生成回填文件（JD 回填路径除外）。ExcelClosedLoopApiTest 锁定该语义。
source_returns = get_json(f'/api/v1/import-batches/{file_import["id"]}/source-return-exports')
assert source_returns == [], source_returns
terminal_order = get_json(f"/api/v1/orders/{file_order_id}")
terminal_followup = next(item for item in terminal_order["review_cases"] if item["reason_code"] == "MULTI_SHIPMENT_SOURCE_FOLLOWUP")
assert terminal_order["order_status"] == "NEED_REVIEW", terminal_order
completed_followup, _ = post_json(
    f'/api/v1/review-cases/{terminal_followup["id"]}/complete-source-followup',
    {"expected_version": terminal_followup["version"], "note": "已在来源平台人工补充第二批运单"},
    headers=write_headers(f"acceptance-followup-complete-{run_id}"),
)
assert completed_followup["status"] == "RESOLVED", completed_followup
closed_order = get_json(f"/api/v1/orders/{file_order_id}")
assert closed_order["order_status"] == "CLOSED", closed_order
timeline = get_json(f"/api/v1/orders/{file_order_id}/timeline")
assert any(event["event_type_code"] == "MANUAL_SOURCE_FOLLOWUP_COMPLETED" for event in timeline), timeline
versions = get_json(f"/api/v1/orders/{file_order_id}/versions")
assert len(versions) >= 2, versions

# 单 Shipment 全额回传：回填文件在回传时立即按来源原格式产出（is_final），含运单号。
single_order_ref = f"E2E-FX-SINGLE-{run_id}"
single_import, _ = multipart(
    "/api/v1/import-batches/source-orders",
    {"import_mode": "NEW"},
    "single-return.csv",
    feixiang_csv(single_order_ref, review_customer_ref, review_sku_ref),
    content_type="text/csv",
    headers=write_headers(f"acceptance-single-import-{run_id}"),
)
assert single_import["row_counts"] == {"total": 1, "accepted": 1, "need_review": 0, "rejected": 0}, single_import
single_confirmed, _ = post_json(
    f'/api/v1/import-batches/{single_import["id"]}/confirm',
    {},
    headers=write_headers(f"acceptance-single-confirm-{run_id}"),
)
assert len(single_confirmed["generated_fulfillment_export_ids"]) == 1, single_confirmed
single_export_id = single_confirmed["generated_fulfillment_export_ids"][0]
status, single_instruction, _ = request(f"/api/v1/fulfillment-exports/{single_export_id}/file")
assert status == 200 and single_instruction[:2] == b"PK", status
single_tracking_no = f"JDVA-E2E-SINGLE-{run_id}"
single_returned = tracking_workbook(single_instruction, "SHIPPED", "1.500", single_tracking_no)
single_tracking, _ = multipart(
    f"/api/v1/fulfillment-exports/{single_export_id}/tracking-imports",
    {"import_mode": "NEW"},
    "tracking-single.xlsx",
    single_returned,
    content_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    headers=write_headers(f"acceptance-tracking-single-{run_id}"),
)
assert single_tracking["business_results"] == {"shipped": 1, "partial": 0, "failed": 0}, single_tracking
assert len(single_tracking["generated_source_return_export_ids"]) == 1, single_tracking
single_order_page = get_json(
    "/api/v1/orders?" + urllib.parse.urlencode({"query": single_order_ref, "page": 0, "size": 20})
)
assert single_order_page["total_elements"] == 1, single_order_page
single_order_id = single_order_page["items"][0]["id"]
single_shipments = get_json(f"/api/v1/orders/{single_order_id}/shipments")
assert len(single_shipments) == 1 and single_shipments[0].get("tracking"), single_shipments
single_returns = get_json(f'/api/v1/import-batches/{single_import["id"]}/source-return-exports')
assert len(single_returns) == 1 and single_returns[0]["is_final"], single_returns
status, single_csv, _ = request(f'/api/v1/source-return-exports/{single_returns[0]["id"]}/file')
single_text = single_csv.decode("utf-8-sig")
assert status == 200 and not single_text.startswith("PK"), (status, single_text[:120])
assert single_tracking_no in single_text and "已发货" in single_text and "京东物流" in single_text, single_text[:500]

file_audits = get_json(
    "/api/v1/audit-logs?" + urllib.parse.urlencode({
        "operator": credentials["APP_ADMIN_USER"], "page": 0, "size": 200,
    })
)
required_operations = {
    "review_case.resolve_sku", "source-orders.confirm", "continuation_export.create",
    "review_case.complete_source_followup", "file.upload", "source-orders.upload",
}
actual_operations = {item["operation"] for item in file_audits["items"]}
assert required_operations <= actual_operations, (required_operations - actual_operations, actual_operations)

jd_status = get_json("/api/v1/jd-warehouse/status")
assert jd_status == {
    "client_mode": "MOCK",
    "credentials_configured": False,
    "tenant_configured": False,
    "live_ready": False,
}, jd_status
jd_owners = get_json("/api/v1/jd-warehouse/owners")
assert jd_owners["success"] and jd_owners["business_code"] == "MOCK_SUCCESS", jd_owners
assert jd_owners["data"]["operation"] == "queryOwners", jd_owners
assert jd_owners["data"]["response"]["owners"][0]["owner_no"] == "MOCK-OWNER-001", jd_owners
jd_warehouses = get_json("/api/v1/jd-warehouse/warehouses")
assert jd_warehouses["success"] and jd_warehouses["business_code"] == "MOCK_SUCCESS", jd_warehouses
jd_outbound = get_json("/api/v1/jd-warehouse/outbound-orders/ZM-ACCEPTANCE-0001")
assert jd_outbound["success"] and jd_outbound["business_code"] == "MOCK_SUCCESS", jd_outbound
jd_tracking = get_json("/api/v1/jd-warehouse/tracking?waybill_no=MOCK-TRACK-001")
assert jd_tracking["success"] and jd_tracking["business_code"] == "MOCK_SUCCESS", jd_tracking
audits = get_json("/api/v1/audit-logs?operation=seed.demo-dataset&page=0&size=20")
assert audits["total_elements"] == 1, audits

# Public AI-demo command gate: reuse a publicly visible WECOM BUSINESS source reference, then prove
# the confirmed DEMO order coexists under the same channel/reference without entering BUSINESS reads.
business_same_ref_page = get_json(
    "/api/v1/orders?" + urllib.parse.urlencode({"source_channel": "WECOM", "page": 0, "size": 1})
)
assert business_same_ref_page["total_elements"] > 0 and len(business_same_ref_page["items"]) == 1, business_same_ref_page
business_same_ref = business_same_ref_page["items"][0]
demo_source_ref = business_same_ref["source_ref"]
demo_draft = {
    "confirmed": False,
    "source": "WECOM",
    "source_ref": demo_source_ref,
    "customer": {"customer_name": "端到端演示客户", "customer_code": "E2E-DEMO"},
    "receiver": {"receiver_name": "演示收货人", "receiver_phone": "13900000000", "address": "上海市演示路1号"},
    "required_delivery_time": "2026-08-13T10:00:00+08:00",
    "items": [
        {"product_name": "子牧羊小腿", "sku_code": "DEMO-SKU-1", "specification": "500g/盒", "quantity": "2.000", "unit": "盒"},
        {"product_name": "子牧羊小腿", "sku_code": "DEMO-SKU-2", "specification": "标准箱", "quantity": "1.000", "unit": "箱"},
    ],
    "settlement": {"settlement_method": "MONTHLY", "settlement_time": "2026-08-12T10:00:00+08:00"},
    "remark": "本地端到端验收",
}
unconfirmed = error_json(
    "/demo/v1/extracted-orders",
    body=demo_draft,
    headers=write_headers(f'acceptance-ai-unconfirmed-{business_same_ref["id"]}'),
    status=400,
)
assert unconfirmed["business_code"] == "VALIDATION_ERROR", unconfirmed
demo_draft["confirmed"] = True
extracted_run, _ = post_json(
    "/demo/v1/extracted-orders",
    demo_draft,
    status=201,
    headers=write_headers(f'acceptance-ai-confirmed-{business_same_ref["id"]}'),
)
assert extracted_run["data_scope"] == "DEMO" and extracted_run["status"] == "SUCCEEDED", extracted_run
assert extracted_run["order"]["order_status"] == "SYNCED", extracted_run
assert len(extracted_run["order"]["lines"]) == 2, extracted_run
assert extracted_run["timeline"][-1]["event_type_code"] == "SOURCE_SYNCED", extracted_run
assert {event["operator"] for event in extracted_run["timeline"]} == {"local-operator"}, extracted_run
extracted_business = get_json(
    "/api/v1/orders?" + urllib.parse.urlencode({"query": demo_source_ref, "page": 0, "size": 20})
)
assert extracted_business["total_elements"] == 1, extracted_business
assert extracted_business["items"][0]["id"] == business_same_ref["id"], (extracted_business, business_same_ref)
assert extracted_run["order_id"] != business_same_ref["id"], (extracted_run, business_same_ref)
assert get_json(f'/demo/v1/runs/{extracted_run["id"]}')["data_scope"] == "DEMO"
demo_default_audit = get_json(
    "/api/v1/audit-logs?" + urllib.parse.urlencode({"service": "demo", "operation": "demo.run", "page": 0, "size": 20})
)
assert demo_default_audit["total_elements"] == 0, demo_default_audit

# Inspect every public audit detail for the known receiver/raw-row originals used above. The list seam
# alone intentionally omits payloads.
pii_audits = get_json(
    "/api/v1/audit-logs?" + urllib.parse.urlencode({
        "operator": credentials["APP_ADMIN_USER"], "page": 0, "size": 200,
    })
)
sensitive_values = {
    "验收用户", "13900000000", "上海市验收路1号",
}
for summary in pii_audits["items"]:
    detail = get_json(f'/api/v1/audit-logs/{summary["id"]}')
    rendered = json.dumps(detail, ensure_ascii=False)
    leaked = {value for value in sensitive_values if value in rendered}
    assert not leaked, (summary["id"], summary["operation"], leaked)

status, scenarios_body, _ = request("/demo/v1/scenarios")
assert status == 200
scenario = json.loads(scenarios_body)[0]
status, run_body, _ = request(
    "/demo/v1/scenarios",
    method="POST",
    body={"scenario_code": scenario["scenario_code"]},
    headers=write_headers(f"acceptance-demo-run-{run_id}"),
)
assert status == 201, (status, run_body)
run = json.loads(run_body)
assert run["data_scope"] == "DEMO"
assert run["status"] == "SUCCEEDED"
assert run["order"]["order_status"] == "SYNCED"
assert [event["event_type_code"] for event in run["timeline"]] == [
    "ORDER_RECEIVED",
    "SKU_MAPPED",
    "JD_STOCK_CHECKED",
    "JD_OUTBOUND_SUBMITTED",
    "JD_OUTBOUND_ACCEPTED",
    "JD_SHIPPED",
    "SHIPMENT_CREATED",
    "TRACKING_RECEIVED",
    "SOURCE_SYNCED",
]
assert {event["operator"] for event in run["timeline"]} == {"local-operator"}, run["timeline"]
business_lookup = get_json(
    "/api/v1/orders?" + urllib.parse.urlencode({"query": run["order"]["source_ref"], "page": 0, "size": 20})
)
assert business_lookup["total_elements"] == 0, business_lookup

for route in (
    "/dashboard",
    "/orders",
    "/orders/pending",
    "/orders/exceptions",
    "/orders/tracking",
    "/workbench/reviews",
    "/workbench/channel-messages",
    "/product/products",
    "/product/categories",
    "/product/skus",
    "/product/sku-mappings",
    "/fulfillment/tasks",
    "/fulfillment/jd-warehouse",
    "/fulfillment/sales-outbound",
    "/fulfillment/shipments",
    "/procurement/tickets",
    "/analytics",
    "/system/connectors",
    "/system/audit-logs",
    "/system/config",
    "/demo/order",
):
    status, body, _ = request(route)
    assert status == 200 and b"<div id=\"root\"></div>" in body, route

status, metabase_html, _ = request("/metabase/")
assert status == 200 and b'<base href="/metabase/"' in metabase_html, status
metabase_assets = sorted(set(re.findall(rb'(?:src|href)="(app/(?:dist|assets)/[^"?]+)', metabase_html)))
assert metabase_assets, "Metabase page did not expose any static assets"
for asset in metabase_assets:
    asset_path = "/metabase/" + asset.decode()
    status, _, _ = request(asset_path, method="HEAD")
    assert status == 200, (asset_path, status)

metabase_health = get_json("/metabase/api/health")
assert metabase_health["status"] == "ok", metabase_health
status, session_body, _ = request(
    "/metabase/api/session",
    method="POST",
    body={
        "username": credentials["METABASE_ADMIN_EMAIL"],
        "password": credentials["METABASE_ADMIN_PASSWORD"],
    },
)
assert status == 200, session_body
session_id = json.loads(session_body)["id"]
status, dashboards_body, _ = request(
    "/metabase/api/dashboard", headers={"X-Metabase-Session": session_id}, attempts=20
)
assert status == 200, dashboards_body
dashboards = {
    item["name"]: item["id"] for item in json.loads(dashboards_body) if not item.get("archived")
}
expected_metabase_content = {
    "履约总览": "30 天履约总览",
    "渠道分析": "30 天渠道分析",
    "商品分析": "30 天商品分析",
}
assert expected_metabase_content.keys() <= dashboards.keys(), dashboards.keys()
for dashboard_name, card_name in expected_metabase_content.items():
    status, detail_body, _ = request(
        f"/metabase/api/dashboard/{dashboards[dashboard_name]}",
        headers={"X-Metabase-Session": session_id},
    )
    assert status == 200, (dashboard_name, detail_body)
    dashcards = json.loads(detail_body)["dashcards"]
    matching_cards = [entry["card"] for entry in dashcards if entry.get("card", {}).get("name") == card_name]
    assert len(matching_cards) == 1, (dashboard_name, dashcards)
    status, query_body, _ = request(
        f"/metabase/api/card/{matching_cards[0]['id']}/query",
        method="POST",
        body={"parameters": []},
        headers={"X-Metabase-Session": session_id},
    )
    assert status == 202, (card_name, status, query_body)
    query_result = json.loads(query_body)
    assert not query_result.get("error") and query_result["data"]["rows"], (card_name, query_result)

orders = get_json("/api/v1/orders?page=0&size=1")
print(orders["total_elements"])
PY

before_restart="$(curl --fail --silent "$base_url/api/v1/orders?page=0&size=1" | python3 -c 'import json,sys; print(json.load(sys.stdin)["total_elements"])')"
echo "[acceptance] restart backend to verify empty-only/idempotent seed"
compose restart backend >/dev/null
attempts=0
until curl --fail --silent "$base_url/actuator/health" | grep -q '"status":"UP"'; do
  attempts=$((attempts + 1))
  if [ "$attempts" -ge 60 ]; then
    echo "backend did not recover after restart" >&2
    exit 1
  fi
  sleep 2
done
after_restart="$(curl --fail --silent "$base_url/api/v1/orders?page=0&size=1" | python3 -c 'import json,sys; print(json.load(sys.stdin)["total_elements"])')"
test "$before_restart" = "$after_restart"

echo "[acceptance] force-recreate backend to verify generated-file durability"
compose up -d --force-recreate --wait backend >/dev/null
BASE_URL="$base_url" \
ACCEPTANCE_STATE_FILE="$state_file" \
python3 - <<'PY'
import hashlib
import json
import os
import stat
import time
import urllib.error
import urllib.request

state_flags = os.O_RDONLY
if hasattr(os, "O_NOFOLLOW"):
    state_flags |= os.O_NOFOLLOW
descriptor = os.open(os.environ["ACCEPTANCE_STATE_FILE"], state_flags)
try:
    metadata = os.fstat(descriptor)
    if not stat.S_ISREG(metadata.st_mode) or stat.S_IMODE(metadata.st_mode) != 0o600:
        raise PermissionError("acceptance state must be a regular 0600 file")
    with os.fdopen(descriptor, encoding="utf-8", closefd=False) as state_stream:
        state = json.load(state_stream)
finally:
    os.close(descriptor)
url = os.environ["BASE_URL"] + "/api/v1/fulfillment-exports/" + state["fulfillment_export_id"] + "/file"
last_error = None
for _ in range(30):
    try:
        with urllib.request.urlopen(
            urllib.request.Request(url), timeout=20
        ) as response:
            content = response.read()
            assert response.status == 200 and content[:2] == b"PK", response.status
            assert hashlib.sha256(content).hexdigest() == state["file_sha256"]
            break
    except (urllib.error.URLError, TimeoutError, AssertionError) as error:
        last_error = error
        time.sleep(2)
else:
    raise AssertionError(f"generated file was not durable across backend recreation: {last_error}")
PY

echo "[acceptance] PASS: public HTTP seams, Excel multi-package closure, Demo isolation, Metabase dashboards, restart idempotency, file durability"
echo "[acceptance] stack remains available at $base_url (project: $project_name)"
