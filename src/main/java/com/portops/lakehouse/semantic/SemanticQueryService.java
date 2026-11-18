package com.portops.lakehouse.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portops.lakehouse.common.BizException;
import com.portops.lakehouse.ingest.IngestEvent;
import com.portops.lakehouse.ingest.IngestEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 语义层查询服务：调度看板、可收费作业汇总、作业溯源与航次作业快照。
 * 各视图只读语义层独立保存的状态，不回改事件账与提交。
 */
@Service
public class SemanticQueryService {

    private final SemanticFactRepository factRepo;
    private final SemanticConflictRepository conflictRepo;
    private final SemanticCompileRunRepository runRepo;
    private final ManifestJobSnapshotRepository snapshotRepo;
    private final ManifestJobSnapshotItemRepository snapshotItemRepo;
    private final IngestEventRepository eventRepo;
    private final ObjectMapper om;

    public SemanticQueryService(SemanticFactRepository factRepo,
                                SemanticConflictRepository conflictRepo,
                                SemanticCompileRunRepository runRepo,
                                ManifestJobSnapshotRepository snapshotRepo,
                                ManifestJobSnapshotItemRepository snapshotItemRepo,
                                IngestEventRepository eventRepo,
                                ObjectMapper om) {
        this.factRepo = factRepo;
        this.conflictRepo = conflictRepo;
        this.runRepo = runRepo;
        this.snapshotRepo = snapshotRepo;
        this.snapshotItemRepo = snapshotItemRepo;
        this.eventRepo = eventRepo;
        this.om = om;
    }

