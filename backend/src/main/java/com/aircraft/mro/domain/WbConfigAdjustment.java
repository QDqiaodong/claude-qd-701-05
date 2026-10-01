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
 * 构型调整项：安装/拆除设备的重量、臂长、数量、原因与关联工卡。
 *
 * <p>行随派生动作一次性写入并固化；aircraft_id 冗余落库，
 * 服务端据此拒绝把其他飞机的调整项并入本机构型链。
 */
@Data
@Entity
@Table(name = "wb_config_adjustment")
public class WbConfigAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_version_id", nullable = false)
    private Long configVersionId;

    @Column(name = "aircraft_id", nullable = false)
    private Long aircraftId;

    /** INSTALL 安装（+重量/力矩）/ REMOVE 拆除（-重量/力矩） */
    @Column(name = "action", nullable = false, length = 8)
    private String action;

    @Column(name = "item_name", nullable = false, length = 64)
    private String itemName;

    /** 单位重量原文 */
    @Column(name = "weight_reading", nullable = false, length = 32)
    private String weightReading;

    /** 臂长原文 */
    @Column(name = "arm_reading", nullable = false, length = 32)
    private String armReading;

    @Column(name = "weight_unit", nullable = false, length = 4)
    private String weightUnit;

    @Column(name = "length_unit", nullable = false, length = 4)
    private String lengthUnit;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    /** 统一单位快照 */
    @Column(name = "unit_weight_kg", nullable = false, precision = 14, scale = 6)
    private BigDecimal unitWeightKg;

    @Column(name = "arm_mm", nullable = false, precision = 12, scale = 3)
    private BigDecimal armMm;

    @Column(name = "total_weight_kg", nullable = false, precision = 14, scale = 6)
    private BigDecimal totalWeightKg;

    @Column(name = "moment", nullable = false, precision = 18, scale = 3)
    private BigDecimal moment;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "work_card_no", length = 32)
    private String workCardNo;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;
}
