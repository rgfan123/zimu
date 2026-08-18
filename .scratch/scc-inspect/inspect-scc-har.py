#!/usr/bin/env python3
"""
Inspect scc.freshfood.cn HAR (read-only recon).
Safety: NEVER prints header values / cookie values / raw bodies.
Only prints: header NAMES, query param NAMES, JSON key shapes, sanitized value types.
"""
import json
import re
from urllib.parse import urlparse, parse_qs
from collections import Counter, defaultdict

HAR = "/Users/jerry/Library/Containers/com.tencent.xinWeChat/Data/Documents/xwechat_files/wxid_e1jsbf77g5db12_8d29/msg/file/2026-08/scc.freshfood.cn.har"
MAX_DEPTH = 3
MAX_LIST_ITEMS = 3

# ---------- sanitizers ----------

PHONE_RE = re.compile(r"1[3-9]\d{9}")
LONG_TOKEN_RE = re.compile(r"^[A-Za-z0-9._\-]{20,}$")
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}")


def sanitize_value(v):
    """Return a placeholder type label for a value. Never returns real values."""
    if isinstance(v, bool):
        return "<boolean>"
    if isinstance(v, (int, float)):
        return "<number>"
    if isinstance(v, str):
        if not v:
            return "<empty>"
        if PHONE_RE.search(v):
            return "<phone>"
        if LONG_TOKEN_RE.match(v) and len(v) >= 20 and not DATE_RE.match(v):
            return "<token>"
        if DATE_RE.match(v):
            return "<date>"
        if re.fullmatch(r"\d+", v):
            return "<number>"
        if re.fullmatch(r"[\d\.]+", v):
            return "<decimal>"
        return "<string>"
    if isinstance(v, (list, tuple)):
        return f"<list[{len(v)}]>"
    if isinstance(v, dict):
        return f"<object[{len(v)}]>"
    if v is None:
        return "<null>"
    return f"<{type(v).__name__}>"


def shape(obj, depth=0):
    """Recursive JSON key-shape: {'key': '<type>' or nested dict}, depth-limited."""
    if depth >= MAX_DEPTH:
        return f"<object[{len(obj)}]>" if isinstance(obj, dict) else sanitize_value(obj)
    if isinstance(obj, dict):
        out = {}
        for k, v in obj.items():
            if isinstance(v, dict):
                out[str(k)] = shape(v, depth + 1)
            elif isinstance(v, list):
                if v and isinstance(v[0], (dict, list)):
                    items = [shape(i, depth + 1) for i in v[:MAX_LIST_ITEMS]]
                    out[str(k)] = {"[list]": items, "count": len(v)}
                else:
                    out[str(k)] = f"<list[{len(v)}]>{sanitize_value(v[0]) if v else ''}"
            else:
                out[str(k)] = sanitize_value(v)
        return out
    if isinstance(obj, list):
        return [shape(i, depth + 1) for i in obj[:MAX_LIST_ITEMS]] + [f"...({len(obj)} items)"]
    return sanitize_value(obj)


# ---------- load ----------

d = json.load(open(HAR))
entries = d["log"]["entries"]

# ---------- overview ----------

times = sorted(e.get("startedDateTime", "") for e in entries)
print("== OVERVIEW ==")
print(f"entries={len(entries)}")
print(f"first={times[0]}  last={times[-1]}")
hosts = Counter()
for e in entries:
    hosts[urlparse(e["request"]["url"]).hostname] += 1
for h, n in hosts.most_common():
    print(f"host {h}: {n}")

print("\n== ENDPOINTS (host+method+path, deduped) ==")


def categorize(path):
    p = path.lower()
    rules = [
        ("login/auth", ["login", "auth", "token", "sso", "captcha", "password"]),
        ("order", ["order", "sale", "purchase", "/so", "/ro"]),
        ("goods", ["goods", "sku", "product", "category"]),
        ("stock", ["stock", "inventory"]),
        ("delivery/warehouse", ["delivery", "shipment", "warehouse", "logistics", "excl", "excel"]),
        ("settlement/finance", ["settlement", "bill", "finance", "reconcil"]),
        ("org/user", ["org", "user", "dept", "role", "permission", "menu"]),
        ("task/export", ["task", "export", "download", "file", "async", "job"]),
    ]
    for cat, kws in rules:
        if any(k in p for k in kws):
            return cat
    return "other"


ep = Counter()
ep_meta = defaultdict(list)
for e in entries:
    req = e["request"]
    u = urlparse(req["url"])
    key = (u.hostname, req["method"], u.path)
    ep[key] += 1
    ep_meta[key].append(e)

