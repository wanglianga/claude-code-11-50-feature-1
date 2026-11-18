package com.portops.lakehouse.semantic;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 航次作业快照行：冻结时刻每个作业事实的纳入/排除结论与原始事件范围。 */
@Entity
@Table(name = "manifest_job_snapshot_item",
        uniqueConstraints = @UniqueConstraint(columnNames = {"snapshot_id", "container_no", "job_no"}))
public class ManifestJobSnapshotItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_id", nullable = false)
    private Long snapshotId;

    @Column(name = "container_no", nullable = false, length = 16)
    private String containerNo;

    @Column(name = "job_no", length = 48)
    private String jobNo;

    @Column(name = "fact_type", nullable = false, length = 24)
    private String factType;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "confidence", nullable = false, length = 16)
    private String confidence;

    @Column(name = "device_id", length = 40)
    private String deviceId;

    @Column(name = "from_location", length = 64)
    private String fromLocation;

    @Column(name = "to_location", length = 64)
    private String toLocation;

    @Column(name = "chargeable", nullable = false)
    private boolean chargeable;

    @Column(name = "exclude_reason", columnDefinition = "text")
    private String excludeReason;

    @Column(name = "fact_id", nullable = false)
    private Long factId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_event_ids", nullable = false, columnDefinition = "jsonb")
    private String sourceEventIds;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSnapshotId() { return snapshotId; }
    public void setSnapshotId(Long v) { this.snapshotId = v; }
    public String getContainerNo() { return containerNo; }
    public void setContainerNo(String v) { this.containerNo = v; }
    public String getJobNo() { return jobNo; }
    public void setJobNo(String v) { this.jobNo = v; }
    public String getFactType() { return factType; }
    public void setFactType(String v) { this.factType = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getConfidence() { return confidence; }
    public void setConfidence(String v) { this.confidence = v; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String v) { this.deviceId = v; }
    public String getFromLocation() { return fromLocation; }
    public void setFromLocation(String v) { this.fromLocation = v; }
    public String getToLocation() { return toLocation; }
    public void setToLocation(String v) { this.toLocation = v; }
    public boolean isChargeable() { return chargeable; }
    public void setChargeable(boolean v) { this.chargeable = v; }
    public String getExcludeReason() { return excludeReason; }
    public void setExcludeReason(String v) { this.excludeReason = v; }
    public Long getFactId() { return factId; }
    public void setFactId(Long v) { this.factId = v; }
    public String getSourceEventIds() { return sourceEventIds; }
    public void setSourceEventIds(String v) { this.sourceEventIds = v; }
}
