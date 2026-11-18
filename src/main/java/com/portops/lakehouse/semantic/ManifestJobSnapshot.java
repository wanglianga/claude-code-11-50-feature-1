package com.portops.lakehouse.semantic;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 航次作业快照：清单冻结时把当时的语义解释版本边界固化，
 * 与航次费用快照（voyage_manifest/manifest_item）分开保存、互不覆盖。
 * 不可更新、不可删除（数据库触发器保证）。
 */
@Entity
@Table(name = "manifest_job_snapshot")
public class ManifestJobSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "manifest_id", nullable = false, unique = true)
    private Long manifestId;

    @Column(name = "voyage_no", nullable = false, length = 32)
    private String voyageNo;

    /** {箱号: semantic_compile_run.id} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "run_frontier", nullable = false, columnDefinition = "jsonb")
    private String runFrontier;

    @Column(name = "fact_count", nullable = false)
    private Integer factCount;

    @Column(name = "conflict_count", nullable = false)
    private Integer conflictCount;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getManifestId() { return manifestId; }
    public void setManifestId(Long v) { this.manifestId = v; }
    public String getVoyageNo() { return voyageNo; }
    public void setVoyageNo(String v) { this.voyageNo = v; }
    public String getRunFrontier() { return runFrontier; }
    public void setRunFrontier(String v) { this.runFrontier = v; }
    public Integer getFactCount() { return factCount; }
    public void setFactCount(Integer v) { this.factCount = v; }
    public Integer getConflictCount() { return conflictCount; }
    public void setConflictCount(Integer v) { this.conflictCount = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
