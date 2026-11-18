package com.portops.lakehouse.semantic;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 按设备类型定义的作业阶段规则：阶段序列、允许转换、最小必要证据、可容忍时间间隔。
 * 规则可随运营调整（UPDATE 该行），但每次编译把规则快照进 semantic_compile_run，
 * 旧解释版本不随后续规则调整而改变。
 */
@Entity
@Table(name = "semantic_phase_rule")
public class SemanticPhaseRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** CRANE/GATE/YARD/VESSEL */
    @Column(name = "device_type", nullable = false, unique = true, length = 24)
    private String deviceType;

    /** 有序阶段定义 [{code,name}] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "phases", nullable = false, columnDefinition = "jsonb")
    private String phases;

    /** 允许转换 [["HOIST","TRAVEL"],...] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_transitions", nullable = false, columnDefinition = "jsonb")
    private String allowedTransitions;

    /** 最小必要证据 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "min_evidence", nullable = false, columnDefinition = "jsonb")
    private String minEvidence;

    /** 可容忍时间间隔 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tolerances", nullable = false, columnDefinition = "jsonb")
    private String tolerances;

    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String v) { this.deviceType = v; }
    public String getPhases() { return phases; }
    public void setPhases(String v) { this.phases = v; }
    public String getAllowedTransitions() { return allowedTransitions; }
    public void setAllowedTransitions(String v) { this.allowedTransitions = v; }
    public String getMinEvidence() { return minEvidence; }
    public void setMinEvidence(String v) { this.minEvidence = v; }
    public String getTolerances() { return tolerances; }
    public void setTolerances(String v) { this.tolerances = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
}
