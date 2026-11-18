package com.portops.lakehouse.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备阶段规则的读取与快照。
 * 规则按设备类型维护阶段、允许转换、最小必要证据、可容忍时间间隔；
 * 编译时把当时规则整体快照进编译运行，规则后续调整不影响历史解释版本。
 */
@Component
public class SemanticRuleCatalog {

    private final SemanticPhaseRuleRepository ruleRepo;
    private final ObjectMapper om;

    public SemanticRuleCatalog(SemanticPhaseRuleRepository ruleRepo, ObjectMapper om) {
        this.ruleRepo = ruleRepo;
        this.om = om;
    }

    /** 读取全部设备规则，键为设备类型（CRANE/GATE/YARD/VESSEL）。 */
    public Map<String, SemanticRule> loadAll() {
        Map<String, SemanticRule> rules = new LinkedHashMap<>();
        for (SemanticPhaseRule row : ruleRepo.findAll()) {
            rules.put(row.getDeviceType(), toRule(row));
        }
        return rules;
    }

    public SemanticRule require(String deviceType) {
        SemanticPhaseRule row = ruleRepo.findByDeviceType(deviceType)
                .orElseThrow(() -> new IllegalStateException("缺少设备阶段规则: " + deviceType));
        return toRule(row);
    }

    /** 规则快照（写入编译运行，保证旧版本可按当时规则复算）。 */
    public String snapshot() {
        try {
            Map<String, Object> snap = new LinkedHashMap<>();
            for (Map.Entry<String, SemanticRule> e : loadAll().entrySet()) {
                snap.put(e.getKey(), om.valueToTree(e.getValue()));
            }
            return om.writeValueAsString(snap);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private SemanticRule toRule(SemanticPhaseRule row) {
        try {
            JsonNode min = om.readTree(row.getMinEvidence());
            JsonNode tol = om.readTree(row.getTolerances());
            return new SemanticRule(
                    row.getDeviceType(),
                    om.readValue(row.getPhases(), List.class),
                    om.readValue(row.getAllowedTransitions(), List.class),
                    min.path("jobRequiredPhases").isArray()
                            ? om.readValue(min.get("jobRequiredPhases").toString(), List.class) : List.of(),
                    min.path("confirmedChain").isArray()
                            ? om.readValue(min.get("confirmedChain").toString(), List.class) : List.of(),
                    tol.path("maxPhaseGapSeconds").asLong(0L),
                    tol.path("maxJobOpenSeconds").asLong(0L),
                    tol.path("overlapToleranceSeconds").asLong(0L));
        } catch (Exception e) {
            throw new IllegalStateException("规则解析失败: " + row.getDeviceType(), e);
        }
    }

    /** 规则的运行期视图。 */
    public record SemanticRule(
            String deviceType,
            List<Map<String, Object>> phases,
            List<List<String>> allowedTransitions,
            List<String> jobRequiredPhases,
            List<String> confirmedChain,
            long maxPhaseGapSeconds,
            long maxJobOpenSeconds,
            long overlapToleranceSeconds) {
    }
}
