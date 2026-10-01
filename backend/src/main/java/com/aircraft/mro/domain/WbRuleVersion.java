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
 * 称重规则版本（模块八）。
 *
 * <p>每个机型一组版本：DRAFT 草稿可改，EFFECTIVE 生效后冻结，
 * 新版发布后旧版转 SUPERSEDED。称重记录固化 rule_revision_id，
 * 旧版规则及其包线判断永远保留，新版不得回算覆盖历史称重。
 *
 * <p>规则内全部以统一单位存储：重量 kg、臂长 mm（基准点起算的站位臂）。
 */
@Data
@Entity
@Table(name = "wb_rule_version")
public class WbRuleVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 机型：B737 / A320 / A330 / ARJ21 */
    @Column(name = "model", nullable = false, length = 16)
    private String model;

    /** 版本号：R0 / R1 … */
    @Column(name = "revision_no", nullable = false, length = 16)
    private String revisionNo;

    /** DRAFT 草稿 / EFFECTIVE 生效 / SUPERSEDED 已被取代（冻结） */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    /** 基准点名称，如「机身站位 STA0（机头前 130 in）」 */
    @Column(name = "datum_name", nullable = false, length = 128)
    private String datumName;

    /** 许用总重下界（kg） */
    @Column(name = "min_weight_kg", nullable = false, precision = 12, scale = 6)
    private BigDecimal minWeightKg;

    /** 许用总重上界（kg） */
    @Column(name = "max_weight_kg", nullable = false, precision = 12, scale = 6)
    private BigDecimal maxWeightKg;

    @Column(name = "revision_note", length = 255)
    private String revisionNote;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;
}
