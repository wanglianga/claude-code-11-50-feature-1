package com.portops.lakehouse.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portops.lakehouse.common.EventType;
import com.portops.lakehouse.common.SourceSystem;
import com.portops.lakehouse.ingest.IngestEvent;
import com.portops.lakehouse.ingest.IngestEventRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 作业语义编译服务：在不可变事件账与完整提交之上，把来源事件编译成可解释作业事实。
 *
 * <p>编译边界：只读取 {@code COMMITTED} 事件（已经过提交层排序与冲突裁定），
 * 语义层独立保存自己的状态，不回写事件账与提交。</p>
 *
 * <p>核心约定：</p>
 * <ul>
 *   <li>每次编译产出一个新的解释版本（run_seq 单调递增）；旧事实/冲突只允许
 *       ACTIVE→SUPERSEDED，内容不改写、不删除（数据库触发器兜底）；</li>
 *   <li>场桥按 HOIST→TRAVEL→DROP 严格链分组：完整链确认 COMPLETED/CONFIRMED；
 *       缺落箱且在容忍窗内为 IN_FLIGHT/MEDIUM 推断，超窗为 INTERRUPTED/LOW；
 *       离线补传闭合但阶段间隔超容忍值的，降级 HIGH 并标注撤销规则；</li>
 *   <li>推断只能被后续证据撤销/升级，CONFIRMED 事实不会被晚到的单条事件倒退；</li>
 *   <li>同设备重叠操作两只箱、互斥堆位同时落箱、阶段顺序违反均生成冲突事实，
 *       相关作业标记不可收费，绝不从中任选一条。</li>
 * </ul>
 */
