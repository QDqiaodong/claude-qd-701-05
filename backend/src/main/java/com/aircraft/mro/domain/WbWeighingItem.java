package com.aircraft.mro.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 称重明细行：每台秤的毛读/皮重、燃油/滑油条件项、需要扣除或补入的临时项。
 *
 * <p>每行保留原始读数（字符串原样）、原单位，以及换算后的统一值 kg/mm 快照；
 * item_type：
 * <ul>
 *   <li>PIVOT 支点行：grossReading + tareReading，参与支点净重/总重/力矩；</li>
 *   <li>FUEL 燃油 / OIL 滑油：按 condition_effect 默认 DEDUCT 扣除（求空重时减掉）；</li>
 *   <li>TEMP 临时项：按 condition_effect 扣除或补入；</li>
 *   <li>CONDITION 其他称重条件项（无臂长，只影响重量）。</li>
 * </ul>
 *
 * <p>重量行没有臂长时允许 armRaw 为空；有重量而无臂长的行只改重量不改力矩。
 */
@Data
@Entity
@Table(name = "wb_weighing_item")
public class WbWeighingItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "weighing_id", nullable = false)
    private Long weighingId;

    /** PIVOT / FUEL / OIL / TEMP / CONDITION */
    @Column(name = "item_type", nullable = false, length = 16)
    private String itemType;

    /** 支点编号（PIVOT 行）：NOSE / LEFT / RIGHT */
    @Column(name = "pivot_code", length = 16)
    private String pivotCode;

    /** 本行秤具编号（支点行各台秤各记各的） */
    @Column(name = "scale_no", length = 32)
    private String scaleNo;

    @Column(name = "item_name", length = 64)
    private String itemName;

    /** 毛读（原文） */
    @Column(name = "gross_reading", length = 32)
    private String grossReading;

    /** 皮重（原文），默认 "0" */
    @Column(name = "tare_reading", length = 32)
    private String tareReading;

    /** 臂长原文（非支点行可空） */
    @Column(name = "arm_reading", length = 32)
    private String armReading;

    /** 数量（临时项数量；支点/条件行默认 1） */
    @Column(name = "quantity")
    private Integer quantity;

    /** 本行单位：KG / LB / MM / IN。重量单位与长度单位在两列共写时以单位头判定，
     *  简化为 weight_unit + length_unit 两列 */
    @Column(name = "weight_unit", length = 4)
    private String weightUnit;

    @Column(name = "length_unit", length = 4)
    private String lengthUnit;

    /** 对最终空重的作用：ADD 补入 / DEDUCT 扣除（FUEL/OIL 默认 DEDUCT） */
    @Column(name = "condition_effect", length = 8)
    private String conditionEffect;

    // ---- 统一单位快照（kg / mm）；计算失败时可空，问题记入表头 calc_issues ----
    @Column(name = "gross_kg", precision = 14, scale = 6)
    private BigDecimal grossKg;

    @Column(name = "tare_kg", precision = 14, scale = 6)
    private BigDecimal tareKg;

    @Column(name = "net_kg", precision = 14, scale = 6)
    private BigDecimal netKg;

    @Column(name = "arm_mm", precision = 12, scale = 3)
    private BigDecimal armMm;

    /** 排序号 */
    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    @Column(name = "reason", length = 255)
    private String reason;
}
