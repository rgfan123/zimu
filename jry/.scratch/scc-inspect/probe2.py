#!/usr/bin/env python3
"""Probe 2: value SHAPES only (never values). Prints format heuristics for auth
headers / query params / enums so findings.md can describe the mechanism safely."""
import json
import re
from urllib.parse import urlparse, parse_qs
from collections import Counter, defaultdict

HAR = "/Users/jerry/Library/Containers/com.tencent.xinWeChat/Data/Documents/xwechat_files/wxid_e1jsbf77g5db12_8d29/msg/file/2026-08/scc.freshfood.cn.har"
d = json.load(open(HAR))
entries = d["log"]["entries"]


def fmt(v):
    """Format heuristics: len + charset, never the value."""
    if v is None:
        return "<null>"
    if not isinstance(v, str):
        return f"<{type(v).__name__}>"
    n = len(v)
    if re.fullmatch(r"[0-9a-fA-F]{8,}", v):
        return f"<hex[{n}]>"
    if re.fullmatch(r"[A-Za-z0-9._\-]{8,}", v):
        return f"<alnum[{n}]>"
    if re.fullmatch(r"\d{1,3}(,\d{3})*", v):
        return f"<thousands-number>"
    if re.fullmatch(r"\d+", v):
        return f"<digits[{n}]>"
    if re.fullmatch(r"[\w\u4e00-\u9fff .\-/:]{4,}", v):
        return f"<text[{n}]>"
    return f"<other[{n}]>"


print("== AUTH HEADER VALUE SHAPES (non-OPTIONS only) ==")
seen = defaultdict(set)
for e in entries:
    req = e["request"]
    if req["method"] == "OPTIONS":
        continue
    p = urlparse(req["url"]).path
    for h in req["headers"]:
        low = h["name"].lower()
        if low in ("login-token", "supplier-code", "referer", "origin"):
            seen[low].add(fmt(h["value"]))
for name, shapes in seen.items():
    print(f"  {name}: {sorted(shapes)}")

print("\n== QUERY VALUE SHAPES ==")
for e in entries:
    req = e["request"]
    if req["method"] == "OPTIONS":
        continue
    p = urlparse(req["url"]).path
    q = parse_qs(urlparse(req["url"]).query)
    if q:
        print(f"  {p}:")
        for k, vs in q.items():
            print(f"    {k}: {[fmt(v) for v in vs]}")

print("\n== POST BODY SHAPES ==")
for e in entries:
    req = e["request"]
    if req["method"] != "POST":
        continue
    pd = req.get("postData", {})
    text = pd.get("text", "")
    obj = json.loads(text)
    print(f"  {urlparse(req['url']).path}:")
    for k, v in obj.items():
        print(f"    {k}: {fmt(v)}")

print("\n== task/my response: taskStatus / taskType / taskName value SHAPES over time ==")
seen_status = Counter()
seen_taskname = Counter()
seen_tasktype = Counter()
seen_resultcode = Counter()
for e in entries:
    req = e["request"]
    if req["method"] != "GET" or not req["url"].endswith("/task/task/my") and "/task/task/my?" not in req["url"]:
        continue
    resp = e["response"]
    body = resp.get("content", {}).get("text", "")
    if not body:
        continue
    try:
        obj = json.loads(body)
    except Exception:
        continue
    for t in obj.get("data", []) or []:
        seen_status[fmt(t.get("taskStatus"))] += 1
        seen_resultcode[fmt(t.get("resultCode"))] += 1
        seen_taskname[fmt(t.get("taskName"))] += 1
        seen_tasktype[fmt(t.get("taskType"))] += 1
print("  taskStatus shapes:", dict(seen_status))
print("  resultCode shapes:", dict(seen_resultcode))
print("  taskName shapes  :", dict(seen_taskname))
print("  taskType shapes  :", dict(seen_tasktype))

print("\n== taskStatus transitions (first entry per id, sanitized) ==")
# track task status by task code across polls, print transition graph only
track = {}
for e in sorted(entries, key=lambda x: x.get("startedDateTime", "")):
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
        # key on taskCode SHAPE + taskType SHAPE to avoid leaking values
        key = (fmt(t.get("taskCode")), fmt(t.get("taskType")))
        st = fmt(t.get("taskStatus"))
        track.setdefault(key, []).append(st)
for key, seq in track.items():
    # compress consecutive repeats
    comp = [seq[0]]
    for s in seq[1:]:
        if s != comp[-1]:
            comp.append(s)
    print(f"  task({key[0]},{key[1]}): {' -> '.join(comp)}  (n={len(seq)})")

print("\n== download endpoint query shapes + referer ==")
for e in entries:
    req = e["request"]
    u = urlparse(req["url"])
    if u.path == "/task/file/download":
        print("  download:", {k: [fmt(v) for v in vs] for k, vs in parse_qs(u.query).items()})

print("\n== response header names (non-OPTIONS) ==")
rn = Counter()
for e in entries:
    if e["request"]["method"] == "OPTIONS":
        continue
    for h in e["response"]["headers"]:
        rn[h["name"]] += 1
for name, n in rn.most_common():
    print(f"  {n:4d}  {name}")
