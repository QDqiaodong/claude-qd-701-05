package com.aircraft.mro.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** 模块八审计事件（append-only）：规则发布、称重登记/复核/基线、构型派生全部留痕。 */
@Data
@Entity
@Table(name = "wb_event")
public class WbEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aircraft_id")
    private Long aircraftId;

    /** RULE_PUBLISH / WEIGHING_CREATE / WEIGHING_CONFIRM / BASELINE_SET / CONFIG_DERIVE */
    @Column(name = "event_type", nullable = false, length = 24)
    private String eventType;

    @Column(name = "rule_revision_id")
    private Long ruleRevisionId;

    @Column(name = "weighing_id")
    private Long weighingId;

    @Column(name = "config_version_id")
    private Long configVersionId;

    @Column(name = "operator_name", nullable = false, length = 32)
    private String operatorName;

    @Column(name = "detail", nullable = false, length = 512)
    private String detail;

    @Column(name = "event_at", nullable = false)
    private LocalDateTime eventAt;
}
