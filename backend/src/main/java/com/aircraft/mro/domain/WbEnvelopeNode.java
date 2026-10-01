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
 * 重心包线折线节点：边界不是一个固定数字，而是由 (重量, 重心臂) 节点组成的折线；
 * 重量落在两节点之间时按线性插值取该重量下的边界。
 *
 * <p>boundary_kind = FRONT 前重心边界 / AFT 后重心边界；同一边界的节点重量严格递增。
 * 全部以统一单位 kg / mm 存储。
 */
@Data
@Entity
@Table(name = "wb_envelope_node")
public class WbEnvelopeNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_revision_id", nullable = false)
    private Long ruleRevisionId;

    /** FRONT 前边界 / AFT 后边界 */
    @Column(name = "boundary_kind", nullable = false, length = 8)
    private String boundaryKind;

    /** 节点重量（kg） */
    @Column(name = "weight_kg", nullable = false, precision = 12, scale = 6)
    private BigDecimal weightKg;

    /** 该重量下的边界重心臂（mm） */
    @Column(name = "arm_mm", nullable = false, precision = 12, scale = 3)
    private BigDecimal armMm;
}
