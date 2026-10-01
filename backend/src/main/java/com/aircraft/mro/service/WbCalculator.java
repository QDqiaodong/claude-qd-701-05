package com.aircraft.mro.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 称重与重心计算引擎（模块八）——无状态、确定性、不依赖存储。
 *
 * <p>全部中间量在统一单位（kg/mm）下以固定精度、HALF_UP 舍入：
 * <ul>
 *   <li>重量统一到 {@link #WEIGHT_SCALE}=6 位小数 kg；</li>
 *   <li>臂长统一到 {@link #ARM_SCALE}=3 位小数 mm；</li>
 *   <li>力矩 = 重量×臂长，统一到 {@link #MOMENT_SCALE}=3 位小数 kg·mm；</li>
 *   <li>重心臂 = 总力矩/总重，统一到 {@link #CG_SCALE}=3 位小数 mm。</li>
 * </ul>
 * 换算系数为精确常数（1 lb = 0.45359237 kg；1 in = 25.4 mm），同一份输入无论计算多少次、
 * 是试算还是落库，结果完全一致——不存在反复换算造成的累积误差。
 *
 * <p>判定口径（任一不满足都不得给出「包线内」）：读数缺失、支点不属于规则、
 * 换算出现非法数值、净重为负、总重超出许用范围 → 结论为 UNDETERMINED 或 OUTSIDE。
 */
public final class WbCalculator {

    private WbCalculator() {
    }

    /** 1 磅（常衡磅）精确等于 0.45359237 千克 */
    public static final BigDecimal LB_TO_KG = new BigDecimal("0.45359237");
    /** 1 英寸精确等于 25.4 毫米 */
    public static final BigDecimal IN_TO_MM = new BigDecimal("25.4");

    public static final int WEIGHT_SCALE = 6;
    public static final int ARM_SCALE = 3;
    public static final int MOMENT_SCALE = 3;
    public static final int CG_SCALE = 3;

    public static final String KG = "KG";
    public static final String LB = "LB";
    public static final String MM = "MM";
    public static final String IN = "IN";

    // ---- 结论常量 ----
    public static final String INSIDE = "INSIDE";
    public static final String OUTSIDE = "OUTSIDE";
    public static final String UNDETERMINED = "UNDETERMINED";
    public static final String FORWARD = "FORWARD";
    public static final String AFT = "AFT";
    public static final String OVER_WEIGHT = "OVER_WEIGHT";
    public static final String UNDER_WEIGHT = "UNDER_WEIGHT";

    /** 规则包线快照（已在统一单位下） */
    public static class RuleData {
        public final String model;
        public final String revisionNo;
        public final java.util.Map<String, BigDecimal> pivotArms;
        public final BigDecimal minWeightKg;
        public final BigDecimal maxWeightKg;
        public final List<BigDecimal[]> frontNodes;
        public final List<BigDecimal[]> aftNodes;

        public RuleData(String model, String revisionNo,
                        java.util.Map<String, BigDecimal> pivotArms,
                        BigDecimal minWeightKg, BigDecimal maxWeightKg,
                        List<BigDecimal[]> frontNodes, List<BigDecimal[]> aftNodes) {
            this.model = model;
            this.revisionNo = revisionNo;
            this.pivotArms = pivotArms;
            this.minWeightKg = minWeightKg;
            this.maxWeightKg = maxWeightKg;
            this.frontNodes = frontNodes;
            this.aftNodes = aftNodes;
        }
    }

    /** 明细行输入（原始字符串 + 原单位，计算前不做任何预处理） */
    public static class ItemInput {
        public String itemType;   // PIVOT / FUEL / OIL / TEMP / CONDITION
        public String pivotCode;
        public String scaleNo;
        public String itemName;
        public String grossReading;
        public String tareReading;
        public String armReading;
        public Integer quantity;
        public String weightUnit; // KG / LB
        public String lengthUnit; // MM / IN
        public String effect;     // ADD / DEDUCT
        public String reason;
        public Integer lineNo;
    }

    /** 支点净重结果 */
    public static class PivotResult {
        public String pivotCode;
        public String scaleNo;
        public BigDecimal grossKg;
        public BigDecimal tareKg;
        public BigDecimal netKg;
        public BigDecimal armMm;
        public BigDecimal moment;
    }

    /** 扣补项结果 */
    public static class CorrectionResult {
        public String itemType;
        public String itemName;
        public String effect;
        public BigDecimal unitWeightKg;
        public int quantity;
        public BigDecimal totalWeightKg;
        public BigDecimal armMm;
        public BigDecimal signedWeightKg;
        public BigDecimal signedMoment;
    }

    /** 完整计算结果 */
    public static class Result {
        public List<PivotResult> pivots = new ArrayList<>();
        public List<CorrectionResult> corrections = new ArrayList<>();
        public List<String> issues = new ArrayList<>();
        public BigDecimal grossWeightKg = BigDecimal.ZERO.setScale(WEIGHT_SCALE);
        public BigDecimal grossMoment = BigDecimal.ZERO.setScale(MOMENT_SCALE);
        public BigDecimal finalWeightKg = BigDecimal.ZERO.setScale(WEIGHT_SCALE);
        public BigDecimal finalMoment = BigDecimal.ZERO.setScale(MOMENT_SCALE);
        public BigDecimal cgArmMm;
        public String envelopeStatus = UNDETERMINED;
        public String violationKind;
        public BigDecimal violationDelta;
        public BigDecimal limitFrontMm;
        public BigDecimal limitAftMm;
    }

    // ============================ 单位换算 ============================

    /** 重量原文 → kg，统一 6 位小数 HALF_UP。unit 为 null 时按 KG。 */
    public static BigDecimal toKg(BigDecimal raw, String unit) {
        BigDecimal v = raw;
        if (LB.equals(unit)) {
            v = raw.multiply(LB_TO_KG);
        }
        return v.setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
    }

    /** 臂长原文 → mm，统一 3 位小数 HALF_UP。unit 为 null 时按 MM。 */
    public static BigDecimal toMm(BigDecimal raw, String unit) {
        BigDecimal v = raw;
        if (IN.equals(unit)) {
            v = raw.multiply(IN_TO_MM);
        }
        return v.setScale(ARM_SCALE, RoundingMode.HALF_UP);
    }

    /** kg → lb（仅用于展示），6 位小数 */
    public static BigDecimal kgToLb(BigDecimal kg) {
        return kg.divide(LB_TO_KG, WEIGHT_SCALE, RoundingMode.HALF_UP);
    }

    /** mm → in（仅用于展示），4 位小数保证往返可见 */
    public static BigDecimal mmToIn(BigDecimal mm) {
        return mm.divide(IN_TO_MM, 4, RoundingMode.HALF_UP);
    }

    /** 解析数字：非数字 / NaN / Infinity 一律抛非法数值异常。 */
    public static BigDecimal parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        BigDecimal v;
        try {
            v = new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new IllegalNumberException("非法数值：「" + raw + "」不是有效数字");
        }
        // BigDecimal 自身不含 NaN/Infinity，但统一再挡一道超范围输入
        if (v.abs().compareTo(new BigDecimal("1E12")) >= 0) {
            throw new IllegalNumberException("非法数值：「" + raw + "」超出允许范围");
        }
        return v;
    }

    /** 严格非负解析（读数、皮重）。 */
    public static BigDecimal parseNonNegative(String raw, String label) {
        BigDecimal v = parse(raw);
        if (v != null && v.signum() < 0) {
            throw new IllegalNumberException(label + "不能为负数：「" + raw + "」");
        }
        return v;
    }

    public static class IllegalNumberException extends RuntimeException {
        public IllegalNumberException(String message) {
            super(message);
        }
    }

    // ============================ 称重计算 ============================

    public static Result weigh(RuleData rule, List<ItemInput> items) {
        Result r = new Result();

        BigDecimal grossW = BigDecimal.ZERO.setScale(WEIGHT_SCALE);
        BigDecimal grossM = BigDecimal.ZERO.setScale(MOMENT_SCALE);
        List<ItemInput> pivotInputs = new ArrayList<>();

        // 1) 支点行：毛读 - 皮重 = 净重，臂长只认规则版本
        for (ItemInput in : items) {
            if (!"PIVOT".equals(in.itemType)) {
                continue;
            }
            pivotInputs.add(in);
            PivotResult p = new PivotResult();
            p.pivotCode = in.pivotCode;
            p.scaleNo = in.scaleNo;
            try {
                BigDecimal ruleArm = rule.pivotArms == null ? null : rule.pivotArms.get(in.pivotCode);
                if (ruleArm == null) {
                    r.issues.add("支点「" + in.pivotCode + "」不属于规则 " + rule.model + " "
                            + rule.revisionNo + "，该读数不能参与判定");
                }
                p.armMm = ruleArm;

                if (in.grossReading == null || in.grossReading.trim().isEmpty()) {
                    r.issues.add("支点「" + name(in) + "」毛读数缺失");
                } else {
                    BigDecimal gross = parseNonNegative(in.grossReading, "支点毛读");
                    BigDecimal tareRaw = (in.tareReading == null || in.tareReading.trim().isEmpty())
                            ? BigDecimal.ZERO : parseNonNegative(in.tareReading, "支点皮重");
                    p.grossKg = toKg(gross, in.weightUnit);
                    p.tareKg = toKg(tareRaw, in.weightUnit);
                    p.netKg = p.grossKg.subtract(p.tareKg).setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
                    if (p.netKg.signum() < 0) {
                        r.issues.add("支点「" + name(in) + "」净重为负（毛读 " + in.grossReading
                                + " 小于皮重 " + in.tareReading + "）");
                    }
                    if (ruleArm != null) {
                        p.moment = p.netKg.multiply(ruleArm).setScale(MOMENT_SCALE, RoundingMode.HALF_UP);
                        grossW = grossW.add(p.netKg);
                        grossM = grossM.add(p.moment);
                    }
                }
            } catch (IllegalNumberException e) {
                r.issues.add(e.getMessage());
            }
            r.pivots.add(p);
        }

        if (rule.pivotArms != null) {
            for (String code : rule.pivotArms.keySet()) {
                boolean seen = pivotInputs.stream().anyMatch(i -> code.equals(i.pivotCode));
                if (!seen) {
                    r.issues.add("规则支点「" + code + "」缺少称重读数");
                }
            }
        }

        r.grossWeightKg = grossW;
        r.grossMoment = grossM.setScale(MOMENT_SCALE, RoundingMode.HALF_UP);

        // 2) 燃油/滑油/临时/条件扣补项
        BigDecimal finalW = grossW;
        BigDecimal finalM = grossM;
        for (ItemInput in : items) {
            if ("PIVOT".equals(in.itemType)) {
                continue;
            }
            CorrectionResult c = new CorrectionResult();
            c.itemType = in.itemType;
            c.itemName = in.itemName;
            c.effect = in.effect;
            try {
                if (in.grossReading == null || in.grossReading.trim().isEmpty()) {
                    r.issues.add(label(in) + "重量读数缺失");
                    r.corrections.add(c);
                    continue;
                }
                BigDecimal unit = parseNonNegative(in.grossReading, label(in));
                int qty = in.quantity == null || in.quantity <= 0 ? 1 : in.quantity;
                BigDecimal unitKg = toKg(unit, in.weightUnit);
                BigDecimal totalKg = unitKg.multiply(BigDecimal.valueOf(qty))
                        .setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
                c.unitWeightKg = unitKg;
                c.quantity = qty;
                c.totalWeightKg = totalKg;

                BigDecimal arm = null;
                if (in.armReading != null && !in.armReading.trim().isEmpty()) {
                    arm = toMm(parse(in.armReading), in.lengthUnit);
                    if (arm.signum() < 0) {
                        r.issues.add(label(in) + "臂长不能为负");
                    }
                }
                c.armMm = arm;

                int sign = "ADD".equals(in.effect) ? 1 : -1;
                BigDecimal signedW = totalKg.multiply(BigDecimal.valueOf(sign))
                        .setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
                BigDecimal signedMo = BigDecimal.ZERO.setScale(MOMENT_SCALE);
                if (arm != null) {
                    signedMo = totalKg.multiply(arm).multiply(BigDecimal.valueOf(sign))
                            .setScale(MOMENT_SCALE, RoundingMode.HALF_UP);
                }
                c.signedWeightKg = signedW;
                c.signedMoment = signedMo;
                finalW = finalW.add(signedW);
                finalM = finalM.add(signedMo);
            } catch (IllegalNumberException e) {
                r.issues.add(e.getMessage());
            }
            r.corrections.add(c);
        }

        r.finalWeightKg = finalW.setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
        r.finalMoment = finalM.setScale(MOMENT_SCALE, RoundingMode.HALF_UP);

        if (r.finalWeightKg.signum() <= 0) {
            r.issues.add("扣补后最终重量为 " + r.finalWeightKg.toPlainString() + " kg，不是合法正数");
        } else {
            r.cgArmMm = r.finalMoment.divide(r.finalWeightKg, CG_SCALE, RoundingMode.HALF_UP);
        }

        evaluateEnvelope(rule, r);
        return r;
    }

    private static String name(ItemInput in) {
        return in.itemName != null && !in.itemName.isBlank() ? in.itemName
                : (in.pivotCode == null ? "未命名支点" : in.pivotCode);
    }

    private static String label(ItemInput in) {
        String type = switch (in.itemType) {
            case "FUEL" -> "燃油项";
            case "OIL" -> "滑油项";
            case "TEMP" -> "临时项";
            default -> "条件项";
        };
        return type + "「" + (in.itemName == null ? "" : in.itemName) + "」";
    }

    // ============================ 包线判定 ============================

    /**
     * 用扣补后最终重量与重心臂判包线：
     * 总重越界 → OUTSIDE（UNDER_WEIGHT/OVER_WEIGHT，差值为 kg）；
     * 重量合法但落在折线节点覆盖范围外 → UNDETERMINED；
     * 节点之间按线性插值取前/后边界：重心臂 &lt; 前边界 → 偏前；&gt; 后边界 → 偏后（差值为 mm）。
     */
    public static void evaluateEnvelope(RuleData rule, Result r) {
        if (!r.issues.isEmpty()) {
            r.envelopeStatus = UNDETERMINED;
            return;
        }
        BigDecimal w = r.finalWeightKg;
        if (w.compareTo(rule.minWeightKg) < 0) {
            r.envelopeStatus = OUTSIDE;
            r.violationKind = UNDER_WEIGHT;
            r.violationDelta = rule.minWeightKg.subtract(w).setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
            return;
        }
        if (w.compareTo(rule.maxWeightKg) > 0) {
            r.envelopeStatus = OUTSIDE;
            r.violationKind = OVER_WEIGHT;
            r.violationDelta = w.subtract(rule.maxWeightKg).setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
            return;
        }
        BigDecimal front = interpolate(rule.frontNodes, w, r, "前重心");
        BigDecimal aft = interpolate(rule.aftNodes, w, r, "后重心");
        if (front == null || aft == null) {
            r.envelopeStatus = UNDETERMINED;
            return;
        }
        r.limitFrontMm = front;
        r.limitAftMm = aft;
        if (r.cgArmMm.compareTo(front) < 0) {
            r.envelopeStatus = OUTSIDE;
            r.violationKind = FORWARD;
            r.violationDelta = front.subtract(r.cgArmMm).setScale(ARM_SCALE, RoundingMode.HALF_UP);
        } else if (r.cgArmMm.compareTo(aft) > 0) {
            r.envelopeStatus = OUTSIDE;
            r.violationKind = AFT;
            r.violationDelta = r.cgArmMm.subtract(aft).setScale(ARM_SCALE, RoundingMode.HALF_UP);
        } else {
            r.envelopeStatus = INSIDE;
        }
    }

    /**
     * 折线线性插值：nodes 为按重量递增的 [weightKg, armMm] 数组。
     * 重量正好在节点上取节点值；两节点之间线性插值；落在折线覆盖区间之外返回 null（无法判定）。
     */
    public static BigDecimal interpolate(List<BigDecimal[]> nodes, BigDecimal weightKg, Result r, String name) {
        if (nodes == null || nodes.isEmpty()) {
            r.issues.add(name + "包线没有节点，无法判定");
            return null;
        }
        if (nodes.size() == 1) {
            return nodes.get(0)[1].setScale(ARM_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal first = nodes.get(0)[0];
        BigDecimal last = nodes.get(nodes.size() - 1)[0];
        if (weightKg.compareTo(first) < 0 || weightKg.compareTo(last) > 0) {
            r.issues.add("重量 " + weightKg.toPlainString() + " kg 落在" + name
                    + "包线节点范围（" + first.toPlainString() + "~" + last.toPlainString()
                    + " kg）之外，无法线性插值判定");
            return null;
        }
        for (int i = 0; i < nodes.size() - 1; i++) {
            BigDecimal w1 = nodes.get(i)[0];
            BigDecimal a1 = nodes.get(i)[1];
            BigDecimal w2 = nodes.get(i + 1)[0];
            BigDecimal a2 = nodes.get(i + 1)[1];
            int c1 = weightKg.compareTo(w1);
            int c2 = weightKg.compareTo(w2);
            if (c1 == 0) {
                return a1.setScale(ARM_SCALE, RoundingMode.HALF_UP);
            }
            if (c2 == 0) {
                return a2.setScale(ARM_SCALE, RoundingMode.HALF_UP);
            }
            if (c1 > 0 && c2 < 0) {
                // arm = a1 + (a2-a1)*(w-w1)/(w2-w1)，先以 6 位中间精度算比例再落 3 位
                BigDecimal ratio = weightKg.subtract(w1)
                        .divide(w2.subtract(w1), 6, RoundingMode.HALF_UP);
                return a1.add(a2.subtract(a1).multiply(ratio))
                        .setScale(ARM_SCALE, RoundingMode.HALF_UP);
            }
        }
        r.issues.add(name + "包线折线无法覆盖重量 " + weightKg.toPlainString() + " kg");
        return null;
    }

    /**
     * 构型派生：在父版本重量/力矩上叠加一组调整项后重新判包线。
     *
     * @param adjustments 每项 [action(INSTALL/REMOVE), unitWeightKg(已换算), armMm(已换算), quantity]
     */
    public static Result derive(RuleData rule, BigDecimal parentWeightKg, BigDecimal parentMoment,
                                List<Object[]> adjustments) {
        Result r = new Result();
        BigDecimal w = parentWeightKg.setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
        BigDecimal m = parentMoment.setScale(MOMENT_SCALE, RoundingMode.HALF_UP);
        for (Object[] a : adjustments) {
            String action = (String) a[0];
            BigDecimal unit = (BigDecimal) a[1];
            BigDecimal arm = (BigDecimal) a[2];
            int qty = a[3] == null ? 1 : (Integer) a[3];
            int sign = "REMOVE".equals(action) ? -1 : 1;
            BigDecimal total = unit.multiply(BigDecimal.valueOf(qty)).setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
            BigDecimal mo = total.multiply(arm).multiply(BigDecimal.valueOf(sign))
                    .setScale(MOMENT_SCALE, RoundingMode.HALF_UP);
            w = w.add(total.multiply(BigDecimal.valueOf(sign))).setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
            m = m.add(mo).setScale(MOMENT_SCALE, RoundingMode.HALF_UP);
            CorrectionResult c = new CorrectionResult();
            c.itemType = "CONFIG";
            c.itemName = (String) a[4];
            c.effect = sign > 0 ? "ADD" : "DEDUCT";
            c.totalWeightKg = total;
            c.armMm = arm;
            c.signedWeightKg = total.multiply(BigDecimal.valueOf(sign));
            c.signedMoment = mo;
            c.quantity = qty;
            r.corrections.add(c);
        }
        r.grossWeightKg = w;
        r.grossMoment = m;
        r.finalWeightKg = w;
        r.finalMoment = m;
        if (w.signum() <= 0) {
            r.issues.add("派生构型后重量为 " + w.toPlainString() + " kg，不是合法正数");
        } else {
            r.cgArmMm = m.divide(w, CG_SCALE, RoundingMode.HALF_UP);
        }
        evaluateEnvelope(rule, r);
        return r;
    }
}
