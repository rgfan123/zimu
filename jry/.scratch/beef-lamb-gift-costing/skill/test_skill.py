"""jd-fee-calc 回归断言。独立验算，不信脚本自己的输出。

默认测**与本文件同目录的那份** scripts/jdfee.py——测试必须验证它旁边的代码，
而不是某个已安装副本，否则仓库里改坏了测试照样绿。
覆盖顺序：环境变量 JDFEE > 同目录 scripts/jdfee.py > ~/.claude/skills 安装位置。
"""
import subprocess, json, math, os, sys

def _locate():
    if os.environ.get("JDFEE"):
        return os.path.abspath(os.environ["JDFEE"])
    here = os.path.dirname(os.path.abspath(__file__))
    for c in (os.path.join(here, "scripts", "jdfee.py"),
              os.path.join(here, "jdfee.py"),
              os.path.expanduser("~/.claude/skills/jd-fee-calc/scripts/jdfee.py")):
        if os.path.isfile(c):
            return c
    sys.exit("✗ 找不到 jdfee.py。用 JDFEE=<路径> 指定。")

S = _locate()
print(f"被测脚本：{S}\n")
P = F = 0
def run(args):
    r = subprocess.run(["python3", S] + args, capture_output=True, text=True)
    return r.returncode, r.stdout + r.stderr
def j(args):
    c, o = run(args + ["--json"])
    return (json.loads(o) if c == 0 else None), c, o
def ok(name, cond, detail=""):
    global P, F
    if cond: P += 1; print(f"  ✓ {name}")
    else:    F += 1; print(f"  ✗ {name}   {detail}")

# ── 独立实现模型，用来对账 ──
def ceil05(x, tol=0.005):
    q = x/0.5; n = math.floor(q)
    return n*0.5 if (q-n)*0.5 <= tol else (n+1)*0.5
def rhu(x): return math.floor(x+0.5)

print("【1】计费重量：进位 + 秤容差 + 抛重取大")
for w, v, exp, note in [
    (1.0,  None, 1.0,  "整数"),
    (1.01, None, 1.5,  "向上进位"),
    (5.003,None, 5.0,  "容差内不进位"),
    (5.019,None, 5.5,  "容差外进位"),
    (1.0,  8000, 1.0,  "抛重=实重"),
    (1.0,  16000,2.0,  "抛重>实重取抛重"),
]:
    a = ["--dest","北京朝阳区","--weight",str(w),"--items","1","--no-packing"]
    if v: a += ["--volume",str(v)]
    d,_,_ = j(a)
    ok(f"{w}kg vol={v} → {exp} ({note})", d and d["billable_weight"]==exp,
       f"得 {d and d['billable_weight']}")

print("【2】运费：四舍五入(常数+斜率×计费重量)，非银行家舍入")
d,_,_ = j(["--dest","北京朝阳区","--weight","1","--items","1","--no-packing"])
ok("北京 1kg = 12+2×1 = 14", d and d["freight_list"]==14, f"得 {d and d['freight_list']}")
# 构造 .5 结尾：福州 10+6w，w=4.25→35.5 不可得(w是0.5倍数)；用 tier 造
d,_,_ = j(["--dest","北京朝阳区","--tier","10.5,2","--weight","1","--items","1","--no-packing"])
ok("10.5+2×1=12.5 → 四舍五入应得 13（银行家舍入会得 12）",
   d and d["freight_list"]==13, f"得 {d and d['freight_list']}")

print("【3】出库操作费三条规则")
for items, sku, extra, exp, rule in [
    (1,1,[],1.2,"单品≤3"), (3,1,[],1.2,"单品=3"), (5,1,[],1.4,"单品>3"),
    (2,2,[],1.6,"多品≤3"), (8,8,[],2.6,"多品>3"),
    (8,8,["--original-pack"],4.8,"原包0.6×8"),
]:
    d,_,_ = j(["--dest","北京朝阳区","--weight","1","--items",str(items),
               "--sku-count",str(sku),"--no-packing"]+extra)
    ok(f"{items}件/{sku}SKU {rule} = {exp}", d and abs(d["out_fee"]-exp)<1e-9,
       f"得 {d and d['out_fee']}")

