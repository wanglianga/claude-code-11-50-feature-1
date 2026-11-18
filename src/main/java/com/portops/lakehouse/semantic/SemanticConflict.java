package com.portops.lakehouse.semantic;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 语义冲突事实：不可能状态的可解释记录。
 * 冲突涉及的作业事实被标记为不可收费（IMPOSSIBLE/INTERRUPTED），
 * 不会从中任选一条进入可收费作业汇总。同样按版本只增不改。
 */
@Entity
@Table(name = "semantic_conflict")
public class SemanticConflict {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "container_no", nullable = false, length = 16)
    private String containerNo;

    /** DEVICE_OVERLAP / DUAL_SLOT_DROP / PHASE_ORDER / NO_REGRESSION_GUARD */
    @Column(name = "conflict_type", nullable = false, length = 40)
    private String conflictType;

    /** HIGH/MEDIUM */
    @Column(name = "severity", nullable = false, length = 16)
    private String severity;

    @Column(name = "detail", nullable = false, columnDefinition = "text")
    private String detail;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_ids", nullable = false, columnDefinition = "jsonb")
    private String eventIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "related_fact_ids", nullable = false, columnDefinition = "jsonb")
    private String relatedFactIds = "[]";

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "record_status", nullable = false, length = 16)
    private String recordStatus = "ACTIVE";

    @Column(name = "superseded_by_run")
    private Long supersededByRun;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getContainerNo() { return containerNo; }
    public void setContainerNo(String v) { this.containerNo = v; }
    public String getConflictType() { return conflictType; }
    public void setConflictType(String v) { this.conflictType = v; }
    public String getSeverity() { return severity; }
    public void setSeverity(String v) { this.severity = v; }
    public String getDetail() { return detail; }
    public void setDetail(String v) { this.detail = v; }
    public String getEventIds() { return eventIds; }
    public void setEventIds(String v) { this.eventIds = v; }
    public String getRelatedFactIds() { return relatedFactIds; }
    public void setRelatedFactIds(String v) { this.relatedFactIds = v; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer v) { this.version = v; }
    public Long getRunId() { return runId; }
    public void setRunId(Long v) { this.runId = v; }
    public String getRecordStatus() { return recordStatus; }
    public void setRecordStatus(String v) { this.recordStatus = v; }
    public Long getSupersededByRun() { return supersededByRun; }
    public void setSupersededByRun(Long v) { this.supersededByRun = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
