package com.portops.lakehouse;

import com.portops.lakehouse.common.EventType;
import com.portops.lakehouse.common.SourceSystem;
import com.portops.lakehouse.ingest.EventIngestRequest;
import com.portops.lakehouse.ingest.IngestService;
import com.portops.lakehouse.commit.CommitService;
import com.portops.lakehouse.semantic.ConfidenceLevel;
import com.portops.lakehouse.semantic.FactStatus;
import com.portops.lakehouse.semantic.SemanticCompileService;
import com.portops.lakehouse.semantic.SemanticConflict;
import com.portops.lakehouse.semantic.SemanticFact;
import com.portops.lakehouse.semantic.SemanticFactRepository;
import com.portops.lakehouse.semantic.SemanticConflictRepository;
import com.portops.lakehouse.semantic.SemanticQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 作业语义编译与状态一致性检查的端到端集成测试，覆盖五类验收样例：
 * 完整吊运、缺失落箱、离线补传、并行设备冲突、跨堆位不可能轨迹与晚到吊起防倒退。
 */
class SemanticCompilationFlowTest extends BaseIntegrationTest {

    @Autowired
    private IngestService ingestService;
    @Autowired
    private CommitService commitService;
    @Autowired
    private SemanticCompileService compileService;
    @Autowired
    private SemanticQueryService queryService;
    @Autowired
    private SemanticFactRepository factRepo;
    @Autowired
    private SemanticConflictRepository conflictRepo;

    private EventIngestRequest crane(long seq, String container, String partition, String bizTime,
                                     String action, String from, String to, String crane) {
        String payload = "{\"action\":\"" + action + "\",\"fromBlock\":\"" + from.split("-")[0]
                + "\",\"fromSlot\":\"" + from.substring(from.indexOf('-') + 1)
                + "\",\"toBlock\":\"" + to.split("-")[0]
                + "\",\"toSlot\":\"" + to.substring(to.indexOf('-') + 1)
                + "\",\"crane\":\"" + crane + "\"}";
        return ev(SourceSystem.CRANE, seq, container, partition, EventType.CRANE_MOVE,
                bizTime, "V2026-11B", payload);
    }

    private void ingestAndCommit(EventIngestRequest... reqs) {
        for (EventIngestRequest r : reqs) {
            ingestService.ingestOne(r);
        }
        // 逐箱调用事务性 generate，与 CommitController.generateAll 的语义一致
        for (String containerNo : commitService.listPendingContainers()) {
            commitService.generate(containerNo);
        }
    }

    private boolean hasConflict(Map<String, Object> view, String type) {
        @SuppressWarnings("unchecked")
        List<SemanticConflict> conflicts = (List<SemanticConflict>) view.get("conflicts");
        return conflicts.stream().anyMatch(c -> type.equals(c.getConflictType()));
    }

    @Test
    void completeChain_isConfirmedAndChargeable() {
        ingestAndCommit(
                crane(201, "BOX1", "YARD-A", "2026-11-21T09:00:00+08:00", "HOIST", "STAGING-S1", "STAGING-S1", "QC-01"),
                crane(202, "BOX1", "YARD-A", "2026-11-21T09:06:00+08:00", "TRAVEL", "STAGING-S1", "A-01-01", "QC-01"),
                crane(203, "BOX1", "YARD-A", "2026-11-21T09:12:00+08:00", "DROP", "A-01-01", "A-01-01", "QC-01"));
        compileService.compileAll("test");

        Map<String, Object> view = queryService.containerView("BOX1");
        assertEquals(1, ((List<?>) view.get("availableFacts")).size());
        assertEquals(0, ((List<?>) view.get("inferredFacts")).size());
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) view.get("stateFact");
        @SuppressWarnings("unchecked")
        Map<String, Object> phase = (Map<String, Object>) state.get("phaseState");
        assertEquals("LANDED", phase.get("semanticState"));