for (host, method, path), n in sorted(ep.items(), key=lambda kv: -kv[1]):
    cat = categorize(path)
    statuses = Counter(str(e["response"]["status"]) for e in ep_meta[(host, method, path)])
    cts = Counter()
    for e in ep_meta[(host, method, path)]:
        for hdr in e["response"]["headers"]:
            if hdr["name"].lower() == "content-type":
                cts[hdr["value"].split(";")[0]] += 1
                break
    print(f"{n:4d}  {method:7s} {host}{path}  [cat={cat}] status={dict(statuses)} ct={dict(cts)}")

# ---------- auth headers (NAMES ONLY) ----------

print("\n== AUTH / HEADER MECHANISM (names only, no values) ==")
all_headers = Counter()
auth_hits = defaultdict(list)  # header name -> [path]
for e in entries:
    for hdr in e["request"]["headers"]:
        name = hdr["name"]
        all_headers[name] += 1
        low = name.lower()
        if low in ("authorization", "cookie", "set-cookie") or "token" in low or "auth" in low or "session" in low:
            auth_hits[name].append(urlparse(e["request"]["url"]).path)

print("request header names (all):")
for name, n in all_headers.most_common():
    marker = " <-- AUTH" if name.lower() in ("authorization", "cookie") or "token" in name.lower() or "auth" in name.lower() else ""
    print(f"  {n:4d}  {name}{marker}")

print("\nauth-ish headers present on:")
for name, paths in auth_hits.items():
    uniq = sorted(set(paths))
    print(f"  {name}: {len(paths)} requests, paths={uniq}")

# bearer scheme shape only
auth_vals = set()
for e in entries:
    for hdr in e["request"]["headers"]:
        if hdr["name"].lower() == "authorization":
            v = hdr["value"]
            scheme = v.split(" ", 1)[0] if " " in v else "<non-standard>"
            auth_vals.add(scheme)
print(f"authorization scheme(s) seen (values stripped): {sorted(auth_vals)}")

# ---------- query/pagination patterns ----------

print("\n== QUERY PARAMS PER ENDPOINT (names + sanitized values) ==")
for (host, method, path), n in sorted(ep.items(), key=lambda kv: -kv[1]):
    qnames = Counter()
    qsample = {}
    for e in ep_meta[(host, method, path)]:
        q = parse_qs(urlparse(e["request"]["url"]).query)
        for k, vs in q.items():
            qnames[k] += 1
            qsample.setdefault(k, sanitize_value(vs[0]))
    if qnames:
        print(f"{method} {path}:")
        for k in sorted(qnames):
            print(f"    {k} = {qsample[k]}  (in {qnames[k]}/{n} reqs)")

# ---------- POST bodies (names + sanitized) ----------

print("\n== POST BODIES (keys + sanitized values) ==")
for e in entries:
    req = e["request"]
    if req.get("method") != "POST":
        continue
    pd = req.get("postData", {})
    print(f"POST {urlparse(req['url']).path} mimeType={pd.get('mimeType')}")
    text = pd.get("text", "")
    if not text:
        print("    <empty body>")
        continue
    try:
        obj = json.loads(text)
        print("    json:", json.dumps(shape(obj), ensure_ascii=False))
    except Exception:
        # form-encoded: keys only
        for part in text.split("&"):
            k = part.split("=", 1)[0]
            print(f"    form key: {k}")

# ---------- response JSON shape for key endpoints ----------

print("\n== RESPONSE JSON SHAPE (key endpoints, depth<=3, values sanitized) ==")
for e in entries:
    req = e["request"]
    path = urlparse(req["url"]).path
    if req["method"] == "OPTIONS":
        continue
    resp = e["response"]
    ct = ""
    for hdr in resp["headers"]:
        if hdr["name"].lower() == "content-type":
            ct = hdr["value"]
    body = resp.get("content", {}).get("text", "")
    size = resp.get("content", {}).get("size", len(body))
    print(f"\n-- {req['method']} {path}  status={resp['status']} ct={ct.split(';')[0]} size={size}B --")
    if "json" in ct:
        try:
            obj = json.loads(body)
            print(json.dumps(shape(obj), ensure_ascii=False, indent=1)[:6000])
        except Exception as ex:
            print(f"    <json parse failed: {ex}; body starts: {body[:200]!r}>")
    elif "excel" in ct or "spreadsheet" in ct or "octet-stream" in ct:
        print(f"    <binary download, {size} bytes>")
    else:
        print(f"    <non-json body, {len(body)} chars>")

# ---------- request sequence (timeline) ----------

print("\n== TIMELINE (time, method, path, status, latency) ==")
for e in sorted(entries, key=lambda x: x.get("startedDateTime", "")):
    req = e["request"]
    u = urlparse(req["url"])
    lat = e.get("time", 0)
    print(f"{e.get('startedDateTime','')[:23]}  {req['method']:7s} {u.path}  {e['response']['status']}  {lat:.0f}ms")
