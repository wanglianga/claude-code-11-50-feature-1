package com.portops.lakehouse.semantic;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 作业语义事实：来源事件编译出的可解释事实。
 *
 * <p>版本化语义：修正只能生成新的解释版本——旧事实不删除、不改写，
 * 仅允许 record_status ACTIVE→SUPERSEDED（数据库触发器兜底）。
 * source_event_ids 保留每个事实使用的原始事件范围，结算可据此追溯纳入/排除原因。</p>
 */
@Entity
@Table(name = "semantic_fact")
public class SemanticFact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "container_no", nullable = false, length = 16)
    private String containerNo;

    /** JOB（作业）/ STATE（箱子当前状态事实） */
    @Column(name = "fact_type", nullable = false, length = 24)
    private String factType;

    /** 逻辑作业号（箱号+链序号），STATE 事实为空 */
    @Column(name = "job_no", length = 48)
    private String jobNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private FactStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "confidence", nullable = false, length = 16)
    private ConfidenceLevel confidence;

    @Column(name = "device_type", length = 24)
    private String deviceType;

    @Column(name = "device_id", length = 40)
    private String deviceId;

    /** 各阶段证据 {HOIST:{eventIds,bizTime,action},...} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "phase_state", nullable = false, columnDefinition = "jsonb")
    private String phaseState;

    /** 本事实使用的原始事件范围（事件账 id） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_event_ids", nullable = false, columnDefinition = "jsonb")
    private String sourceEventIds;

    @Column(name = "first_event_id")
    private Long firstEventId;

    @Column(name = "last_event_id")
    private Long lastEventId;

    @Column(name = "start_biz_time")
    private OffsetDateTime startBizTime;

    @Column(name = "end_biz_time")
    private OffsetDateTime endBizTime;

    @Column(name = "from_location", length = 64)
    private String fromLocation;

    @Column(name = "to_location", length = 64)
    private String toLocation;

    @Column(name = "chargeable", nullable = false)
    private boolean chargeable;

    @Column(name = "exclude_reason", columnDefinition = "text")
    private String excludeReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "conflict_ids", nullable = false, columnDefinition = "jsonb")
    private String conflictIds = "[]";

    /** 推断依据、置信等级与撤销规则 */
    @Column(name = "inference_note", columnDefinition = "text")
    private String inferenceNote;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    /** ACTIVE/SUPERSEDED */
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
    public String getFactType() { return factType; }
    public void setFactType(String v) { this.factType = v; }
    public String getJobNo() { return jobNo; }
    public void setJobNo(String v) { this.jobNo = v; }
    public FactStatus getStatus() { return status; }
    public void setStatus(FactStatus v) { this.status = v; }
    public ConfidenceLevel getConfidence() { return confidence; }
    public void setConfidence(ConfidenceLevel v) { this.confidence = v; }
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String v) { this.deviceType = v; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String v) { this.deviceId = v; }
    public String getPhaseState() { return phaseState; }
    public void setPhaseState(String v) { this.phaseState = v; }
    public String getSourceEventIds() { return sourceEventIds; }
    public void setSourceEventIds(String v) { this.sourceEventIds = v; }
    public Long getFirstEventId() { return firstEventId; }
    public void setFirstEventId(Long v) { this.firstEventId = v; }
    public Long getLastEventId() { return lastEventId; }
    public void setLastEventId(Long v) { this.lastEventId = v; }
    public OffsetDateTime getStartBizTime() { return startBizTime; }
    public void setStartBizTime(OffsetDateTime v) { this.startBizTime = v; }
    public OffsetDateTime getEndBizTime() { return endBizTime; }
    public void setEndBizTime(OffsetDateTime v) { this.endBizTime = v; }
    public String getFromLocation() { return fromLocation; }
    public void setFromLocation(String v) { this.fromLocation = v; }
    public String getToLocation() { return toLocation; }
    public void setToLocation(String v) { this.toLocation = v; }
    public boolean isChargeable() { return chargeable; }
    public void setChargeable(boolean v) { this.chargeable = v; }
    public String getExcludeReason() { return excludeReason; }
    public void setExcludeReason(String v) { this.excludeReason = v; }
    public String getConflictIds() { return conflictIds; }
    public void setConflictIds(String v) { this.conflictIds = v; }
    public String getInferenceNote() { return inferenceNote; }
    public void setInferenceNote(String v) { this.inferenceNote = v; }
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
