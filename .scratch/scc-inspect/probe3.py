#!/usr/bin/env python3
"""Probe 3: fine-grained shapes for fields flagged as sensitive-ish, plus
CORS/config headers (safe to show), referer host, download url host."""
import json
import re
from urllib.parse import urlparse
from collections import Counter

HAR = "/Users/jerry/Library/Containers/com.tencent.xinWeChat/Data/Documents/xwechat_files/wxid_e1jsbf77g5db12_8d29/msg/file/2026-08/scc.freshfood.cn.har"
d = json.load(open(HAR))
entries = d["log"]["entries"]

# 1. taskCode / taskParam digit-run structure (no values)
print("== task record field structure ==")
def digit_runs(s):
    runs = [len(m) for m in re.findall(r"\d+", s)]
    alpha_runs = [len(m) for m in re.findall(r"[A-Za-z]+", s)]
    return f"digits={runs} alpha={alpha_runs} total={len(s)}"

seen_code = set()
seen_param = set()
seen_taskname = Counter()
seen_taskdata = set()
seen_taskattach = set()
for e in entries:
    req = e["request"]
    u = urlparse(req["url"])
    if req["method"] != "GET" or u.path != "/task/task/my":
        continue
    body = e["response"].get("content", {}).get("text", "")
    if not body:
        continue
    try:
        obj = json.loads(body)
    except Exception:
        continue
    for t in obj.get("data", []) or []:
        if isinstance(t.get("taskCode"), str):
            seen_code.add(digit_runs(t["taskCode"]))
        if isinstance(t.get("taskParam"), str):
            seen_param.add(digit_runs(t["taskParam"]))
        if isinstance(t.get("taskName"), str):
            seen_taskname[t["taskName"]] += 1
        if isinstance(t.get("taskData"), str):
            seen_taskdata.add(digit_runs(t["taskData"]))
        if isinstance(t.get("taskAttach"), str):
            seen_taskattach.add(digit_runs(t["taskAttach"]))
print("  taskCode structures :", sorted(seen_code))
print("  taskParam structures:", sorted(seen_param))
print("  taskData structures :", sorted(seen_taskdata))
print("  taskAttach structures:", sorted(seen_taskattach))
print("  taskName values (unique):", dict(seen_taskname))

# 2. taskStatus / resultCode / taskResult / processStatus raw distinct values (numbers are not sensitive)
print("\n== task numeric enums ==")
for field in ("taskStatus", "resultCode", "taskResult", "processStatus", "totalProgress", "currProgress"):
    vals = Counter()
    for e in entries:
        req = e["request"]
        u = urlparse(req["url"])
        if req["method"] != "GET" or u.path != "/task/task/my":
            continue
        body = e["response"].get("content", {}).get("text", "")
        if not body:
            continue
        try:
            obj = json.loads(body)
        except Exception:
            continue
        for t in obj.get("data", []) or []:
            v = t.get(field)
            if v is not None:
                vals[str(v)] += 1
    print(f"  {field}: {dict(vals)}")

# 3. exportDeliverExcl response + payTime field charset
print("\n== exportDeliverExcl details ==")
for e in entries:
    req = e["request"]
    if req["method"] != "POST":
        continue
    body = json.loads(req["postData"]["text"])
    for k, v in body.items():
        if isinstance(v, str):
            print(f"  {k}: len={len(v)} all_digit={v.isdigit()} has_alpha={bool(re.search('[A-Za-z]', v))} chars={sorted(set(v))[:8]}")
    resp = e["response"]["content"]["text"]
    print("  response:", resp)

# 4. download url host/path shape
print("\n== download url param shape ==")
for e in entries:
    req = e["request"]
    u = urlparse(req["url"])
    if u.path == "/task/file/download":
        from urllib.parse import parse_qs
        q = parse_qs(u.query)
        url = q.get("url", [""])[0]
        pu = urlparse(url)
        print("  name:", q.get("name"))
        print("  url scheme:", pu.scheme, "host:", pu.hostname)
        print("  url path segments:", [f"<{len(s)} chars>" for s in pu.path.split("/") if s])
        print("  url query keys:", list(parse_qs(pu.query).keys()))

# 5. CORS / config headers (values are header names/domains, safe)
print("\n== CORS & server headers ==")
for e in entries[:3]:
    for h in e["response"]["headers"]:
        if h["name"].lower() in ("access-control-allow-origin", "access-control-expose-headers", "access-control-allow-headers", "server", "via", "content-disposition"):
            print(f"  {h['name']}: {h['value'][:300]}")

# 6. referer / origin hosts
print("\n== referer/origin shape ==")
for e in entries[:2]:
    for h in e["request"]["headers"]:
        if h["name"].lower() in ("referer", "origin"):
            u = urlparse(h["value"])
            print(f"  {h['name']}: scheme={u.scheme} host={u.hostname} path_segments={len([s for s in u.path.split('/') if s])}")
