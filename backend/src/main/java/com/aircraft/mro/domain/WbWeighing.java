package com.aircraft.mro.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 实机称重记录（模块八）。
 *
 * <p>读数、皮重、燃油/滑油条件、临时扣补项在 {@link WbWeighingItem} 中逐行保留原始读数与原单位；
 * 本表保存按引用规则版本一次性算出的统一单位（kg/mm）结果快照——
 * 任何重复查看都读快照或从原始读数重新换算（换算无状态、无累积误差），
 * 规则日后修订也不会回算覆盖这里的历史结果。
 *
 * <p>status：DRAFT 待复核 / CONFIRMED 复核确认（可成为基线）。
 * envelope_status：INSIDE 包线内 / OUTSIDE 包线外 / UNDETERMINED 无法判定。
 */
@Data
@Entity
@Table(name = "wb_weighing")
public class WbWeighing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 称重单号 */
    @Column(name = "weighing_no", nullable = false, length = 32)
    private String weighingNo;

    @Column(name = "aircraft_id", nullable = false)
    private Long aircraftId;

    @Column(name = "tail_no", nullable = false, length = 16)
    private String tailNo;

    @Column(name = "model", nullable = false, length = 16)
    private String model;

    @Column(name = "weighing_date", nullable = false)
    private LocalDate weighingDate;

    /** 秤具编号（如三台秤分别在明细中记录各自编号时，这里记主秤组） */
    @Column(name = "scale_no", nullable = false, length = 32)
    private String scaleNo;

    /** 记录时引用的规则版本（永久固化） */
    @Column(name = "rule_revision_id", nullable = false)
    private Long ruleRevisionId;

    /** 固化规则版本号与机型，便于旧规则删除风险下的历史追溯 */
    @Column(name = "rule_revision_no", nullable = false, length = 16)
    private String ruleRevisionNo;

    /** 称重默认单位制：METRIC kg/mm 或 IMPERIAL lb/in（各行可自带单位） */
    @Column(name = "unit_system", nullable = false, length = 8)
    private String unitSystem;

    /** 燃油条件说明（各燃油项重量仍在 item 行记录） */
    @Column(name = "fuel_condition", length = 255)
    private String fuelCondition;

    /** 滑油条件说明 */
    @Column(name = "oil_condition", length = 255)
    private String oilCondition;

    /** 场地/姿态等备注 */
    @Column(name = "remark", length = 255)
    private String remark;

    /** DRAFT 待复核 / CONFIRMED 已复核确认 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "weighed_by", length = 32)
    private String weighedBy;

    @Column(name = "reviewed_by", length = 32)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    // ---- 一次性计算的统一单位（kg/mm）结果快照 ----

    /** 各支点净重合计（毛重，含燃油/滑油/临时项）kg */
    @Column(name = "gross_weight_kg", precision = 14, scale = 6)
    private BigDecimal grossWeightKg;

    /** 毛重总力矩 kg·mm */
    @Column(name = "gross_moment", precision = 18, scale = 3)
    private BigDecimal grossMoment;

    /** 扣补后最终（空重口径）重量 kg */
    @Column(name = "final_weight_kg", precision = 14, scale = 6)
    private BigDecimal finalWeightKg;

    /** 扣补后最终总力矩 kg·mm */
    @Column(name = "final_moment", precision = 18, scale = 3)
    private BigDecimal finalMoment;

    /** 最终重心臂 mm（最终力矩 / 最终重量） */
    @Column(name = "cg_arm_mm", precision = 14, scale = 3)
    private BigDecimal cgArmMm;

    /** INSIDE / OUTSIDE / UNDETERMINED */
    @Column(name = "envelope_status", nullable = false, length = 16)
    private String envelopeStatus;

    /** OUTSIDE 时越界方向：FORWARD 偏前 / AFT 偏后 / OVER_WEIGHT 超总重 / UNDER_WEIGHT 低于总重 */
    @Column(name = "violation_kind", length = 16)
    private String violationKind;

    /** 越界差值：重心越界为 mm（带符号说明在文案中），重量越界为 kg */
    @Column(name = "violation_delta", precision = 14, scale = 6)
    private BigDecimal violationDelta;

    /** 判定时所在的前/后边界臂 mm（插值结果），UNDETERMINED 时可空 */
    @Column(name = "limit_front_mm", precision = 12, scale = 3)
    private BigDecimal limitFrontMm;

    @Column(name = "limit_aft_mm", precision = 12, scale = 3)
    private BigDecimal limitAftMm;

    /** 无法判定或越界的原因文案（分号分隔） */
    @Column(name = "calc_issues", length = 512)
    private String calcIssues;

    /** 成为当前基线的时间（同一飞机同时只有一个非空，见 wb_config_version） */
    @Column(name = "baselined_at")
    private LocalDateTime baselinedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