    /** 单箱语义视图：当前事实、冲突、版本与三层分区（可用/推断/待裁定）。 */
    @Transactional(readOnly = true)
    public Map<String, Object> containerView(String containerNo) {
        List<SemanticFact> activeFacts = factRepo
                .findByContainerNoAndRecordStatusOrderByStartBizTimeAscIdAsc(containerNo, "ACTIVE");
        List<SemanticConflict> activeConflicts = conflictRepo
                .findByContainerNoAndRecordStatusOrderByCreatedAtAsc(containerNo, "ACTIVE");
        SemanticCompileRun run = runRepo.findTopByContainerNoOrderByRunSeqDesc(containerNo).orElse(null);

        List<Map<String, Object>> available = new ArrayList<>();
        List<Map<String, Object>> inferred = new ArrayList<>();
        List<Map<String, Object>> pending = new ArrayList<>();
        for (SemanticFact f : activeFacts) {
            if ("STATE".equals(f.getFactType())) {
                continue;
            }
            Map<String, Object> row = factRow(f);
            switch (f.getStatus()) {
                case COMPLETED -> {
                    if (f.getConfidence() == ConfidenceLevel.CONFIRMED) {
                        available.add(row);
                    } else {
                        inferred.add(row); // 完整链但离线补传降级的高置信事实
                    }
                }
                case IN_FLIGHT -> inferred.add(row);
                case INTERRUPTED, IMPOSSIBLE -> pending.add(row);
            }
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("containerNo", containerNo);
        r.put("version", run == null ? null : run.getRunSeq());
        r.put("runId", run == null ? null : run.getId());
        r.put("stateFact", activeFacts.stream().filter(f -> "STATE".equals(f.getFactType()))
                .findFirst().map(this::factRow).orElse(null));
        r.put("availableFacts", available);      // 当前可用：已证实
        r.put("inferredFacts", inferred);        // 推断状态：带置信等级与撤销规则
        r.put("pendingAdjudication", pending);   // 待裁定范围：中断/不可能
        r.put("conflicts", activeConflicts);
        r.put("factCount", activeFacts.size());
        r.put("conflictCount", activeConflicts.size());
        return r;
    }

    /** 调度看板：全部已编译箱号的三层分区汇总。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> dispatchBoard() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String c : runRepo.findAllContainerNos()) {
            Map<String, Object> view = containerView(c);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("containerNo", c);
            row.put("version", view.get("version"));
            row.put("stateFact", view.get("stateFact"));
            row.put("availableCount", ((List<?>) view.get("availableFacts")).size());
            row.put("inferredCount", ((List<?>) view.get("inferredFacts")).size());
            row.put("pendingCount", ((List<?>) view.get("pendingAdjudication")).size());
            row.put("conflictCount", view.get("conflictCount"));
            out.add(row);
        }
        return out;
    }

    /**
     * 可收费作业汇总：只纳入 ACTIVE 且 chargeable=true 的作业事实。
     * 被冲突阻止、未闭合、中断待核的作业一律不出现，可经作业溯源查询排除原因。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> chargeableSummary(String containerNo) {
        List<SemanticFact> chargeable = factRepo
                .findByRecordStatusAndChargeableTrueOrderByContainerNoAscIdAsc("ACTIVE").stream()
                .filter(f -> "JOB".equals(f.getFactType()))
                .filter(f -> containerNo == null || containerNo.isBlank()
                        || f.getContainerNo().equals(containerNo))
                .toList();
        List<Map<String, Object>> lines = new ArrayList<>();
        Set<String> excludedContainers = new LinkedHashSet<>();
        for (SemanticFact f : chargeable) {
            lines.add(factRow(f));
        }
        // 排除清单：同箱号内不可收费的作业事实
        List<SemanticFact> allActive = containerNo == null || containerNo.isBlank()
                ? factRepo.findAll().stream().filter(f -> "ACTIVE".equals(f.getRecordStatus())
                        && "JOB".equals(f.getFactType()) && !f.isChargeable()).toList()
                : factRepo.findByContainerNoAndRecordStatusOrderByStartBizTimeAscIdAsc(containerNo, "ACTIVE")
                        .stream().filter(f -> "JOB".equals(f.getFactType()) && !f.isChargeable()).toList();
        List<Map<String, Object>> excluded = new ArrayList<>();
        for (SemanticFact f : allActive) {
            excluded.add(factRow(f));
            excludedContainers.add(f.getContainerNo());
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("containerNo", containerNo);
        r.put("includedCount", lines.size());
        r.put("included", lines);
        r.put("excludedCount", excluded.size());
        r.put("excluded", excluded);
        r.put("excludedContainers", new ArrayList<>(excludedContainers));
        return r;
    }

    /** 作业溯源：事实 + 所使用的原始事件范围 + 关联冲突，解释为何纳入/排除。 */
    @Transactional(readOnly = true)
    public Map<String, Object> traceability(Long factId) {
        SemanticFact f = factRepo.findById(factId)
                .orElseThrow(() -> BizException.notFound("作业事实不存在: " + factId));
        List<Long> eventIds = readLongList(f.getSourceEventIds());
        List<Map<String, Object>> events = new ArrayList<>();
        for (IngestEvent e : eventRepo.findAllById(eventIds)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("eventId", e.getId());
            m.put("sourceSystem", e.getSourceSystem());
            m.put("sourceSeq", e.getSourceSeq());
            m.put("eventType", e.getEventType());
            m.put("opPartition", e.getOpPartition());
            m.put("bizTime", e.getBizTime());
            m.put("status", e.getStatus());
            m.put("commitId", e.getCommitId());
            m.put("payload", readJson(e.getPayload()));
            events.add(m);
        }
        events.sort((a, b) -> ((OffsetDateTime) a.get("bizTime")).compareTo((OffsetDateTime) b.get("bizTime")));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("fact", factRow(f));
        r.put("sourceEvents", events);
        r.put("decision", f.isChargeable() ? "INCLUDED" : "EXCLUDED");
        r.put("reason", f.isChargeable() ? f.getInferenceNote() : f.getExcludeReason());
        r.put("conflictIds", readLongList(f.getConflictIds()));
        return r;
    }

    /** 清单冻结前置校验：返回会阻塞冻结的高严重度冲突（不可能状态未裁定）。 */
    @Transactional(readOnly = true)
    public List<SemanticConflict> blockingConflicts(List<String> containerNos) {
        List<SemanticConflict> blocking = new ArrayList<>();
        for (String c : containerNos) {
            for (SemanticConflict cft : conflictRepo
                    .findByContainerNoAndRecordStatusOrderByCreatedAtAsc(c, "ACTIVE")) {
                if ("HIGH".equals(cft.getSeverity())) {
                    blocking.add(cft);
                }
            }
        }
        return blocking;
    }

    /** 清单冻结时固化航次作业快照（与费用快照分开保存）。 */
    @Transactional
    public Map<String, Object> freezeSnapshot(Long manifestId, String voyageNo, List<String> containerNos) {
        snapshotRepo.findByManifestId(manifestId).ifPresent(s -> {
            throw BizException.conflict("清单 " + manifestId + " 的作业快照已存在，不可重复冻结");
        });
        Map<String, Object> frontier = new LinkedHashMap<>();
        int factCount = 0;
        int conflictCount = 0;
        List<ManifestJobSnapshotItem> items = new ArrayList<>();

        ManifestJobSnapshot snapshot = new ManifestJobSnapshot();
        snapshot.setManifestId(manifestId);
        snapshot.setVoyageNo(voyageNo);
        for (String c : containerNos) {
            SemanticCompileRun run = runRepo.findTopByContainerNoOrderByRunSeqDesc(c).orElse(null);
            if (run == null) {
                throw BizException.conflict("箱号 " + c + " 尚未完成语义编译，无法固化航次作业快照");
            }
            frontier.put(c, run.getId());
            List<SemanticFact> facts = factRepo
                    .findByContainerNoAndRecordStatusOrderByStartBizTimeAscIdAsc(c, "ACTIVE");
            List<SemanticConflict> conflicts = conflictRepo
                    .findByContainerNoAndRecordStatusOrderByCreatedAtAsc(c, "ACTIVE");
            factCount += facts.size();
            conflictCount += conflicts.size();
            for (SemanticFact f : facts) {
                ManifestJobSnapshotItem item = new ManifestJobSnapshotItem();
                item.setSnapshotId(snapshot.getId()); // 保存后回填
                item.setContainerNo(c);
                item.setJobNo(f.getJobNo());
                item.setFactType(f.getFactType());
                item.setStatus(f.getStatus().name());
                item.setConfidence(f.getConfidence().name());
                item.setDeviceId(f.getDeviceId());
                item.setFromLocation(f.getFromLocation());
                item.setToLocation(f.getToLocation());
                item.setChargeable(f.isChargeable());
                item.setExcludeReason(f.getExcludeReason());
                item.setFactId(f.getId());
                item.setSourceEventIds(f.getSourceEventIds());
                items.add(item);
            }
        }
        snapshot.setRunFrontier(writeJson(frontier));
        snapshot.setFactCount(factCount);
        snapshot.setConflictCount(conflictCount);
        ManifestJobSnapshot saved = snapshotRepo.save(snapshot);
        for (ManifestJobSnapshotItem item : items) {
            item.setSnapshotId(saved.getId());
        }
        snapshotItemRepo.saveAll(items);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("snapshotId", saved.getId());
        r.put("manifestId", manifestId);
        r.put("voyageNo", voyageNo);
        r.put("runFrontier", frontier);
        r.put("factCount", factCount);
        r.put("conflictCount", conflictCount);
        r.put("itemCount", items.size());
        return r;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> snapshotDetail(Long manifestId) {
        ManifestJobSnapshot snapshot = snapshotRepo.findByManifestId(manifestId)
                .orElseThrow(() -> BizException.notFound("清单 " + manifestId + " 无航次作业快照"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (ManifestJobSnapshotItem item : snapshotItemRepo
                .findBySnapshotIdOrderByContainerNoAscIdAsc(snapshot.getId())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.getId());
            row.put("snapshotId", item.getSnapshotId());
            row.put("containerNo", item.getContainerNo());
            row.put("jobNo", item.getJobNo());
            row.put("factType", item.getFactType());
            row.put("status", item.getStatus());
            row.put("confidence", item.getConfidence());
            row.put("deviceId", item.getDeviceId());
            row.put("fromLocation", item.getFromLocation());
            row.put("toLocation", item.getToLocation());
            row.put("chargeable", item.isChargeable());
            row.put("excludeReason", item.getExcludeReason());
            row.put("factId", item.getFactId());
            row.put("sourceEventIds", readJson(item.getSourceEventIds()));
            items.add(row);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("snapshotId", snapshot.getId());
        r.put("manifestId", snapshot.getManifestId());
        r.put("voyageNo", snapshot.getVoyageNo());
        r.put("runFrontier", readJson(snapshot.getRunFrontier()));
        r.put("factCount", snapshot.getFactCount());
        r.put("conflictCount", snapshot.getConflictCount());
        r.put("createdAt", snapshot.getCreatedAt());
        r.put("items", items);
        return r;
    }

    private Map<String, Object> factRow(SemanticFact f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("containerNo", f.getContainerNo());
        m.put("factType", f.getFactType());
        m.put("jobNo", f.getJobNo());
        m.put("status", f.getStatus());
        m.put("confidence", f.getConfidence());
        m.put("deviceType", f.getDeviceType());
        m.put("deviceId", f.getDeviceId());
        m.put("phaseState", readJson(f.getPhaseState()));
        m.put("sourceEventIds", readLongList(f.getSourceEventIds()));
        m.put("startBizTime", f.getStartBizTime());
        m.put("endBizTime", f.getEndBizTime());
        m.put("fromLocation", f.getFromLocation());
        m.put("toLocation", f.getToLocation());
        m.put("chargeable", f.isChargeable());
        m.put("excludeReason", f.getExcludeReason());
        m.put("conflictIds", readLongList(f.getConflictIds()));
        m.put("inferenceNote", f.getInferenceNote());
        m.put("version", f.getVersion());
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<Long> readLongList(String json) {
        try {
            List<Number> m = om.readValue(json, List.class);
            return m.stream().map(Number::longValue).toList();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Object readJson(String json) {
        try {
            return om.readValue(json, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String writeJson(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