@Service
public class SemanticCompileService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCompileService.class);

    public static final String ACTIVE = "ACTIVE";

    private final IngestEventRepository eventRepo;
    private final SemanticCompileRunRepository runRepo;
    private final SemanticFactRepository factRepo;
    private final SemanticConflictRepository conflictRepo;
    private final SemanticRuleCatalog ruleCatalog;
    private final EntityManager em;
    private final ObjectMapper om;

    public SemanticCompileService(IngestEventRepository eventRepo,
                                  SemanticCompileRunRepository runRepo,
                                  SemanticFactRepository factRepo,
                                  SemanticConflictRepository conflictRepo,
                                  SemanticRuleCatalog ruleCatalog,
                                  EntityManager em,
                                  ObjectMapper om) {
        this.eventRepo = eventRepo;
        this.runRepo = runRepo;
        this.factRepo = factRepo;
        this.conflictRepo = conflictRepo;
        this.ruleCatalog = ruleCatalog;
        this.em = em;
        this.om = om;
    }

    /** 单箱编译（手动触发）。若发现与其他箱的设备重叠，连带重编译对方箱号。 */
    @Transactional
    public Map<String, Object> compileContainer(String containerNo, String operator) {
        Map<String, Object> summary = doCompile(containerNo, "MANUAL", operator);
        @SuppressWarnings("unchecked")
        List<String> counterparts = (List<String>) summary.getOrDefault("overlapCounterparts", List.of());
        List<Map<String, Object>> extra = new ArrayList<>();
        for (String other : counterparts) {
            extra.add(doCompile(other, "MANUAL", operator));
        }
        summary.put("counterpartRuns", extra);
        return summary;
    }

    /** 全量编译：跨箱设备重叠在同一轮版本中对双方留痕。 */
    @Transactional
    public List<Map<String, Object>> compileAll(String operator) {
        List<String> containers = eventRepo.findCommittedContainers();
        List<Map<String, Object>> out = new ArrayList<>();
        for (String c : containers) {
            out.add(doCompile(c, "ALL", operator));
        }
        return out;
    }

    /**
     * 冻结前置：确保每个箱号至少存在一个语义解释版本。
     * 已编译过的箱号不重复生成（新版本只应在事件变化后由显式编译产生）。
     */
    @Transactional
    public void ensureCompiled(List<String> containerNos, String operator) {
        for (String c : containerNos) {
            if (runRepo.findTopByContainerNoOrderByRunSeqDesc(c).isEmpty()) {
                compileContainer(c, operator);
            }
        }
    }

    // ============================ 编译主流程 ============================

    private Map<String, Object> doCompile(String containerNo, String triggerMode, String operator) {
        em.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(?1))")
                .setParameter(1, "semantic|" + containerNo)
                .getSingleResult();

        List<IngestEvent> events = eventRepo.findCommitted(containerNo);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("containerNo", containerNo);
        if (events.isEmpty()) {
            result.put("result", "NO_COMMITTED_EVENTS");
            return result;
        }

        Map<String, SemanticRuleCatalog.SemanticRule> rules = ruleCatalog.loadAll();
        SemanticRuleCatalog.SemanticRule craneRule = rules.get("CRANE");

        long runSeq = runRepo.findTopByContainerNoOrderByRunSeqDesc(containerNo)
                .map(r -> r.getRunSeq() + 1).orElse(1L);
        int version = (int) runSeq;

        SemanticCompileRun run = new SemanticCompileRun();
        run.setContainerNo(containerNo);
        run.setRunSeq(runSeq);
        run.setTriggerMode(triggerMode);
        run.setRuleSnapshot(ruleCatalog.snapshot());
        run.setEventFrontier(writeJson(buildFrontier(events)));
        run.setCreatedBy(operator);
        run = runRepo.save(run);

        List<SemanticConflict> conflicts = new ArrayList<>();
        List<SemanticFact> facts = new ArrayList<>();

        // ---------- 1. 权威单点事实（闸口/盘点/装船）；场桥事件区分显式动作与历史报文 ----------
        List<IngestEvent> craneEvents = new ArrayList<>();
        for (IngestEvent e : events) {
            if (e.getSourceSystem() == SourceSystem.CRANE && e.getEventType() == EventType.CRANE_MOVE) {
                craneEvents.add(e);
            } else if (e.getEventType() != EventType.CORRECTION) {
                facts.add(buildAuthoritativeFact(e, run.getId(), version));
            }
        }
        List<IngestEvent> explicitCraneEvents = new ArrayList<>();
        for (IngestEvent e : craneEvents) {
            JsonNode p = SemanticEventInterpreter.payload(e, om);
            if (SemanticEventInterpreter.isExplicitAction(e, p)) {
                explicitCraneEvents.add(e);
            } else {
                // 未拆分三阶段的历史报文：作为移动过程证据保留，不臆造吊起/落下、不触发阶段冲突
                facts.add(buildLegacyMoveFact(e, run.getId(), version));
            }
        }

        // ---------- 2. 显式场桥动作 → 逻辑作业链 → 作业事实草稿 ----------
        List<Chain> chains = groupChains(explicitCraneEvents);
        List<PlannedJob> jobs = new ArrayList<>();
        for (Chain chain : chains) {
            jobs.add(evaluateChain(chain, craneRule, run.getId(), version, containerNo, conflicts));
        }

        // ---------- 3. 同箱冲突：互斥堆位同时落箱 / 跨堆位不可能轨迹（基于原始 DROP 证据） ----------
        detectDualSlotDrops(explicitCraneEvents, jobs, containerNo, run.getId(), version, conflicts);

        // ---------- 4. 跨箱冲突：同一设备时间重叠操作两只箱 ----------
        Set<String> counterparts = detectDeviceOverlaps(containerNo, jobs, run.getId(), version, conflicts);

        // ---------- 5. 箱子当前语义状态事实（含防倒退保护） ----------
        facts.add(buildStateFact(containerNo, events, jobs, facts, run.getId(), version, conflicts));

        // ---------- 6. 版本更替：旧 ACTIVE 标 SUPERSEDED → 先存冲突取得 id → 再存事实 ----------
        factRepo.supersedeActive(containerNo, runSeq);
        conflictRepo.supersedeActive(containerNo, runSeq);
        for (SemanticConflict c : conflicts) {
            conflictRepo.save(c);
        }
        for (PlannedJob job : jobs) {
            facts.add(job.toFact(containerNo, run.getId(), version, om, conflicts));
        }
        for (SemanticFact f : facts) {
            factRepo.save(f);
        }

        run.setFactCount(facts.size());
        run.setConflictCount(conflicts.size());
        runRepo.save(run);

        result.put("result", "OK");
        result.put("runId", run.getId());
        result.put("version", version);
        result.put("factCount", facts.size());
        result.put("conflictCount", conflicts.size());
        result.put("chargeableJobCount",
                facts.stream().filter(f -> "JOB".equals(f.getFactType()) && f.isChargeable()).count());
        result.put("overlapCounterparts", new ArrayList<>(counterparts));
        log.info("semantic compiled: container={} version={} facts={} conflicts={}",
                containerNo, version, facts.size(), conflicts.size());
        return result;
    }

    // ============================ 链分组 ============================

    /** 按业务顺序把场桥动作事件切成逻辑作业链：新 HOIST 开启新链，DROP 闭合。 */
    private List<Chain> groupChains(List<IngestEvent> craneEvents) {
        List<Chain> chains = new ArrayList<>();
        Chain open = null;
        for (IngestEvent e : craneEvents.stream()
                .sorted(Comparator.comparing(IngestEvent::getBizTime)
                        .thenComparing(x -> x.getSourceSystem().ordinal())
                        .thenComparing(IngestEvent::getSourceSeq))
                .toList()) {
            JsonNode p = SemanticEventInterpreter.payload(e, om);
            CraneEv cv = new CraneEv(e, p, SemanticEventInterpreter.phaseAction(e, p),
                    SemanticEventInterpreter.deviceId(e, p),
                    SemanticEventInterpreter.fromLocation(p),
                    SemanticEventInterpreter.toLocation(p));
            if (SemanticEventInterpreter.ACTION_HOIST.equals(cv.action())) {
                if (open != null) {
                    chains.add(open); // 未落下即出现新吊起：旧链留待中断判定
                }
                open = new Chain();
                open.events.add(cv);
            } else {
                if (open == null) {
                    open = new Chain(); // 孤儿 TRAVEL/DROP：缺少吊起，后续判 PHASE_ORDER
                }
                open.events.add(cv);
                if (SemanticEventInterpreter.ACTION_DROP.equals(cv.action())) {
                    chains.add(open);
                    open = null;
                }
            }
        }
        if (open != null) {
            chains.add(open);
        }
        return chains;
    }

    // ============================ 单链评估 ============================

    private PlannedJob evaluateChain(Chain chain, SemanticRuleCatalog.SemanticRule rule,
                                     Long runId, int version, String containerNo,
                                     List<SemanticConflict> conflicts) {
        List<CraneEv> evs = chain.events;
        CraneEv first = evs.get(0);
        CraneEv last = evs.get(evs.size() - 1);
        boolean hasHoist = evs.stream().anyMatch(c -> SemanticEventInterpreter.ACTION_HOIST.equals(c.action()));
        boolean hasDrop = evs.stream().anyMatch(c -> SemanticEventInterpreter.ACTION_DROP.equals(c.action()));
        long maxGap = rule.maxPhaseGapSeconds();
        long maxOpen = rule.maxJobOpenSeconds();

        PlannedJob job = new PlannedJob();
        job.jobNo = containerNo + "-JOB-" + first.event().getSourceSeq() + "-" + first.event().getId();
        job.deviceType = "CRANE";
        job.deviceId = first.deviceId();
        job.phaseState = buildPhaseState(evs);
        job.eventIds = evs.stream().map(c -> c.event().getId()).sorted().toList();
        job.firstEventId = first.event().getId();
        job.lastEventId = last.event().getId();
        job.startBizTime = first.event().getBizTime();
        job.endBizTime = hasDrop ? last.event().getBizTime() : null;
        job.fromLocation = first.from();
        job.toLocation = dropLocation(evs, last);

        // 规则一：阶段顺序违反（允许转换之外的动作序列，或缺吊起的落下）
        List<String> orderViolations = checkPhaseOrder(evs, rule);
        if (!hasHoist) {
            orderViolations.add("缺少吊起(HOIST)证据即出现 " + last.action() + "，作业链无起点");
        }
        if (!orderViolations.isEmpty()) {
            job.status = FactStatus.IMPOSSIBLE;
            job.confidence = ConfidenceLevel.LOW;
            job.chargeable = false;
            job.excludeReason = "阶段顺序违反: " + String.join("; ", orderViolations);
            job.inferenceNote = "不可能状态：证据链违反设备阶段规则，禁止进入可收费作业汇总，等待人工裁定";
            SemanticConflict orderConflict = conflict(containerNo, "PHASE_ORDER", "HIGH",
                    "作业 " + job.jobNo + " 阶段顺序违反: " + String.join("; ", orderViolations),
                    job.eventIds, runId, version);
            conflicts.add(orderConflict);
            job.conflictDrafts.add(orderConflict);
            return job;
        }

        // 阶段间隔（离线补传判定）
        long worstGap = worstGapSeconds(evs);
        boolean gapBeyondTolerance = worstGap > maxGap;

        if (hasHoist && hasDrop) {
            // 完整链：确认作业；离线补传造成超容忍间隔则降为高置信推断
            job.status = FactStatus.COMPLETED;
            if (gapBeyondTolerance) {
                job.confidence = ConfidenceLevel.HIGH;
                job.inferenceNote = "完整吊运链已闭合，但阶段最大间隔 " + worstGap
                        + "s 超出可容忍值 " + maxGap + "s，按设备离线补传处理；"
                        + "撤销规则：若补传证据被裁定无效，本事实在新版本中降级为中断待核";
            } else {
                job.confidence = ConfidenceLevel.CONFIRMED;
                job.inferenceNote = "HOIST→TRAVEL→DROP 完整阶段链，最小必要证据齐备";
            }
            job.chargeable = true;
            return job;
        }

        // 有吊起无落下：在途推断 vs 中断待核
        OffsetDateTime anchor = last.event().getBizTime();
        long openSeconds = Duration.between(anchor, OffsetDateTime.now()).getSeconds();
        if (openSeconds <= maxOpen) {
            job.status = FactStatus.IN_FLIGHT;
            job.confidence = ConfidenceLevel.MEDIUM;
            job.chargeable = false;
            job.excludeReason = "缺失落下(DROP)证据，作业未闭合，暂不可收费";
            job.inferenceNote = "仅据吊起/移动部分证据推断正在吊运（置信 MEDIUM）；撤销规则：DROP 证据到达后在新版本升级为 "
                    + "COMPLETED/CONFIRMED；若 " + maxOpen + "s 内未闭合则转 INTERRUPTED/LOW；"
                    + "该推断不得倒退任何已证实的落箱/装船事实";
        } else {
            job.status = FactStatus.INTERRUPTED;
            job.confidence = ConfidenceLevel.LOW;
            job.chargeable = false;
            job.excludeReason = "缺失落下(DROP)证据且作业开放已达 " + openSeconds
                    + "s，超出可容忍开放时限 " + maxOpen + "s";
            job.inferenceNote = "中断待核：设备掉线或报文缺失，须人工核对后由新版本修正";
        }
        return job;
    }

    /** 校验动作序列是否都在允许转换内（重复同阶段动作视为过程重发，允许）。 */
    private List<String> checkPhaseOrder(List<CraneEv> evs, SemanticRuleCatalog.SemanticRule rule) {
        List<String> violations = new ArrayList<>();
        String prev = null;
        for (CraneEv cv : evs) {
            String a = cv.action();
            if (prev != null && !a.equals(prev)) {
                final String from = prev;
                boolean allowed = rule.allowedTransitions().stream()
                        .anyMatch(t -> t.get(0).equals(from) && t.get(1).equals(a));
                if (!allowed) {
                    violations.add(from + "→" + a + " 不在允许转换 " + rule.allowedTransitions() + " 内"
                            + "（事件 " + cv.event().getId() + "）");
                }
            }
            prev = a;
        }
        return violations;
    }

    // ============================ 同箱堆位冲突 ============================

    /**
     * 两条落箱证据互斥即冲突（基于原始 DROP 事件判定，不依赖链形状）：
     * 同时点不同堆位 = 互斥堆位同时落箱；不同时点但两次落箱之间无新吊起 = 跨堆位不可能轨迹。
     */
    private void detectDualSlotDrops(List<IngestEvent> explicitCraneEvents, List<PlannedJob> jobs,
                                     String containerNo, Long runId, int version,
                                     List<SemanticConflict> conflicts) {
        record DropEv(IngestEvent event, String location) {
        }
        List<DropEv> drops = new ArrayList<>();
        List<IngestEvent> hoists = new ArrayList<>();
        for (IngestEvent e : explicitCraneEvents) {
            JsonNode p = SemanticEventInterpreter.payload(e, om);
            String action = SemanticEventInterpreter.phaseAction(e, p);
            if (SemanticEventInterpreter.ACTION_DROP.equals(action)) {
                drops.add(new DropEv(e, SemanticEventInterpreter.toLocation(p)));
            } else if (SemanticEventInterpreter.ACTION_HOIST.equals(action)) {
                hoists.add(e);
            }
        }
        drops.sort(Comparator.comparing(d -> d.event().getBizTime()));
        for (int i = 0; i < drops.size(); i++) {
            for (int j = i + 1; j < drops.size(); j++) {
                DropEv a = drops.get(i);
                DropEv b = drops.get(j);
                if (a.location() == null || a.location().equals(b.location())) {
                    continue; // 同堆位重复落箱报告不互斥
                }
                long secs = Math.abs(Duration.between(a.event().getBizTime(), b.event().getBizTime()).getSeconds());
                String detail = null;
                if (secs == 0) {
                    detail = "同一箱在互斥堆位 " + a.location() + " 与 " + b.location()
                            + " 同时落箱，物理不可能";
                } else {
                    boolean hoistBetween = hoists.stream().anyMatch(h -> h.getBizTime()
                            .isAfter(a.event().getBizTime())
                            && !h.getBizTime().isAfter(b.event().getBizTime()));
                    if (!hoistBetween) {
                        detail = "同一箱已落位 " + a.location() + "，未经新的吊起(HOIST)即出现 "
                                + b.location() + " 落箱证据，跨堆位轨迹不可能";
                    }
                }
                if (detail != null) {
                    Set<Long> idSet = new LinkedHashSet<>();
                    idSet.add(a.event().getId());
                    idSet.add(b.event().getId());
                    SemanticConflict c = conflict(containerNo, "DUAL_SLOT_DROP", "HIGH", detail,
                            idSet.stream().sorted().toList(), runId, version);
                    conflicts.add(c);
                    final String conflictDetail = detail;
                    for (Long dropIdRaw : List.of(a.event().getId(), b.event().getId())) {
                        final Long dropId = dropIdRaw;
                        jobs.stream().filter(job -> job.eventIds.contains(dropId)).forEach(job -> {
                            job.status = FactStatus.IMPOSSIBLE;
                            job.confidence = ConfidenceLevel.LOW;
                            job.chargeable = false;
                            job.excludeReason = conflictDetail;
                            job.inferenceNote = "不可能状态：冲突事实 DUAL_SLOT_DROP 已阻止其进入可收费作业汇总，不作二选一";
                            job.conflictDrafts.add(c);
                        });
                    }
                }
            }
        }
    }

    // ============================ 跨箱设备重叠 ============================

    /**
     * 同设备对两只箱的吊运区间重叠 → DEVICE_OVERLAP（对称判定）。
     * 重建全部箱号的显式动作链区间：本箱作业与任何同设备他箱开放区间重叠即冲突，
     * 他箱在其自身编译轮次中同样判冲突（compileContainer 会连带重编译对方箱号）。
     */
    private Set<String> detectDeviceOverlaps(String containerNo, List<PlannedJob> myJobs,
                                             Long runId, int version,
                                             List<SemanticConflict> conflicts) {
        Set<String> counterparts = new LinkedHashSet<>();
        Set<String> seenOverlapKeys = new LinkedHashSet<>();

        record Interval(String device, OffsetDateTime start, OffsetDateTime end, List<Long> eventIds) {
        }
        // 重建所有箱号的显式场桥链区间，用于跨箱对称判定
        Map<String, List<Interval>> intervalsByContainer = new LinkedHashMap<>();
        for (String c : eventRepo.findCommittedContainers()) {
            List<IngestEvent> explicit = new ArrayList<>();
            for (IngestEvent e : eventRepo.findCommitted(c)) {
                if (e.getSourceSystem() != SourceSystem.CRANE || e.getEventType() != EventType.CRANE_MOVE) {
                    continue;
                }
                JsonNode p = SemanticEventInterpreter.payload(e, om);
                if (SemanticEventInterpreter.isExplicitAction(e, p)) {
                    explicit.add(e);
                }
            }
            List<Interval> intervals = new ArrayList<>();
            for (Chain ch : groupChains(explicit)) {
                if (ch.events.isEmpty()) {
                    continue;
                }
                List<Long> ids = ch.events.stream().map(cv -> cv.event().getId()).sorted().toList();
                String device = ch.events.get(0).deviceId();
                OffsetDateTime start = ch.events.get(0).event().getBizTime();
                OffsetDateTime end = ch.events.get(ch.events.size() - 1).event().getBizTime();
                intervals.add(new Interval(device, start, end, ids));
            }
            intervalsByContainer.put(c, intervals);
        }

        for (PlannedJob job : myJobs) {
            if (job.startBizTime == null) {
                continue;
            }
            OffsetDateTime jobEnd = job.endBizTime != null ? job.endBizTime : OffsetDateTime.now();
            for (Map.Entry<String, List<Interval>> entry : intervalsByContainer.entrySet()) {
                String otherContainer = entry.getKey();
                if (otherContainer.equals(containerNo)) {
                    continue;
                }
                for (Interval other : entry.getValue()) {
                    if (!other.device().equals(job.deviceId)) {
                        continue;
                    }
                    OffsetDateTime otherEnd = other.end(); // 未闭合链的 end 即最后动作时点
                    boolean overlap = job.startBizTime.isBefore(otherEnd.plusSeconds(1))
                            && other.start().isBefore(jobEnd.plusSeconds(1));
                    if (!overlap) {
                        continue;
                    }
                    String key = job.deviceId + "|" + containerNo + "|" + otherContainer + "|" + job.jobNo;
                    if (!seenOverlapKeys.add(key)) {
                        continue;
                    }
                    String detail = "设备 " + job.deviceId + " 的两个吊运区间重叠：" + containerNo
                            + "[" + job.startBizTime + " ~ " + jobEnd + "] 与 " + otherContainer
                            + "[" + other.start() + " ~ " + otherEnd + "]，同一设备不可能时间重叠操作两只箱";
                    Set<Long> idSet = new LinkedHashSet<>(job.eventIds);
                    idSet.addAll(other.eventIds());
                    SemanticConflict c = conflict(containerNo, "DEVICE_OVERLAP", "HIGH", detail,
                            idSet.stream().sorted().toList(), runId, version);
                    conflicts.add(c);
                    job.status = FactStatus.IMPOSSIBLE;
                    job.confidence = ConfidenceLevel.LOW;
                    job.chargeable = false;
                    job.excludeReason = detail;
                    job.inferenceNote = "并行设备冲突：作业被阻止进入可收费汇总，待人工裁定，不以任一消息为准";
                    job.conflictDrafts.add(c);
                    counterparts.add(otherContainer);
                }
            }
        }
        return counterparts;
    }

    // ============================ 历史报文兼容事实 ============================

    /** 未显式拆分三阶段的 CRANE_MOVE：仅作移动过程证据，不产生起吊/落箱语义。 */
    private SemanticFact buildLegacyMoveFact(IngestEvent e, Long runId, int version) {
        SemanticFact f = baseFact(e.getContainerNo(), runId, version);
        f.setFactType("JOB");
        f.setJobNo(e.getContainerNo() + "-LEGACY-" + e.getSourceSeq());
        f.setDeviceType("CRANE");
        JsonNode p = SemanticEventInterpreter.payload(e, om);
        f.setDeviceId(SemanticEventInterpreter.deviceId(e, p));
        f.setStartBizTime(e.getBizTime());
        f.setEndBizTime(e.getBizTime());
        f.setFirstEventId(e.getId());
        f.setLastEventId(e.getId());
        f.setSourceEventIds(writeJson(List.of(e.getId())));
        Map<String, Object> phase = new LinkedHashMap<>();
        phase.put("TRAVEL", List.of(SemanticEventInterpreter.evidenceNode(e, "TRAVEL", p)));
        f.setPhaseState(writeJson(phase));
        f.setStatus(FactStatus.COMPLETED);
        f.setConfidence(ConfidenceLevel.HIGH);
        f.setFromLocation(SemanticEventInterpreter.fromLocation(p));
        f.setToLocation(SemanticEventInterpreter.toLocation(p));
        f.setChargeable(true);
        f.setInferenceNote("历史报文未拆分吊起/落下阶段，仅按移动过程证据计为一次移箱（HIGH，非 CONFIRMED）；"
                + "不参与三阶段顺序与堆位互斥判定");
        return f;
    }

    // ============================ 权威单点事实 ============================

    private SemanticFact buildAuthoritativeFact(IngestEvent e, Long runId, int version) {
        SemanticFact f = baseFact(e.getContainerNo(), runId, version);
        f.setFactType("JOB");
        f.setJobNo(e.getContainerNo() + "-" + e.getSourceSystem().name() + "-" + e.getSourceSeq());
        f.setDeviceType(e.getSourceSystem().name());
        JsonNode p = SemanticEventInterpreter.payload(e, om);
        f.setDeviceId(SemanticEventInterpreter.deviceId(e, p));
        f.setStartBizTime(e.getBizTime());
        f.setEndBizTime(e.getBizTime());
        f.setFirstEventId(e.getId());
        f.setLastEventId(e.getId());
        f.setSourceEventIds(writeJson(List.of(e.getId())));
        Map<String, Object> phase = new LinkedHashMap<>();
        phase.put("evidence", List.of(SemanticEventInterpreter.evidenceNode(
                e, e.getEventType().name(), p)));
        f.setPhaseState(writeJson(phase));
        f.setStatus(FactStatus.COMPLETED);
        f.setConfidence(ConfidenceLevel.CONFIRMED);
        f.setInferenceNote(switch (e.getEventType()) {
            case GATE_RELEASE -> "闸口系统权威单点证据（通行即事实）";
            case YARD_CHECK -> "堆场盘点权威单点证据（实地盘点即落位事实）";
            case LOAD_CONFIRM -> "装船确认权威单点证据";
            default -> "权威单点证据";
        });
        switch (e.getEventType()) {
            case GATE_RELEASE -> {
                String direction = p.path("direction").asText("IN");
                f.setFromLocation("OUT".equalsIgnoreCase(direction) ? null : "GATE-OUTSIDE");
                f.setToLocation("OUT".equalsIgnoreCase(direction) ? "GATE-OUT"
                        : "GATE-IN:" + p.path("gate").asText("?"));
                f.setChargeable("OUT".equalsIgnoreCase(direction)); // 出场放行才产生闸口费
                if (!f.isChargeable()) {
                    f.setExcludeReason("进场通行不产生可收费作业");
                }
            }
            case YARD_CHECK -> {
                f.setToLocation(SemanticEventInterpreter.checkLocation(p));
                f.setChargeable(false);
                f.setExcludeReason("盘点为核对性作业，不产生装卸费用");
            }
            case LOAD_CONFIRM -> {
                f.setToLocation("VESSEL:" + p.path("voyageNo").asText(
                        e.getVoyageNo() == null ? "?" : e.getVoyageNo()));
                f.setChargeable(true);
            }
            default -> {
                f.setChargeable(false);
                f.setExcludeReason("非作业性事件");
            }
        }
        return f;
    }

    // ============================ 箱子语义状态事实（防倒退） ============================

    private SemanticFact buildStateFact(String containerNo, List<IngestEvent> events,
                                        List<PlannedJob> jobs, List<SemanticFact> authFacts,
                                        Long runId, int version,
                                        List<SemanticConflict> conflicts) {
        SemanticFact state = baseFact(containerNo, runId, version);
        state.setFactType("STATE");
        state.setStatus(FactStatus.INTERRUPTED);
        state.setConfidence(ConfidenceLevel.LOW);
        state.setChargeable(false);
        state.setExcludeReason("状态事实仅供调度，不直接计费");

        List<Long> evidenceIds = new ArrayList<>();
        String semanticState;
        String location = null;

        SemanticFact latestAuth = authFacts.stream()
                .max(Comparator.comparing(SemanticFact::getEndBizTime)).orElse(null);
        PlannedJob latestCompleted = jobs.stream()
                .filter(j -> j.status == FactStatus.COMPLETED)
                .max(Comparator.comparing(j -> j.endBizTime)).orElse(null);
        PlannedJob openJob = jobs.stream()
                .filter(j -> j.status == FactStatus.IN_FLIGHT)
                .max(Comparator.comparing(j -> j.startBizTime)).orElse(null);
        boolean hasImpossible = jobs.stream().anyMatch(j -> j.status == FactStatus.IMPOSSIBLE);

        OffsetDateTime confirmedFrontier = null;
        if (latestAuth != null) {
            confirmedFrontier = latestAuth.getEndBizTime();
        }
        if (latestCompleted != null && (confirmedFrontier == null
                || latestCompleted.endBizTime.isAfter(confirmedFrontier))) {
            confirmedFrontier = latestCompleted.endBizTime;
        }

        if (latestAuth != null && (latestCompleted == null
                || !latestCompleted.endBizTime.isAfter(latestAuth.getEndBizTime()))) {
            semanticState = switch (latestAuth.getDeviceType()) {
                case "VESSEL" -> "LOADED";
                case "GATE" -> "GATE-OUT".equals(latestAuth.getToLocation()) ? "GATE_OUT" : "ARRIVED";
                default -> "LANDED";
            };
            location = latestAuth.getToLocation();
            state.setStatus(FactStatus.COMPLETED);
            state.setConfidence(ConfidenceLevel.CONFIRMED);
            evidenceIds.add(latestAuth.getLastEventId());
        } else if (latestCompleted != null) {
            semanticState = "LANDED";
            location = latestCompleted.toLocation;
            state.setStatus(FactStatus.COMPLETED);
            state.setConfidence(latestCompleted.confidence);
            evidenceIds.addAll(latestCompleted.eventIds);
        } else if (openJob != null) {
            semanticState = "LIFTING";
            state.setStatus(FactStatus.IN_FLIGHT);
            state.setConfidence(ConfidenceLevel.MEDIUM);
            evidenceIds.addAll(openJob.eventIds);
        } else if (hasImpossible) {
            semanticState = "IMPOSSIBLE";
            state.setStatus(FactStatus.IMPOSSIBLE);
        } else {
            semanticState = "UNCERTAIN";
        }

        // 防倒退：已证实终态之后出现晚到的未闭合吊起，不得回退已证实状态
        if (confirmedFrontier != null) {
            for (PlannedJob j : jobs) {
                if ((j.status == FactStatus.IN_FLIGHT || j.status == FactStatus.INTERRUPTED)
                        && j.startBizTime.isAfter(confirmedFrontier)) {
                    SemanticConflict guard = conflict(containerNo, "NO_REGRESSION_GUARD", "MEDIUM",
                            "已证实状态（截止 " + confirmedFrontier + "）之后到达的吊起事件 "
                                    + j.eventIds + " 缺少落箱证据，不得把已证实状态倒退为吊运中；"
                                    + "待 DROP 证据在新版本闭合后才可更新",
                            j.eventIds, runId, version);
                    conflicts.add(guard);
                    j.conflictDrafts.add(guard);
                }
            }
        }

        state.setToLocation(location);
        Map<String, Object> phase = new LinkedHashMap<>();
        phase.put("semanticState", semanticState);
        phase.put("confirmedFrontier", confirmedFrontier == null ? null : confirmedFrontier.toString());
        phase.put("openJobNos", jobs.stream().filter(j -> j.status == FactStatus.IN_FLIGHT)
                .map(j -> j.jobNo).toList());
        phase.put("impossibleJobNos", jobs.stream().filter(j -> j.status == FactStatus.IMPOSSIBLE)
                .map(j -> j.jobNo).toList());
        state.setPhaseState(writeJson(phase));
        if (evidenceIds.isEmpty()) {
            evidenceIds.addAll(events.stream().map(IngestEvent::getId).sorted().toList());
        }
        List<Long> scope = evidenceIds.stream().distinct().sorted().toList();
        state.setSourceEventIds(writeJson(scope));
        state.setFirstEventId(scope.stream().min(Long::compare).orElse(null));
        state.setLastEventId(scope.stream().max(Long::compare).orElse(null));
        state.setInferenceNote("当前语义状态=" + semanticState + "（" + state.getConfidence()
                + "）；CONFIRMED 事实只能被更晚的完整证据更新，晚到单条事件不产生倒退");
        return state;
    }

    // ============================ 辅助结构 ============================

    private SemanticFact baseFact(String containerNo, Long runId, int version) {
        SemanticFact f = new SemanticFact();
        f.setContainerNo(containerNo);
        f.setRunId(runId);
        f.setVersion(version);
        f.setRecordStatus(ACTIVE);
        return f;
    }

    private SemanticConflict conflict(String containerNo, String type, String severity,
                                      String detail, List<Long> eventIds, Long runId, int version) {
        SemanticConflict c = new SemanticConflict();
        c.setContainerNo(containerNo);
        c.setConflictType(type);
        c.setSeverity(severity);
        c.setDetail(detail);
        c.setEventIds(writeJson(eventIds));
        c.setRunId(runId);
        c.setVersion(version);
        c.setRecordStatus(ACTIVE);
        return c;
    }

    private Map<String, Object> buildPhaseState(List<CraneEv> evs) {
        Map<String, List<Map<String, Object>>> byAction = new LinkedHashMap<>();
        for (CraneEv cv : evs) {
            byAction.computeIfAbsent(cv.action(), k -> new ArrayList<>())
                    .add(SemanticEventInterpreter.evidenceNode(cv.event(), cv.action(), cv.payload()));
        }
        return new LinkedHashMap<>(byAction);
    }

    private String dropLocation(List<CraneEv> evs, CraneEv last) {
        return evs.stream().filter(c -> SemanticEventInterpreter.ACTION_DROP.equals(c.action()))
                .reduce((a, b) -> b).map(CraneEv::to).orElse(last.to());
    }

    private long worstGapSeconds(List<CraneEv> evs) {
        long worst = 0;
        for (int i = 1; i < evs.size(); i++) {
            worst = Math.max(worst, Math.abs(Duration.between(
                    evs.get(i - 1).event().getBizTime(), evs.get(i).event().getBizTime()).getSeconds()));
        }
        return worst;
    }

    private Map<String, Object> buildFrontier(List<IngestEvent> events) {
        Map<String, Long> seqBySource = new LinkedHashMap<>();
        long maxId = 0;
        for (IngestEvent e : events) {
            seqBySource.merge(e.getSourceSystem().name(), e.getSourceSeq(), Math::max);
            maxId = Math.max(maxId, e.getId());
        }
        Map<String, Object> frontier = new LinkedHashMap<>();
        frontier.put("maxEventId", maxId);
        frontier.put("sourceSeqs", seqBySource);
        frontier.put("eventCount", events.size());
        return frontier;
    }

    private String writeJson(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 解析后的场桥事件。 */
    private record CraneEv(IngestEvent event, JsonNode payload, String action,
                           String deviceId, String from, String to) {
    }

    /** 一条逻辑作业链（HOIST 起、DROP 止，或未闭合）。 */
    private static class Chain {
        final List<CraneEv> events = new ArrayList<>();
    }

    /** 链评估产物，落库前的可变作业事实。 */
    private static class PlannedJob {
        String jobNo;
        String deviceType;
        String deviceId;
        FactStatus status;
        ConfidenceLevel confidence;
        Map<String, Object> phaseState;
        List<Long> eventIds;
        Long firstEventId;
        Long lastEventId;
        OffsetDateTime startBizTime;
        OffsetDateTime endBizTime;
        String fromLocation;
        String toLocation;
        boolean chargeable;
        String excludeReason;
        String inferenceNote;
        final List<SemanticConflict> conflictDrafts = new ArrayList<>();

        SemanticFact toFact(String containerNo, Long runId, int version, ObjectMapper om,
                            List<SemanticConflict> allConflicts) {
            SemanticFact f = new SemanticFact();
            f.setContainerNo(containerNo);
            f.setFactType("JOB");
            f.setJobNo(jobNo);
            f.setStatus(status);
            f.setConfidence(confidence);
            f.setDeviceType(deviceType);
            f.setDeviceId(deviceId);
            try {
                f.setPhaseState(om.writeValueAsString(phaseState));
                f.setSourceEventIds(om.writeValueAsString(eventIds));
                List<Long> conflictIds = conflictDrafts.stream()
                        .map(allConflicts::indexOf)
                        .filter(idx -> idx >= 0)
                        .map(idx -> allConflicts.get(idx).getId())
                        .toList();
                f.setConflictIds(om.writeValueAsString(conflictIds));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            f.setFirstEventId(firstEventId);
            f.setLastEventId(lastEventId);
            f.setStartBizTime(startBizTime);
            f.setEndBizTime(endBizTime);
            f.setFromLocation(fromLocation);
            f.setToLocation(toLocation);
            f.setChargeable(chargeable);
            f.setExcludeReason(excludeReason);
            f.setInferenceNote(inferenceNote);
            f.setVersion(version);
            f.setRunId(runId);
            f.setRecordStatus(ACTIVE);
            return f;
        }
    }
}
