#!/usr/bin/env python3
"""京东仓配额外费用计算器（运费 + 仓配 + 耗材）。

费率全部取自真实结算账单的【结算金额】= 合同价，不是标价。
校验：运费 735/738、出库费 753/753、耗材 2139/2139、存储费 4416/4416 精确命中。

常用：
  jdfee.py --dest 北京朝阳区 --bundle "牛腩块×1,羊排块×1,羊蝎子×1"
  jdfee.py --dest 福州市 --weight 3.8 --volume 32625 --items 8
  jdfee.py --list-goods 羊        # 查商品档案
  jdfee.py --list-dest 江苏       # 查目的地档位
"""
import argparse, json, math, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
DB = json.load(open(os.path.join(HERE, "rates.json"), encoding="utf-8"))
M = DB["_meta"]

DIM_DIVISOR  = M["dim_divisor"]
ROUND_KG     = M["round_kg"]
WEIGHT_TOL   = M["weight_tol"]
MAX_VERIFIED = M["freight_max_verified_kg"]

FEE_INBOUND_PER_ITEM = 0.03
FEE_STORAGE_M3_DAY   = 4.0
FEE_WAREHOUSE_ADJUST = 0.2
OUT_ORIGINAL = 0.6
OUT_MULTI    = (1.6, 0.2)
OUT_SINGLE   = (1.2, 0.1)

BOX = {1: (0.9, 3.39), 2: (1.7, 6.72), 3: (2.1, 9.00),
       4: (2.9, 15.63), 5: (4.5, 21.11), 6: (5.5, 32.63)}
BAG = {2: 0.5, 3: 0.6, 4: 0.7, 5: 0.8, 6: 1.1}
ICE = {"chill": 0.2, "frozen": 0.3, "dry": 0.9}


def ceil_to(x, step=ROUND_KG, tol=WEIGHT_TOL):
    q = x / step
    n = math.floor(q)
    return n * step if (q - n) * step <= tol else (n + 1) * step


def round_half_up(x):
    return math.floor(x + 0.5)


def find_goods(name):
    cat = DB["catalog"]
    if name in cat:
        return name, cat[name]
    hits = [k for k in cat if name in k or k in name]
    if len(hits) == 1:
        return hits[0], cat[hits[0]]
    return (None, hits) if hits else (None, None)


def find_dest(dest):
    city = DB["city"]
    if not dest:
        return None, None, "miss", None
    if dest in city:
        t = city[dest]
        return t["b"], t["s"], "city", None
    # 省名精确匹配优先于城市模糊匹配，否则「江苏」会被判成命中多个而走不到省级兜底
    if dest in DB["province"]:
        t = DB["province"][dest]
        n = f"按 {dest} 省内多数城市的档位估算"
        if t.get("mixed"):
            n += "；⚠ 该省不同市分属不同档位，可能偏一档，建议传具体城市"
        return t["b"], t["s"], "province", n
    hits = [k for k in city if dest in k or k in dest]
    if len(hits) == 1:
        t = city[hits[0]]
        return t["b"], t["s"], "city", f"按「{hits[0]}」匹配"
    if len(hits) > 1:
        return None, None, "ambiguous", "命中多个城市，无法确定档位：" + "、".join(hits[:8])
    for p, t in DB["province"].items():
        if dest.startswith(p):
            n = f"该市无账单记录，按 {p} 省内多数档位估算"
            if t.get("mixed"):
                n += "；⚠ 该省不同市分属不同档位，可能偏一档"
            return t["b"], t["s"], "province", n
    return None, None, "miss", None


def parse_bundle(spec):
    """'牛腩块×1,羊排块×2' -> [(名, 件数)]。数量非法直接抛错，不静默吞掉。"""
    out = []
    for part in re.split(r"[,，]", spec):
        part = part.strip()
        if not part:
            continue
        if re.search(r"[×xX*]", part):
            m = re.match(r"^(.+?)\s*[×xX*]\s*(-?\d+)\s*$", part)
            if not m:
                raise ValueError(f"「{part}」数量格式不对，应为 商品×正整数")
            q = int(m.group(2))
            if q < 1:
                raise ValueError(f"「{part}」数量必须 ≥1")
            out.append((m.group(1).strip(), q))
        else:
            out.append((part, 1))
    if not out:
        raise ValueError("礼包组成为空")
    return out


def packing_profile(goods_volume):
    """按【货物体积】查历史装箱档案。

    装箱由京东决定，不是商家可选项，所以不做「选最小够用的箱」这种推算，
    而是查 753 单历史实际用箱与耗材成本。用体积而非件数分档：体积对耗材成本的
    解释力更强（R²=0.379 vs 0.340），且体积分档单调、件数分档在 3-4 件处倒挂。
    """
    if goods_volume is None:
        return "_all", DB["packing"]["_all"]
    for k, v in DB["packing"].items():
        if k.startswith("_"):
            continue
        if v["lo"] <= goods_volume and (v["hi"] is None or goods_volume < v["hi"]):
            return k, v
    return "_all", DB["packing"]["_all"]


