package com.portops.lakehouse.semantic;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 作业语义编译接口：编译触发、事实/冲突查询、调度看板、可收费汇总与作业溯源。
 */
@RestController
@RequestMapping("/api/semantic")
@Tag(name = "07-作业语义编译", description = "来源事件→作业事实的语义编译、置信等级、冲突检测与版本化修正")
public class SemanticController {

    private final SemanticCompileService compileService;
    private final SemanticQueryService queryService;
    private final SemanticFactRepository factRepo;
    private final SemanticConflictRepository conflictRepo;
    private final SemanticCompileRunRepository runRepo;
    private final SemanticRuleCatalog ruleCatalog;

    public SemanticController(SemanticCompileService compileService,
                              SemanticQueryService queryService,
                              SemanticFactRepository factRepo,
                              SemanticConflictRepository conflictRepo,
                              SemanticCompileRunRepository runRepo,
                              SemanticRuleCatalog ruleCatalog) {
        this.compileService = compileService;
        this.queryService = queryService;
        this.factRepo = factRepo;
        this.conflictRepo = conflictRepo;
        this.runRepo = runRepo;
        this.ruleCatalog = ruleCatalog;
    }

    @PostMapping("/compile")
    @Operation(summary = "编译指定箱号（生成新解释版本）",
            description = "只读取已提交事件；发现跨箱设备重叠时连带重编译对方箱号；旧版本仅标记 SUPERSEDED")
    public Map<String, Object> compile(@RequestParam String containerNo, Authentication auth) {
        return compileService.compileContainer(containerNo, auth == null ? "anonymous" : auth.getName());
    }

    @PostMapping("/compile-all")
    @Operation(summary = "全量编译所有已提交箱号")
    public List<Map<String, Object>> compileAll(Authentication auth) {
        return compileService.compileAll(auth == null ? "anonymous" : auth.getName());
    }

    @GetMapping("/containers/{containerNo}/view")
    @Operation(summary = "单箱语义视图",
            description = "三层分区：availableFacts 当前可用（已证实）/ inferredFacts 推断（含置信等级与撤销规则）/ pendingAdjudication 待裁定")
    public Map<String, Object> view(@PathVariable String containerNo) {
        return queryService.containerView(containerNo);
    }

    @GetMapping("/dispatch-board")
    @Operation(summary = "调度看板：全部箱号的可用/推断/待裁定分区")
    public List<Map<String, Object>> board() {
        return queryService.dispatchBoard();
    }

    @GetMapping("/facts")
    @Operation(summary = "查询作业事实（当前 ACTIVE 版本）")
    public List<SemanticFact> facts(@RequestParam(required = false) String containerNo) {
        if (containerNo == null || containerNo.isBlank()) {
            return factRepo.findAll().stream()
                    .filter(f -> "ACTIVE".equals(f.getRecordStatus()))
                    .toList();
        }
        return factRepo.findByContainerNoAndRecordStatusOrderByStartBizTimeAscIdAsc(containerNo, "ACTIVE");
    }

    @GetMapping("/facts/history")
    @Operation(summary = "查询事实的全部解释版本（含已 SUPERSEDED）",
            description = "修正只生成新版本：此处可核对每次解释变更与撤销轨迹")
    public List<SemanticFact> history(@RequestParam String containerNo) {
        return factRepo.findByContainerNoOrderByVersionAscIdAsc(containerNo);
    }

    @GetMapping("/facts/{factId}/traceability")
    @Operation(summary = "作业溯源：事实使用的原始事件范围与纳入/排除原因")
    public Map<String, Object> traceability(@PathVariable Long factId) {
        return queryService.traceability(factId);
    }

    @GetMapping("/conflicts")
    @Operation(summary = "查询语义冲突事实", description = "status=ACTIVE 查看当前冲突；类型含 DEVICE_OVERLAP/DUAL_SLOT_DROP/PHASE_ORDER/NO_REGRESSION_GUARD")
    public List<SemanticConflict> conflicts(@RequestParam(defaultValue = "ACTIVE") String status) {
        return conflictRepo.findByRecordStatusOrderByContainerNoAscIdAsc(status);
    }

    @GetMapping("/chargeable")
    @Operation(summary = "可收费作业汇总",
            description = "仅纳入 chargeable=true 的 ACTIVE 事实；被冲突阻止/未闭合/中断的作业列入 excluded 并给出原因")
    public Map<String, Object> chargeable(@RequestParam(required = false) String containerNo) {
        return queryService.chargeableSummary(containerNo);
    }

    @GetMapping("/runs")
    @Operation(summary = "查询编译运行版本（事件水位与规则快照）")
    public List<SemanticCompileRun> runs(@RequestParam String containerNo) {
        return runRepo.findByContainerNoOrderByRunSeqAsc(containerNo);
    }

    @GetMapping("/rules")
    @Operation(summary = "查看按设备类型定义的阶段、允许转换、最小必要证据与容忍间隔")
    public Map<String, SemanticRuleCatalog.SemanticRule> rules() {
        return ruleCatalog.loadAll();
    }

    @GetMapping("/snapshots/{manifestId}")
    @Operation(summary = "查询航次作业快照（冻结时刻固化的解释版本边界）")
    public Map<String, Object> snapshot(@PathVariable Long manifestId) {
        return queryService.snapshotDetail(manifestId);
    }
}
