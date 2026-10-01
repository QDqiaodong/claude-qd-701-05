package com.aircraft.mro.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 称重基线派生的构型版本（模块八）。
 *
 * <p>version_no=0 的根版本由复核确认的称重产生；之后每次安装/拆除设备，
 * 只能从当前（active_config_id 占位）版本继续派生一个新版本，形成一条完整调整链：
 * 不允许在旧版本上分叉，也不允许并入其他飞机的调整项（adjustment 行带 aircraft_id 校验）。
 *
 * <p>每个版本固化当时的统一单位重量/力矩/重心臂与包线判定（快照），
 * 旧版本曾经合格永远按当时结果展示，不随后续规则或构型变化回算。
 */
@Data
@Entity
@Table(name = "wb_config_version")
public class WbConfigVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aircraft_id", nullable = false)
    private Long aircraftId;

    @Column(name = "tail_no", nullable = false, length = 16)
    private String tailNo;

    @Column(name = "model", nullable = false, length = 16)
    private String model;

    /** 链内版本号：称重基线 = 0，之后 1、2、3… */
    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    /** ROOT 称重基线 / DERIVED 调整派生 */
    @Column(name = "kind", nullable = false, length = 8)
    private String kind;

    /** 根称重 id（整条链同一个） */
    @Column(name = "root_weighing_id", nullable = false)
    private Long rootWeighingId;

    /** 来源称重（ROOT=自身称重；DERIVED 时可空） */
    @Column(name = "source_weighing_id")
    private Long sourceWeighingId;

    /** 父构型版本 id（ROOT 为 null） */
    @Column(name = "parent_version_id")
    private Long parentVersionId;

    /** 派生时所用规则版本（始终沿链引用基线规则，保证链内判定口径一致） */
    @Column(name = "rule_revision_id", nullable = false)
    private Long ruleRevisionId;

    @Column(name = "rule_revision_no", nullable = false, length = 16)
    private String ruleRevisionNo;

    @Column(name = "change_reason", length = 255)
    private String changeReason;

    /** 关联工卡（可空） */
    @Column(name = "work_card_no", length = 32)
    private String workCardNo;

    @Column(name = "operator_name", length = 32)
    private String operatorName;

    // ---- 当时结果快照（kg / mm）----
    @Column(name = "weight_kg", nullable = false, precision = 14, scale = 6)
    private BigDecimal weightKg;

    @Column(name = "moment", nullable = false, precision = 18, scale = 3)
    private BigDecimal moment;

    @Column(name = "cg_arm_mm", nullable = false, precision = 14, scale = 3)
    private BigDecimal cgArmMm;

    /** INSIDE / OUTSIDE / UNDETERMINED */
    @Column(name = "envelope_status", nullable = false, length = 16)
    private String envelopeStatus;

    @Column(name = "violation_kind", length = 16)
    private String violationKind;

    @Column(name = "violation_delta", precision = 14, scale = 6)
    private BigDecimal violationDelta;

    @Column(name = "limit_front_mm", precision = 12, scale = 3)
    private BigDecimal limitFrontMm;

    @Column(name = "limit_aft_mm", precision = 12, scale = 3)
    private BigDecimal limitAftMm;

    /** 当前版本占位：非空 = 飞机 id，同一飞机全链只有一行非空；旧版本为 NULL 不可再派生 */
    @Column(name = "active_aircraft_id")
    private Long activeAircraftId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
