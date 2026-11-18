package com.portops.lakehouse.semantic;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 语义编译运行：每次编译为箱号产出一个新的解释版本（run_seq 单调递增）。
 * 保存事件水位与规则快照，使任一历史版本都可复算、可解释。
 */
@Entity
@Table(name = "semantic_compile_run",
        uniqueConstraints = @UniqueConstraint(columnNames = {"container_no", "run_seq"}))
public class SemanticCompileRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "container_no", nullable = false, length = 16)
    private String containerNo;

    @Column(name = "run_seq", nullable = false)
    private Long runSeq;

    /** MANUAL/ALL */
    @Column(name = "trigger_mode", nullable = false, length = 16)
    private String triggerMode;

    /** 本次编译使用的事件水位 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_frontier", nullable = false, columnDefinition = "jsonb")
    private String eventFrontier;

    /** 本次编译使用的规则快照 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_snapshot", nullable = false, columnDefinition = "jsonb")
    private String ruleSnapshot;

    @Column(name = "fact_count", nullable = false)
    private Integer factCount = 0;

    @Column(name = "conflict_count", nullable = false)
    private Integer conflictCount = 0;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getContainerNo() { return containerNo; }
    public void setContainerNo(String v) { this.containerNo = v; }
    public Long getRunSeq() { return runSeq; }
    public void setRunSeq(Long v) { this.runSeq = v; }
    public String getTriggerMode() { return triggerMode; }
    public void setTriggerMode(String v) { this.triggerMode = v; }
    public String getEventFrontier() { return eventFrontier; }
    public void setEventFrontier(String v) { this.eventFrontier = v; }
    public String getRuleSnapshot() { return ruleSnapshot; }
    public void setRuleSnapshot(String v) { this.ruleSnapshot = v; }
    public Integer getFactCount() { return factCount; }
    public void setFactCount(Integer v) { this.factCount = v; }
    public Integer getConflictCount() { return conflictCount; }
    public void setConflictCount(Integer v) { this.conflictCount = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
