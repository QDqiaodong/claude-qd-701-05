#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
模块八验收示例数据的计算底稿（非运行时代码）：
用 Decimal 严格按 WbCalculator 口径（kg 6 位 / mm 3 位 HALF_UP）算出
称重净重、总重、总力矩、重心臂、包线插值边界与越界差值，供 schema.sql 种子核对。

运行：python3 tools/wb_seed_calc.py
"""
from decimal import Decimal as D, ROUND_HALF_UP, getcontext

getcontext().prec = 40

LB = D("0.45359237")
INCH = D("25.4")


def q6(x):
    return x.quantize(D("0.000001"), rounding=ROUND_HALF_UP)


def q3(x):
    return x.quantize(D("0.001"), rounding=ROUND_HALF_UP)


def kg(lb):
    return q6(D(str(lb)) * LB)


def mm(inch):
    return q3(D(str(inch)) * INCH)


def interp(nodes, w):
    w = D(str(w))
    for (w1, a1), (w2, a2) in zip(nodes, nodes[1:]):
        w1, a1, w2, a2 = D(w1), D(a1), D(w2), D(a2)
        if w == w1:
            return q3(a1)
        if w1 < w < w2:
            ratio = q6((w - w1) / (w2 - w1))
            return q3(a1 + (a2 - a1) * ratio)
    if w == D(nodes[-1][0]):
        return q3(D(nodes[-1][1]))
    return None


def weigh(title, arms, readings, corrections, front, aft, lo, hi, unit="KG"):
    gw = D(0)
    gm = D(0)
    print(f"== {title}")
    for code, gross, tare in readings:
        net = q6(kg(gross) - kg(tare)) if unit == "LB" else q6(D(gross) - D(tare))
        arm = arms[code]
        gw += net
        gm += q3(net * arm)
        print(f"  {code}: gross={gross} tare={tare} -> net={net} kg arm={arm} mm moment={q3(net*arm)}")
    fw, fm = q6(gw), q3(gm)
    for name, wraw, arm, sign in corrections:
        w = kg(wraw) if unit == "LB" else q6(D(wraw))
        fw = q6(fw + sign * w)
        fm = q3(fm + sign * q3(w * arm))
        print(f"  corr {name}: unit={wraw} -> {w} kg arm={arm} {'+' if sign>0 else '-'}")
    cg = q3(fm / fw)
    f = interp(front, fw)
    a = interp(aft, fw)
    print(f"  grossW={q6(gw)} kg grossM={q3(gm)} | finalW={fw} finalM={fm} CG={cg}")
    print(f"  limits[{lo},{hi}] front={f} aft={a}")
    if fw < D(lo):
        status = f"OUTSIDE UNDER_WEIGHT delta={q6(D(lo)-fw)}"
    elif fw > D(hi):
        status = f"OUTSIDE OVER_WEIGHT delta={q6(fw-D(hi))}"
    elif f and cg < f:
        status = f"OUTSIDE FORWARD delta={q3(f-cg)}"
    elif a and cg > a:
        status = f"OUTSIDE AFT delta={q3(cg-a)}"
    else:
        status = "INSIDE" if f and a else "UNDETERMINED"
    print(f"  => {status}\n")
    return fw, fm, cg


def derive_step(tag, parent, action, unit_w, arm, qty, front, aft, lo, hi):
    w0, m0, _ = parent
    sign = -1 if action == "REMOVE" else 1
    total = q6(D(unit_w) * qty)
    w = q6(w0 + sign * total)
    m = q3(m0 + sign * q3(total * D(arm)))
    cg = q3(m / w)
    f, a = interp(front, w), interp(aft, w)
    if w > D(hi):
        status = f"OUTSIDE OVER_WEIGHT delta={q6(w-D(hi))}"
    elif f and cg < f:
        status = f"OUTSIDE FORWARD delta={q3(f-cg)}"
    elif a and cg > a:
        status = f"OUTSIDE AFT delta={q3(cg-a)}"
    elif f and a:
        status = "INSIDE"
    else:
        status = "UNDETERMINED"
    print(f"  {tag}: {action} {qty}x{unit_w}kg@{arm} => w={w} m={m} cg={cg} "
          f"front={f} aft={a} => {status}")
    return w, m, cg


# ============ B737 R0（旧版，2025-01-01 生效；最大 46000） ============
r0_arms = {"NOSE": D("1600.000"), "LEFT": D("9000.000"), "RIGHT": D("9000.000")}
r0_front = [("40000", "7000"), ("46000", "7600")]
r0_aft = [("40000", "7800"), ("46000", "8000")]
# B-9002 历史基线（2025-03-10 称重，引用 R0，当时 INSIDE，R1 发布后结果冻结）
b9002 = weigh("B737 R0 B-9002 historical baseline (frozen, INSIDE under R0)",
              r0_arms,
              [("NOSE", "7900", "0"), ("LEFT", "17375", "0"), ("RIGHT", "17375", "0")],
              [("燃油", "800", D("8200"), -1), ("滑油", "50", D("7800"), -1)],
              r0_front, r0_aft, "40000", "46000")

# ============ B737 R1（2025-06-01 生效，最大放大到 48000；节点间插值演示） ============
r1_arms = {"NOSE": D("1600.000"), "LEFT": D("9000.000"), "RIGHT": D("9000.000")}
r1_front = [("40000", "7000"), ("46000", "7600"), ("48000", "7650")]
r1_aft = [("40000", "7800"), ("46000", "8000"), ("48000", "8050")]

# B-6032 公制基线：最终 43800 kg，CG 7800，居中于插值包线（节点之间）
b6032 = weigh("B737 R1 B-6032 metric baseline (43800 between nodes)",
              r1_arms,
              [("NOSE", "7200", "0"), ("LEFT", "18725", "0"), ("RIGHT", "18725", "0")],
              [("燃油", "800", D("8200"), -1), ("滑油", "50", D("7800"), -1)],
              r1_front, r1_aft, "40000", "48000")

print("--- B-6032 config chain: v0 INSIDE -> v1 AFT out -> v2 FORWARD out -> v3 back INSIDE ---")
# v1：后货舱加装 3000kg@11800（重量仍在范围内，重心穿过后边界）
v1 = derive_step("v1", b6032, "INSTALL", "3000", "11800", 1, r1_front, r1_aft, "40000", "48000")
# v2：拆除后部 3000 + 前电子舱加装 4000@1600（一次派生可含多条调整；向前穿过前边界）
w2, m2, cg2 = q6(v1[0] - D("3000")), q3(v1[1] - q3(D("3000") * D("11800"))), None
w2, m2 = q6(w2 + D("4000")), q3(m2 + q3(D("4000") * D("1600")))
cg2 = q3(m2 / w2)
f2, a2 = interp(r1_front, w2), interp(r1_aft, w2)
print(f"  v2: REMOVE 3000@11800 + INSTALL 4000@1600 => w={w2} m={m2} cg={cg2} "
      f"front={f2} aft={a2} => OUTSIDE FORWARD delta={q3(f2-cg2)}")
v2 = (w2, m2, cg2)
# v3：拆除前电子舱配重，回到 v0 同一重量/力矩/判定
v3 = derive_step("v3", v2, "REMOVE", "4000", "1600", 1, r1_front, r1_aft, "40000", "48000")

# 偏前越界单项验证（脚本备查）
print("--- forward-out single step scratch ---")
derive_step("fwd1", b6032, "INSTALL", "4000", "1600", 1, r1_front, r1_aft, "40000", "48000")

# B-6688 英制称重：臂长英寸录入，读数磅录入（与 B-6032 同一物理量级，INSIDE）
print("inch arm check: 62.992126in ->", mm("62.992126"), "; 354.330709in ->", mm("354.330709"))
# 选读数 lb：让 nose≈7200kg, main each≈18725kg 的磅值
nose_lb = D("7200") / LB
main_lb = D("18725") / LB
fuel_lb = D("800") / LB
oil_lb = D("50") / LB
print("lb raw choices: nose", q6(nose_lb), "main", q6(main_lb), "fuel", q6(fuel_lb), "oil", q6(oil_lb))
b6688 = weigh("B737 R1 B-6688 imperial weighing (lb/in, INSIDE)",
              {"NOSE": mm("62.992126"), "LEFT": mm("354.330709"), "RIGHT": mm("354.330709")},
              [("NOSE", "15873", "0"), ("LEFT", "41281", "0"), ("RIGHT", "41281", "0")],
              [("燃油", "1764", mm("322.834646"), -1), ("滑油", "110", mm("307.086614"), -1)],
              r1_front, r1_aft, "40000", "48000", unit="LB")
print("roundtrip: 15873lb ->", kg("15873"), "kg; back ->", q6(kg("15873")/LB), "lb")
print("roundtrip: 41281lb ->", kg("41281"), "kg; back ->", q6(kg("41281")/LB), "lb")

# B-3027：缺右读数 UNDETERMINED（SQL 只插两行支点）——脚本不计算

# ============ A320 R0 ============
a320_arms = {"NOSE": D("1400.000"), "LEFT": D("8400.000"), "RIGHT": D("8400.000")}
a320_front = [("38000", "6500"), ("44000", "7100"), ("46000", "7150")]
a320_aft = [("38000", "7600"), ("44000", "7900"), ("46000", "7950")]
b6108 = weigh("A320 B-6108 baseline (40860 between nodes, INSIDE)",
              a320_arms,
              [("NOSE", "4400", "0"), ("LEFT", "18600", "0"), ("RIGHT", "18600", "0")],
              [("燃油", "700", D("7600"), -1), ("滑油", "40", D("7200"), -1)],
              a320_front, a320_aft, "38000", "46000")

# ============ ARJ21 R0 ============
arj_arms = {"NOSE": D("1200.000"), "LEFT": D("7200.000"), "RIGHT": D("7200.000")}
arj_front = [("22000", "6200"), ("27000", "6700"), ("28000", "6750")]
arj_aft = [("22000", "7100"), ("27000", "7400"), ("28000", "7450")]
# B-11030 待复核：超总重（精确差值）
weigh("ARJ21 B-11030 weighing OVER_WEIGHT (draft, cannot baseline)",
      arj_arms,
      [("NOSE", "3100", "0"), ("LEFT", "12750", "0"), ("RIGHT", "12750", "0")],
      [("燃油", "220", D("6800"), -1), ("滑油", "40", D("6400"), -1)],
      arj_front, arj_aft, "22000", "28000")

# ============ A330 R0/R1（仅规则，无基线飞机：B-6571/B-7411 显示未建立基线） ============
a330_front = [("100000", "9000"), ("120000", "9800")]
a330_aft = [("100000", "10500"), ("120000", "11000")]
print("A330: R0 + R1 rules only; aircraft B-6571/B-7411 remain NO BASELINE")