print("【4】分项相加 == 合计")
for a in (["--dest","北京朝阳区","--bundle","牛腩块×1,羊排块×1,羊蝎子×1"],
          ["--dest","福州市","--weight","3.8","--items","8","--sku-count","8","--storage-days","15"],
          ["--dest","上海浦东新区","--weight","2","--items","4","--sku-count","1","--box","5","--ice-frozen","3"]):
    d,_,_ = j(a)
    s = sum([d["freight"],d["out_fee"],d["in_fee"],d["adjust"],d["consumable"],d["storage"]])
    ok(f"{a[1]} 分项和={round(s,2)} == total={d['total']}", abs(s-d["total"])<0.011)

print("【5】耗材体积档边界（左闭右开）")
for gv, exp in [(2999,4.97),(3000,5.68),(5999,5.68),(6000,5.51),
                (10999,5.51),(11000,9.42),(17999,9.42),(18000,11.33)]:
    d,_,_ = j(["--dest","北京朝阳区","--weight","1","--items","1","--goods-volume",str(gv)])
    ok(f"货物体积 {gv} → 耗材 {exp}", d and abs(d["consumable"]-exp)<1e-9,
       f"得 {d and d['consumable']}")

print("【6】--volume 不被历史档案覆盖（B1 回归）")
d1,_,_ = j(["--dest","福州市","--weight","1","--volume","5000","--items","1"])
d2,_,_ = j(["--dest","福州市","--weight","1","--volume","5000","--items","1","--no-packing"])
ok("给了 --volume，两条路径 dim_w 一致且=进位(5000/8000)=1.0",
   d1["dim_w"]==d2["dim_w"]==1.0, f"得 {d1['dim_w']} / {d2['dim_w']}")

print("【7】输入校验：非法值必须拒绝，不能静默算出数")
for a, why in [
    (["--weight","-5"],"负重量"), (["--weight","1","--items","0"],"0件"),
    (["--weight","1","--freight-discount","1.5"],"折扣>1"),
    (["--weight","1","--freight-discount","-0.5"],"折扣<0"),
    (["--weight","1","--box","9"],"箱号越界"), (["--weight","1","--bag","1"],"袋号越界"),
    (["--bundle","牛腩块×0"],"bundle数量0"), (["--bundle","牛腩块×-1"],"bundle负数量"),
    (["--weight","1","--tier","abc"],"tier格式错"),
    (["--weight","1","--volume","-100"],"负体积"),
    ([],"缺重量和bundle"),
]:
    c,o = run(["--dest","北京朝阳区"]+a)
    ok(f"拒绝 {why}", c!=0 and "Traceback" not in o, f"exit={c} 崩溃={'Traceback' in o}")

print("【8】查询命令（子串匹配，不能假阴性）")
c,o = run(["--list-dest","福州"]); ok("--list-dest 福州 能查到", "福建福州市" in o)
c,o = run(["--list-dest","江苏"]); ok("--list-dest 江苏 能查到", "江苏" in o and "共 0 个" not in o)
c,o = run(["--list-goods","羊"]);  ok("--list-goods 羊 有结果", "共 0 个" not in o)
c,o = run(["--list-goods","不存在的商品"]); ok("查不到时给引导", "候选" in o or "含相同字" in o or "看全部" in o)

print("【9】目的地解析")
d,_,_ = j(["--dest","江苏","--weight","1","--items","1","--no-packing"])
ok("省名走省级估算", d and d["tier_match"]=="province" if d and "tier_match" in d else "省级估算" in str(d))
c,o = run(["--dest","市","--weight","1"]); ok("模糊命中多个 → 拒绝并列候选", c!=0 and "多个" in o)
c,o = run(["--dest","火星","--weight","1"]); ok("无匹配 → 明确报错", c!=0 and "✗" in o)

print("【10】超重告警")
d,_,_ = j(["--dest","北京朝阳区","--weight","50","--items","1","--no-packing"])
ok("50kg 触发告警", d and any("超出已验证区间" in w for w in d["warnings"]))

print("【11】标点混用")
base = None
for spec in ["羊小腿×2","羊小腿x2","羊小腿X2","羊小腿*2"]:
    d,_,_ = j(["--dest","福州市","--bundle",spec])
    if base is None: base = d["total"]
    ok(f"「{spec}」= {d['total']}", d and d["total"]==base)
d,_,_ = j(["--dest","福州市","--bundle","羊小腿×1，羊排块×1"])
ok("中文逗号分隔", d is not None)

print(f"\n{'='*46}\n  通过 {P}  失败 {F}\n{'='*46}")
sys.exit(1 if F else 0)