        Map<String, Object> charges = queryService.chargeableSummary("BOX1");
        assertEquals(1, charges.get("includedCount"));
        assertEquals(0, charges.get("excludedCount"));
    }

    @Test
    void missingDrop_isInFlightInferenceAndNotChargeable() {
        ingestAndCommit(
                crane(211, "BOX2", "YARD-A", "2026-11-21T10:00:00+08:00", "HOIST", "A-02-02", "A-02-02", "QC-02"),
                crane(212, "BOX2", "YARD-A", "2026-11-21T10:05:00+08:00", "TRAVEL", "A-02-02", "A-03-03", "QC-02"));
        compileService.compileAll("test");

        Map<String, Object> view = queryService.containerView("BOX2");
        assertEquals(0, ((List<?>) view.get("availableFacts")).size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inferred = (List<Map<String, Object>>) view.get("inferredFacts");
        assertEquals(1, inferred.size());
        assertEquals(FactStatus.IN_FLIGHT, inferred.get(0).get("status"));
        assertEquals(ConfidenceLevel.MEDIUM, inferred.get(0).get("confidence"));
        assertNotNull(inferred.get(0).get("inferenceNote"));
        assertTrue(String.valueOf(inferred.get(0).get("inferenceNote")).contains("撤销规则"));
        assertEquals(0, queryService.chargeableSummary("BOX2").get("includedCount"));
    }

    @Test
    void backfilledDrop_closesJobWithNewVersionAndDowngradesConfidence() {
        ingestAndCommit(
                crane(221, "BOX3", "YARD-A", "2026-11-21T10:00:00+08:00", "HOIST", "A-02-02", "A-02-02", "QC-02"),
                crane(222, "BOX3", "YARD-A", "2026-11-21T10:05:00+08:00", "TRAVEL", "A-02-02", "A-03-03", "QC-02"));
        compileService.compileAll("test");
        assertEquals(1L, ((Number) queryService.containerView("BOX3").get("version")).longValue());

        // 离线 45 分钟后补传落下（超过 30 分钟阶段容忍窗）
        ingestService.ingestOne(crane(223, "BOX3", "YARD-A", "2026-11-21T10:50:00+08:00",
                "DROP", "A-03-03", "A-03-03", "QC-02"));
        commitService.listPendingContainers().forEach(commitService::generate);
        compileService.compileAll("test");

        Map<String, Object> view = queryService.containerView("BOX3");
        assertEquals(2L, ((Number) view.get("version")).longValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inferred = (List<Map<String, Object>>) view.get("inferredFacts");
        assertEquals(1, inferred.size());
        assertEquals(FactStatus.COMPLETED, inferred.get(0).get("status"));
        assertEquals(ConfidenceLevel.HIGH, inferred.get(0).get("confidence")); // 超容忍窗，不能给 CONFIRMED
        // 旧版本事实保留且已被取代
        long superseded = factRepo.findByContainerNoOrderByVersionAscIdAsc("BOX3").stream()
                .filter(f -> "SUPERSEDED".equals(f.getRecordStatus())).count();
        assertTrue(superseded > 0);
        assertEquals(1, queryService.chargeableSummary("BOX3").get("includedCount"));
    }

    @Test
    void sameDeviceOverlap_marksBothContainersImpossibleAndBlocksCharges() {
        ingestAndCommit(
                crane(231, "BOX4", "YARD-B", "2026-11-21T11:00:00+08:00", "HOIST", "STAGING-S4", "STAGING-S4", "QC-03"),
                crane(232, "BOX4", "YARD-B", "2026-11-21T11:20:00+08:00", "DROP", "B-01-01", "B-01-01", "QC-03"),
                crane(241, "BOX5", "YARD-B", "2026-11-21T11:10:00+08:00", "HOIST", "STAGING-S5", "STAGING-S5", "QC-03"),
                crane(242, "BOX5", "YARD-B", "2026-11-21T11:15:00+08:00", "DROP", "B-02-02", "B-02-02", "QC-03"));
        compileService.compileAll("test");

        for (String c : List.of("BOX4", "BOX5")) {
            Map<String, Object> view = queryService.containerView(c);
            assertTrue(hasConflict(view, "DEVICE_OVERLAP"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pending = (List<Map<String, Object>>) view.get("pendingAdjudication");
            assertTrue(pending.stream().anyMatch(x -> FactStatus.IMPOSSIBLE == x.get("status")));
            assertEquals(0, queryService.chargeableSummary(c).get("includedCount"));
        }
    }

    @Test
    void dualSlotDropsAndImpossibleTrajectory_generateConflicts() {
        ingestAndCommit(
                // 互斥堆位同时落箱
                crane(251, "BOX6", "YARD-A", "2026-11-21T14:00:00+08:00", "HOIST", "STAGING-S7", "STAGING-S7", "QC-05"),
                crane(252, "BOX6", "YARD-A", "2026-11-21T14:10:00+08:00", "DROP", "A-01-01", "A-01-01", "QC-05"),
                crane(253, "BOX6", "YARD-B", "2026-11-21T14:10:00+08:00", "DROP", "B-02-02", "B-02-02", "QC-05"),
                // 无新吊起的跨堆位落箱
                crane(261, "BOX7", "YARD-A", "2026-11-21T13:00:00+08:00", "HOIST", "STAGING-S6", "STAGING-S6", "QC-04"),
                crane(262, "BOX7", "YARD-A", "2026-11-21T13:10:00+08:00", "DROP", "A-01-01", "A-01-01", "QC-04"),
                crane(263, "BOX7", "YARD-B", "2026-11-21T13:25:00+08:00", "DROP", "B-09-09", "B-09-09", "QC-04"));
        compileService.compileAll("test");

        for (String c : List.of("BOX6", "BOX7")) {
            Map<String, Object> view = queryService.containerView(c);
            assertTrue(hasConflict(view, "DUAL_SLOT_DROP"), c + " 应产生 DUAL_SLOT_DROP 冲突");
            assertEquals(0, queryService.chargeableSummary(c).get("includedCount"));
        }
    }

    @Test
    void lateHoistCannotRegressConfirmedLanding() {
        ingestAndCommit(
                crane(271, "BOX8", "YARD-A", "2026-11-21T15:00:00+08:00", "HOIST", "STAGING-S8", "STAGING-S8", "QC-06"),
                crane(272, "BOX8", "YARD-A", "2026-11-21T15:05:00+08:00", "TRAVEL", "STAGING-S8", "A-05-05", "QC-06"),
                crane(273, "BOX8", "YARD-A", "2026-11-21T15:10:00+08:00", "DROP", "A-05-05", "A-05-05", "QC-06"));
        compileService.compileAll("test");

        // 晚到的吊起，无落箱
        ingestService.ingestOne(crane(274, "BOX8", "YARD-A", "2026-11-21T16:00:00+08:00",
                "HOIST", "A-05-05", "A-05-05", "QC-06"));
        commitService.listPendingContainers().forEach(commitService::generate);
        compileService.compileAll("test");

        Map<String, Object> view = queryService.containerView("BOX8");
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) view.get("stateFact");
        @SuppressWarnings("unchecked")
        Map<String, Object> phase = (Map<String, Object>) state.get("phaseState");
        assertEquals("LANDED", phase.get("semanticState")); // 未倒退为 LIFTING
        assertEquals(ConfidenceLevel.CONFIRMED, state.get("confidence"));
        assertTrue(hasConflict(view, "NO_REGRESSION_GUARD"));
        // 已证实落箱作业仍可收费，晚到吊起的未闭合作业不可收费
        Map<String, Object> charges = queryService.chargeableSummary("BOX8");
        assertEquals(1, charges.get("includedCount"));
        assertTrue(((List<?>) charges.get("excluded")).size() >= 1);
    }

    @Test
    void factsKeepSourceEventScopeAndHistoryIsVersioned() {
        ingestAndCommit(
                crane(281, "BOX9", "YARD-A", "2026-11-21T09:00:00+08:00", "HOIST", "STAGING-S1", "STAGING-S1", "QC-09"),
                crane(282, "BOX9", "YARD-A", "2026-11-21T09:08:00+08:00", "DROP", "A-09-09", "A-09-09", "QC-09"));
        compileService.compileAll("test");
        compileService.compileAll("test"); // 重复编译产生新版本

        Long factId = factRepo.findByContainerNoAndRecordStatusOrderByStartBizTimeAscIdAsc("BOX9", "ACTIVE").stream()
                .filter(f -> "JOB".equals(f.getFactType()))
                .findFirst().orElseThrow().getId();
        Map<String, Object> trace = queryService.traceability(factId);
        assertEquals("INCLUDED", trace.get("decision"));
        assertEquals(2, ((List<?>) trace.get("sourceEvents")).size());

        List<SemanticFact> history = factRepo.findByContainerNoOrderByVersionAscIdAsc("BOX9");
        assertTrue(history.size() >= 4); // 两个版本 × (作业事实 + 状态事实)
        assertTrue(history.stream().anyMatch(f -> "SUPERSEDED".equals(f.getRecordStatus())));
        assertEquals(0, conflictRepo.findByContainerNoAndRecordStatusOrderByCreatedAtAsc("BOX9", "ACTIVE").size());
    }

    @Test
    void phaseOrderViolationIsImpossible() {
        ingestAndCommit(
                // 没有 HOIST 直接 DROP：阶段顺序违反
                crane(291, "BOX10", "YARD-A", "2026-11-21T17:00:00+08:00", "DROP", "A-01-01", "A-01-01", "QC-10"));
        compileService.compileAll("test");

        Map<String, Object> view = queryService.containerView("BOX10");
        assertTrue(hasConflict(view, "PHASE_ORDER"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pending = (List<Map<String, Object>>) view.get("pendingAdjudication");
        assertTrue(pending.stream().anyMatch(x -> FactStatus.IMPOSSIBLE == x.get("status")));
        assertEquals(0, queryService.chargeableSummary("BOX10").get("includedCount"));
    }
}
