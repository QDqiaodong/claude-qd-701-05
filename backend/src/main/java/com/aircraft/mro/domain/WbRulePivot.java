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
 * 规则起落架支点：支点编号（NOSE / LEFT / RIGHT 等）相对基准点的臂长（mm）。
 * 称重读数的支点编号必须能在所属规则版本里找到，否则判非法。
 */
@Data
@Entity
@Table(name = "wb_rule_pivot")
public class WbRulePivot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_revision_id", nullable = false)
    private Long ruleRevisionId;

    /** 支点编号：NOSE / LEFT / RIGHT */
    @Column(name = "pivot_code", nullable = false, length = 16)
    private String pivotCode;

    @Column(name = "pivot_name", nullable = false, length = 32)
    private String pivotName;

    /** 臂长（mm，基准点起算） */
    @Column(name = "arm_mm", nullable = false, precision = 12, scale = 3)
    private BigDecimal armMm;
}
