package com.portops.lakehouse.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portops.lakehouse.common.EventType;
import com.portops.lakehouse.common.SourceSystem;
import com.portops.lakehouse.ingest.IngestEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 来源事件 → 设备作业阶段动作的解释器。
 *
 * <p>场桥系统把一次"提箱—落箱"拆成吊起/移动/落下三类动作（payload.action），
 * 闸口只报告通行、装船只报告确认；解释器把异构来源统一成设备阶段规则中的阶段码。
 * 历史报文未显式给 action 时，CRANE_MOVE 回退解释为 TRAVEL（移动过程证据），
 * 并在事实推断说明中标注，不臆造吊起/落下。</p>
 */
public final class SemanticEventInterpreter {

    public static final String ACTION_HOIST = "HOIST";
    public static final String ACTION_TRAVEL = "TRAVEL";
    public static final String ACTION_DROP = "DROP";

    private SemanticEventInterpreter() {
    }

    /** 来源系统即设备类型。 */
    public static String deviceType(IngestEvent e) {
        return e.getSourceSystem().name();
    }

    /** 设备标识：优先取 payload.crane/deviceId，缺省用 设备类型:分区。 */
    public static String deviceId(IngestEvent e, JsonNode payload) {
        if (payload.hasNonNull("crane")) {
            return payload.get("crane").asText();
        }
        if (payload.hasNonNull("deviceId")) {
            return payload.get("deviceId").asText();
        }
        return e.getSourceSystem().name() + ":" + e.getOpPartition();
    }

    /**
     * 解释事件对应的阶段动作；返回 null 表示该事件不属于场桥三阶段动作
     * （闸口通行/盘点/装船/更正走权威单点事实路径）。
     */
    public static String phaseAction(IngestEvent e, JsonNode payload) {
        if (e.getEventType() != EventType.CRANE_MOVE) {
            return null;
        }
        if (payload.hasNonNull("action")) {
            String a = payload.get("action").asText().trim().toUpperCase();
            return switch (a) {
                case ACTION_HOIST, ACTION_TRAVEL, ACTION_DROP -> a;
                default -> ACTION_TRAVEL; // 未知动作码不臆造起吊/落下，按移动过程证据处理
            };
        }
        return ACTION_TRAVEL; // 未拆分阶段的历史报文：仅作为移动过程证据
    }

    public static boolean isExplicitAction(IngestEvent e, JsonNode payload) {
        return e.getEventType() == EventType.CRANE_MOVE && payload.hasNonNull("action");
    }

    /** 落位堆位：toBlock-toSlot。 */
    public static String toLocation(JsonNode p) {
        if (p.hasNonNull("toBlock") || p.hasNonNull("toSlot")) {
            return text(p, "toBlock", "?") + "-" + text(p, "toSlot", "?");
        }
        return null;
    }

    /** 起吊堆位：fromBlock-fromSlot。 */
    public static String fromLocation(JsonNode p) {
        if (p.hasNonNull("fromBlock") || p.hasNonNull("fromSlot")) {
            return text(p, "fromBlock", "?") + "-" + text(p, "fromSlot", "?");
        }
        return null;
    }

    /** 盘点堆位：block-slot。 */
    public static String checkLocation(JsonNode p) {
        if (p.hasNonNull("block")) {
            return text(p, "block", "?") + "-" + text(p, "slot", "?");
        }
        return null;
    }

    /** 单条事件的证据节点（写入 phase_state，保留事件范围与现场时点）。 */
    public static Map<String, Object> evidenceNode(IngestEvent e, String action, JsonNode payload) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", e.getId());
        m.put("sourceSystem", e.getSourceSystem().name());
        m.put("sourceSeq", e.getSourceSeq());
        m.put("bizTime", e.getBizTime().toString());
        m.put("action", action);
        m.put("deviceId", deviceId(e, payload));
        return m;
    }

    public static JsonNode payload(IngestEvent e, ObjectMapper om) {
        try {
            return om.readTree(e.getPayload());
        } catch (Exception ex) {
            throw new IllegalStateException("事件 payload 无法解析: " + e.getId(), ex);
        }
    }

    private static String text(JsonNode n, String field, String dft) {
        return n != null && n.hasNonNull(field) ? n.get(field).asText() : dft;
    }
}