def calc(b, s, net_kg, volume=None, items=1, sku_count=None, original_pack=False,
         box=None, bag=None, ice_frozen=0, ice_chill=0, dry_ice=0,
         storage_days=0.0, freight_discount=0.0, inbound=True,
         consumable_flat=None):
    notes, warns = [], []
    rw = ceil_to(net_kg)
    dw = ceil_to(volume / DIM_DIVISOR) if volume else None
    bw = max(rw, dw) if dw else rw

    if dw and dw > rw:
        notes.append(f"按体积重计费（体积重 {dw}kg > 实重 {rw}kg）——运费由包裹体积主导，不是净重")
    if bw > MAX_VERIFIED:
        warns.append(f"计费重量 {bw}kg 超出已验证区间（≤{MAX_VERIFIED}kg）；"
                     f"账单中 >40kg 实付比公式高约 12%，此结果可能低估")

    freight_list = round_half_up(b + s * bw)
    freight = freight_list * (1 - freight_discount)

    if original_pack:
        out_fee, rule = items * OUT_ORIGINAL, "原包-是·仅贴单"
    else:
        multi = (sku_count or items) > 1
        first, extra = OUT_MULTI if multi else OUT_SINGLE
        rule = "原包-否·多品" if multi else "原包-否·单品"
        out_fee = first + max(0, items - 3) * extra

    in_fee = items * FEE_INBOUND_PER_ITEM if inbound else 0.0

    consum, det = 0.0, []
    if consumable_flat is not None and not (box or bag or ice_frozen or ice_chill or dry_ice):
        consum = consumable_flat
        det.append("历史同档均值")
    else:
        if box:
            if box not in BOX:
                raise ValueError(f"泡沫箱号 {box} 不存在，只支持 {min(BOX)}-{max(BOX)}")
            consum += BOX[box][0]; det.append(f"{box}号泡沫箱 {BOX[box][0]}")
        if bag:
            if bag not in BAG:
                raise ValueError(f"保温袋号 {bag} 不存在，只支持 {min(BAG)}-{max(BAG)}")
            consum += BAG[bag]; det.append(f"{bag}号保温袋 {BAG[bag]}")
        for n, key, lbl in ((ice_frozen, "frozen", "冷冻冰袋"),
                            (ice_chill, "chill", "冷藏冰袋"),
                            (dry_ice, "dry", "干冰")):
            if n:
                consum += n * ICE[key]; det.append(f"{lbl}×{n} {n*ICE[key]:.1f}")

    storage = 0.0
    if storage_days:
        if volume:
            storage = volume / 1e6 * FEE_STORAGE_M3_DAY * storage_days
        else:
            warns.append("算存储费需要体积，已跳过")

    total = freight + out_fee + in_fee + FEE_WAREHOUSE_ADJUST + consum + storage
    return dict(billable_weight=bw, real_w=rw, dim_w=dw,
                freight_list=freight_list, freight=round(freight, 2),
                freight_discount=freight_discount,
                out_fee=round(out_fee, 2), out_rule=rule,
                in_fee=round(in_fee, 2), adjust=FEE_WAREHOUSE_ADJUST,
                consumable=round(consum, 2), consumable_detail=det,
                storage=round(storage, 2), total=round(total, 2),
                notes=notes, warnings=warns)


def render(r, dest, tier, items, bundle_lines=None):
    W = 54
    print("─" * W)
    print(f"  目的地 {dest}    运费档位 {tier}")
    if bundle_lines:
        print("  礼包组成：")
        for l in bundle_lines:
            print(f"    {l}")
    dim = f" / 体积重 {r['dim_w']}kg" if r["dim_w"] else ""
    print(f"  实重进位 {r['real_w']}kg{dim}  →  计费重量 {r['billable_weight']}kg   共 {items} 件")
    print("─" * W)
    for k, v in (("运费", r["freight"]), ("出库操作费", r["out_fee"]),
                 ("耗材", r["consumable"]), ("入库验收费", r["in_fee"]),
                 ("仓库资源调节费", r["adjust"]), ("存储费", r["storage"])):
        if not v:
            continue
        tail = ""
        if k == "运费":
            tail = (f"   合同价{r['freight_list']}×折{1-r['freight_discount']:.0%}"
                    if r["freight_discount"] else "   合同价，未打折")
        elif k == "出库操作费":
            tail = f"   {r['out_rule']}"
        print(f"  {k:<16}{v:>9.2f}{tail}")
    if r["consumable_detail"]:
        print(f"      └ {' + '.join(r['consumable_detail'])}")
    print("─" * W)
    print(f"  {'额外费用合计':<16}{r['total']:>9.2f} 元")
    print("─" * W)
    for n in r["notes"]:
        print(f"  · {n}")
    for w in r["warnings"]:
        print(f"  ⚠ {w}")
    if not r["storage"]:
        print("  · 未含存储费（4元/方/天）；加 --storage-days N 摊入")
    return 0


def main():
    ap = argparse.ArgumentParser(
        description="京东仓配额外费用计算（合同价口径）",
        formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    ap.add_argument("--dest", help="目的地，如 北京朝阳区 / 福州市 / 福建")
    ap.add_argument("--bundle", help='礼包组成，如 "牛腩块×1,羊排块×1"（自动查体积重量）')
    ap.add_argument("--weight", type=float, help="总实重 kg")
    ap.add_argument("--volume", type=float,
                    help="【包裹体积】cm³——已知京东实际用的箱子多大时给。给了就直接用来算抛重，不会被历史档案覆盖")
    ap.add_argument("--goods-volume", type=float,
                    help="【货物体积】cm³——净货物体积，用于查耗材档位。--bundle 会自动算，手工模式下给它更准")
    ap.add_argument("--items", type=int, help="总件数")
    ap.add_argument("--sku-count", type=int, help="SKU 种类数，决定单品/多品")
    ap.add_argument("--original-pack", action="store_true", help="原包发货（仅贴单 0.6元/件）")
    ap.add_argument("--box", type=int, help="泡沫箱号 1-6（已知实际箱号时覆盖历史均值）")
    ap.add_argument("--bag", type=int, help="保温袋号 2-6（默认同箱号）")
    ap.add_argument("--ice-frozen", type=int, default=0, help="冷冻冰袋250g 数量")
    ap.add_argument("--ice-chill", type=int, default=0, help="冷藏冰袋250g 数量")
    ap.add_argument("--dry-ice", type=int, default=0, help="干冰500g 数量")
    ap.add_argument("--no-packing", action="store_true",
                    help="不用历史装箱档案（耗材记 0，自己用 --box/--ice-* 指定）")
    ap.add_argument("--storage-days", type=float, default=0, help="摊存储费的周转天数")
    ap.add_argument("--freight-discount", type=float, default=0.0,
                    help="运费促销折扣率 0~1，默认0=合同价（历史：5月.30 6月.40 7月.20）")
    ap.add_argument("--no-inbound", action="store_true", help="不计入库验收费")
    ap.add_argument("--tier", help="手工指定运费档位「常数,斜率」")
    ap.add_argument("--json", action="store_true", help="输出 JSON")
    ap.add_argument("--list-dest", nargs="?", const="")
    ap.add_argument("--list-goods", nargs="?", const="")
    a = ap.parse_args()

    if a.list_dest is not None:
        ks = sorted(k for k in DB["city"] if k.startswith(a.list_dest))
        for k in ks:
            t = DB["city"][k]
            print(f"  {k:<16} {t['b']} + {t['s']}×重量")
        print(f"\n  共 {len(ks)} 个城市有账单记录")
        if not a.list_dest:
            print("  省级估算：" + "、".join(sorted(DB["province"])))
        return 0

    if a.list_goods is not None:
        n = 0
        for k, v in sorted(DB["catalog"].items()):
            if a.list_goods in k:
                flag = "  ⚠档案可疑" if v.get("suspect") else ""
                print(f"  {k:<20} {v['vol']:>7.0f}cm³ {v['kg']:>5.2f}kg{flag}")
                n += 1
        print(f"\n  共 {n} 个商品")
        return 0

    if not a.dest:
        ap.print_help()
        return 2

    # ── 入参校验：宁可报错，也不要静默算出一个看着正常的错价 ──
    if a.weight is not None and a.weight <= 0:
        print("✗ --weight 必须 >0"); return 2
    if a.items is not None and a.items < 1:
        print("✗ --items 必须 ≥1（0 件不是有效订单）"); return 2
    if a.sku_count is not None and a.sku_count < 1:
        print("✗ --sku-count 必须 ≥1"); return 2
    if not 0 <= a.freight_discount < 1:
        print(f"✗ --freight-discount 必须在 [0,1) 区间，收到 {a.freight_discount}"
              "（0=合同价不打折；历史值 5月0.30 / 6月0.40 / 7月0.20）"); return 2
    if a.storage_days < 0:
        print("✗ --storage-days 不能为负"); return 2
    for lbl, v in (("--volume", a.volume), ("--goods-volume", a.goods_volume)):
        if v is not None and v <= 0:
            print(f"✗ {lbl} 必须 >0"); return 2
    for lbl, v in (("--ice-frozen", a.ice_frozen), ("--ice-chill", a.ice_chill),
                   ("--dry-ice", a.dry_ice)):
        if v < 0:
            print(f"✗ {lbl} 不能为负"); return 2
    if a.box is not None and a.box not in BOX:
        print(f"✗ --box 只支持 {min(BOX)}-{max(BOX)}，收到 {a.box}"); return 2
    if a.bag is not None and a.bag not in BAG:
        print(f"✗ --bag 只支持 {min(BAG)}-{max(BAG)}，收到 {a.bag}"); return 2

    if a.tier:
        try:
            b, s = [float(x) for x in a.tier.split(",")]
        except ValueError:
            print("✗ --tier 格式：常数,斜率")
            return 2
        how, dnote, tier_txt = "manual", "手工指定档位", f"{b} + {s}×重量（手工）"
    else:
        b, s, how, dnote = find_dest(a.dest)
        if b is None:
            print(f"✗ 目的地「{a.dest}」无匹配。{dnote or ''}")
            print("  用 --list-dest 查可用目的地，或 --tier 常数,斜率 手工指定")
            return 1
        tier_txt = f"{b} + {s}×重量" + ("  [省级估算]" if how == "province" else "")

    lines, weight, items, skus = None, a.weight, a.items, a.sku_count
    bundle_volume = None
    if a.bundle:
        w = v = it = 0
        lines, miss = [], []
        try:
            parsed = parse_bundle(a.bundle)
        except ValueError as e:
            print(f"✗ {e}"); return 2
        for name, qty in parsed:
            key, g = find_goods(name)
            if key is None:
                miss.append((name, g))
                continue
            w += g["kg"] * qty
            v += g["vol"] * qty
            it += qty
            flag = " ⚠档案可疑" if g.get("suspect") else ""
            lines.append(f"{key}×{qty}  {g['kg']*qty:.2f}kg {g['vol']*qty:.0f}cm³{flag}")
        if miss:
            for name, hits in miss:
                extra = f"，可能是：{'、'.join(hits[:6])}" if hits else ""
                print(f"✗ 商品「{name}」未找到{extra}")
            print("  用 --list-goods 查商品档案")
            return 1
        weight = w if weight is None else weight
        bundle_volume = v
        items = it if items is None else items
        skus = len(parsed) if skus is None else skus

    if weight is None:
        print("✗ 需要 --bundle 或 --weight")
        return 2
    if items is None:
        items = 1

    manual_pack = any([a.box, a.bag, a.ice_frozen, a.ice_chill, a.dry_ice])
    prof_key = prof = None
    flat = None
    # 货物体积（查耗材档用）：--goods-volume > bundle 汇总 > --volume 兜底
    goods_volume = a.goods_volume if a.goods_volume is not None else bundle_volume
    if goods_volume is None:
        goods_volume = a.volume
    # 包裹体积（算抛重用）：--volume 显式给了就用它，否则查历史典型箱容积
    parcel_volume = a.volume
    if not manual_pack and not a.no_packing:
        prof_key, prof = packing_profile(goods_volume)
        flat = prof["cost"]
        if parcel_volume is None and prof.get("box_vol"):
            parcel_volume = prof["box_vol"]
    volume = parcel_volume

    try:
        r = calc(b, s, weight, volume, items, skus, a.original_pack, a.box, a.bag,
                 a.ice_frozen, a.ice_chill, a.dry_ice, a.storage_days,
                 a.freight_discount, not a.no_inbound, consumable_flat=flat)
    except ValueError as e:
        print(f"✗ {e}"); return 2
    if dnote:
        r["notes"].insert(0, dnote)
    if prof:
        if prof.get("box_vol"):
            src = "包裹体积按 --volume 指定值" if a.volume is not None else \
                  f"包裹体积按该档典型箱容积 {prof['box_vol']}cm³"
            r["notes"].append(
                f"货物体积 {goods_volume:.0f}cm³ 落在「{prof_key}」档（{src}）："
                f"耗材取历史均值 {prof['cost']}元（中位 {prof['cost_med']}，"
                f"档内标准差 ±{prof['cost_sd']}，{prof['n']}单）；"
                f"该档 {prof['box_share']:.0%} 用 {prof['box']}号箱")
        else:
            r["notes"].append(f"无体积信息，耗材取全样本均值 {prof['cost']}元（{prof['n']}单）")
        r["notes"].append("装箱由京东决定，非商家可选——耗材与箱型均为历史估算，"
                          "已知实际箱号可用 --box 覆盖")

    if a.json:
        r.update(dest=a.dest, tier=tier_txt, items=items, bundle=lines)
        print(json.dumps(r, ensure_ascii=False, indent=1))
        return 0
    return render(r, a.dest, tier_txt, items, lines)


if __name__ == "__main__":
    sys.exit(main())
