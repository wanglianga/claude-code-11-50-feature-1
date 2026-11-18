#!/usr/bin/env bash
# 语义编译验收回放：
#   完整吊运 / 缺失落箱 / 离线补传 / 并行设备冲突 / 跨堆位不可能轨迹 / 晚到吊起防倒退
# 前置：服务已启动（本地 mvn spring-boot:run 或 docker compose up）
# 用法：./scripts/replay-semantic.sh   （可用 BASE_URL 覆盖服务地址）
set -uo pipefail

BASE=${BASE_URL:-http://127.0.0.1:8080}
INGEST="ingest:ingest123"
DISPATCH="dispatcher:disp123"
SETTLE="settlement:settle123"
ADMIN="admin:admin123"
VOYAGE="V2026-11B"
CLEAN_VOYAGE="V2026-11C"
CUTOFF="2026-11-21T20:00:00+08:00"

PASS=0; FAIL=0
check() { # check <描述> <实际> <期望>
  if [[ "$2" == "$3" ]]; then PASS=$((PASS+1)); echo "  ✔ $1（=$2）";
  else FAIL=$((FAIL+1)); echo "  ✘ $1：期望[$3] 实际[$2]"; fi
}

api() { # api <user:pass> <METHOD> <path> [json-body] → stdout=body
  local auth=$1 method=$2 path=$3 body=${4:-}
  if [[ -n "$body" ]]; then
    curl -sS -u "$auth" -X "$method" "$BASE$path" -H 'Content-Type: application/json' -d "$body"
  else
    curl -sS -u "$auth" -X "$method" "$BASE$path"
  fi
}

api_status() { # api_status <user:pass> <METHOD> <path> [json-body] → stdout=http_code, body落盘 /tmp/replay_sem_body.json
  local auth=$1 method=$2 path=$3 body=${4:-}
  if [[ -n "$body" ]]; then
    curl -sS -o /tmp/replay_sem_body.json -w "%{http_code}" -u "$auth" -X "$method" "$BASE$path" -H 'Content-Type: application/json' -d "$body"
  else
    curl -sS -o /tmp/replay_sem_body.json -w "%{http_code}" -u "$auth" -X "$method" "$BASE$path"
  fi
}

echo "== 0. 等待服务并就绪重置 =="
for i in $(seq 1 30); do
  if curl -sS -o /dev/null "$BASE/v3/api-docs" 2>/dev/null; then break; fi
  sleep 1
done
curl -sS -o /dev/null "$BASE/v3/api-docs" || { echo "服务不可达: $BASE"; exit 1; }
api "$ADMIN" POST /api/dev/reset > /dev/null
echo "  服务可达，业务表已重置"

echo "== 1. 回放第一批样例（完整吊运/缺失落箱/并行冲突/不可能轨迹/已证实落箱）=="
for s in semantic-1-complete semantic-2-missing-drop semantic-4-device-overlap \
         semantic-5-impossible-trajectory semantic-6a-confirmed-landed; do
  N=$(api "$INGEST" POST "/api/ingest/replay/$s" | jq 'length')
  echo "  回放 $s: $N 条"
done

echo "== 2. 生成完整提交（语义层只编译已提交事件）=="
api "$INGEST" POST /api/commits/generate-all > /tmp/sem_gen1.json
check "全部 7 箱已生成提交" "$(jq 'length' /tmp/sem_gen1.json)" "7"

echo "== 3. 第一轮语义编译（解释版本 v1）=="
api "$INGEST" POST /api/semantic/compile-all > /tmp/sem_run1.json
check "编译箱数" "$(jq 'length' /tmp/sem_run1.json)" "7"
check "每箱版本号均为 1" "$(jq '[.[].version] | all(. == 1)' /tmp/sem_run1.json)" "true"

echo "== 4. 场景1：完整吊运 → 已完成作业/已证实，调度可用 =="
V1=$(api "$DISPATCH" GET /api/semantic/containers/SEM001/view)
check "SEM001 可用事实数（完整链 CONFIRMED）" "$(echo "$V1" | jq '[.availableFacts[] | select(.status=="COMPLETED" and .confidence=="CONFIRMED")] | length')" "1"
check "SEM001 状态事实=已落位/已证实" "$(echo "$V1" | jq -r '.stateFact.phaseState.semanticState + "|" + .stateFact.confidence')" "LANDED|CONFIRMED"
check "SEM001 事实保留原始事件范围(3 条)" "$(echo "$V1" | jq -r '.availableFacts[0].sourceEventIds | length')" "3"

echo "== 5. 场景2：缺失落箱 → 在途作业/推断状态，不与可用状态混淆 =="
V2=$(api "$DISPATCH" GET /api/semantic/containers/SEM002/view)
check "SEM002 推断事实数" "$(echo "$V2" | jq '[.inferredFacts[] | select(.status=="IN_FLIGHT" and .confidence=="MEDIUM")] | length')" "1"
check "SEM002 可用事实数=0（未闭合不可当可用）" "$(echo "$V2" | jq '.availableFacts | length')" "0"
check "SEM002 状态事实=吊运中/中等置信" "$(echo "$V2" | jq -r '.stateFact.phaseState.semanticState + "|" + .stateFact.confidence')" "LIFTING|MEDIUM"
check "推断事实写明撤销规则" "$(echo "$V2" | jq -r '.inferredFacts[0].inferenceNote | contains("撤销规则")')" "true"

echo "== 6. 场景3：并行设备冲突 → 双方作业均不可能、不可收费，不二选一 =="
V4=$(api "$DISPATCH" GET /api/semantic/containers/SEM004/view)
V5=$(api "$DISPATCH" GET /api/semantic/containers/SEM005/view)
check "SEM004 设备重叠冲突" "$(echo "$V4" | jq '[.conflicts[] | select(.conflictType=="DEVICE_OVERLAP")] | length')" "1"
check "SEM005 设备重叠冲突（对称留痕）" "$(echo "$V5" | jq '[.conflicts[] | select(.conflictType=="DEVICE_OVERLAP")] | length')" "1"
check "SEM004 作业进入待裁定(IMPOSSIBLE)" "$(echo "$V4" | jq '[.pendingAdjudication[] | select(.status=="IMPOSSIBLE")] | length')" "1"
check "SEM005 作业进入待裁定(IMPOSSIBLE)" "$(echo "$V5" | jq '[.pendingAdjudication[] | select(.status=="IMPOSSIBLE")] | length')" "1"
CHG=$(api "$DISPATCH" GET "/api/semantic/chargeable?containerNo=SEM004")
check "SEM004 可收费汇总纳入数=0" "$(echo "$CHG" | jq '.includedCount')" "0"
check "SEM004 被排除且有原因" "$(echo "$CHG" | jq -r '.excluded[0].excludeReason | contains("同一设备不可能时间重叠")')" "true"

echo "== 7. 场景4：跨堆位不可能轨迹 + 互斥堆位同时落箱 =="
V6=$(api "$DISPATCH" GET /api/semantic/containers/SEM006/view)
V7=$(api "$DISPATCH" GET /api/semantic/containers/SEM007/view)
check "SEM006 双堆位冲突（无吊起跨堆位）" "$(echo "$V6" | jq '[.conflicts[] | select(.conflictType=="DUAL_SLOT_DROP")] | length')" "1"
check "SEM007 双堆位冲突（同时点互斥堆位）" "$(echo "$V7" | jq '[.conflicts[] | select(.conflictType=="DUAL_SLOT_DROP")] | length')" "1"
check "SEM006 两条作业均被阻止收费" "$(echo "$V6" | jq '[.pendingAdjudication[] | select(.chargeable==false)] | length')" "2"
check "SEM007 两条作业均被阻止收费" "$(echo "$V7" | jq '[.pendingAdjudication[] | select(.chargeable==false)] | length')" "2"

echo "== 8. 可收费作业汇总：纳入的可追溯、排除的有原因 =="
SUM=$(api "$DISPATCH" GET "/api/semantic/chargeable?containerNo=SEM001")
check "SEM001 纳入可收费汇总" "$(echo "$SUM" | jq '.includedCount')" "1"
FACT_ID=$(echo "$SUM" | jq '.included[0].id')
TR=$(api "$DISPATCH" GET "/api/semantic/facts/$FACT_ID/traceability")
check "纳入作业溯源结论=INCLUDED" "$(echo "$TR" | jq -r '.decision')" "INCLUDED"
check "溯源能取回原始事件" "$(echo "$TR" | jq '.sourceEvents | length')" "3"
EXCL_ID=$(api "$DISPATCH" GET "/api/semantic/chargeable?containerNo=SEM006" | jq '.excluded[0].id')
TRX=$(api "$DISPATCH" GET "/api/semantic/facts/$EXCL_ID/traceability")
check "排除作业溯源结论=EXCLUDED 且带原因" "$(echo "$TRX" | jq -r '.decision + "|" + (.reason | type)')" "EXCLUDED|string"

echo "== 9. 带语义冲突冻结航次 → 必须拒绝（不发布带冲突的航次快照）=="
api "$SETTLE" POST /api/manifests/bookings \
  "{\"voyageNo\":\"$VOYAGE\",\"containerNos\":[\"SEM001\",\"SEM002\",\"SEM004\",\"SEM005\",\"SEM006\",\"SEM007\",\"SEM008\"]}" > /dev/null
CODE=$(api_status "$SETTLE" POST /api/manifests/freeze "{\"voyageNo\":\"$VOYAGE\",\"cutoffTime\":\"$CUTOFF\"}")
check "冻结被拒绝(HTTP 409)" "$CODE" "409"
check "失败原因=存在待裁定的语义冲突" "$(jq -r '.message | contains("语义冲突")' /tmp/replay_sem_body.json)" "true"

echo "== 10. 场景5：离线补传落箱 → 新版本升级为完整作业（超容忍窗降 HIGH，旧版本可追溯）=="
api "$INGEST" POST /api/ingest/replay/semantic-3-backfill > /dev/null
api "$INGEST" POST /api/commits/generate-all > /dev/null
api "$INGEST" POST /api/semantic/compile-all > /tmp/sem_run2.json
V2B=$(api "$DISPATCH" GET /api/semantic/containers/SEM002/view)
check "SEM002 补传后作业已完成" "$(echo "$V2B" | jq '[.inferredFacts[] | select(.status=="COMPLETED")] | length')" "1"
check "SEM002 超阶段容忍窗降为 HIGH（非 CONFIRMED）" "$(echo "$V2B" | jq -r '.inferredFacts[0].confidence')" "HIGH"
check "SEM002 状态事实随证据升级为已落位" "$(echo "$V2B" | jq -r '.stateFact.phaseState.semanticState')" "LANDED"
check "SEM002 历史版本含已 SUPERSEDED 事实（修正只增版本）" \
  "$(api "$DISPATCH" GET '/api/semantic/facts/history?containerNo=SEM002' | jq '[.[] | select(.recordStatus=="SUPERSEDED")] | length > 0')" "true"

echo "== 11. 场景6：晚到吊起不得倒退已证实落箱（防倒退保护 + 新版本修正）=="
api "$INGEST" POST /api/ingest/replay/semantic-6b-late-hoist > /dev/null
api "$INGEST" POST /api/commits/generate-all > /dev/null
api "$INGEST" POST /api/semantic/compile-all > /dev/null
V8=$(api "$DISPATCH" GET /api/semantic/containers/SEM008/view)
check "SEM008 已证实落箱未被倒退（仍 LANDED/CONFIRMED）" \
  "$(echo "$V8" | jq -r '.stateFact.phaseState.semanticState + "|" + .stateFact.confidence')" "LANDED|CONFIRMED"
check "SEM008 生成防倒退冲突事实" "$(echo "$V8" | jq '[.conflicts[] | select(.conflictType=="NO_REGRESSION_GUARD")] | length')" "1"
check "SEM008 经历两个解释版本" "$(api "$DISPATCH" GET '/api/semantic/runs?containerNo=SEM008' | jq 'length')" "3"

echo "== 12. 干净航次冻结：固化航次作业快照，结算可追溯纳入/排除 =="
api "$SETTLE" POST /api/manifests/bookings \
  "{\"voyageNo\":\"$CLEAN_VOYAGE\",\"containerNos\":[\"SEM001\",\"SEM008\"]}" > /dev/null
FREEZE=$(api "$SETTLE" POST /api/manifests/freeze "{\"voyageNo\":\"$CLEAN_VOYAGE\",\"cutoffTime\":\"$CUTOFF\"}")
MID=$(echo "$FREEZE" | jq '.manifestId')
check "干净航次冻结成功" "$(echo "$FREEZE" | jq -r '.status')" "FROZEN"
check "冻结响应含作业快照" "$(echo "$FREEZE" | jq '.jobSnapshot.snapshotId > 0')" "true"
SNAP=$(api "$SETTLE" GET "/api/settlement/manifests/$MID/job-snapshot")
check "作业快照行数（含状态事实）" "$(echo "$SNAP" | jq '.items | length >= 4')" "true"
check "快照中可收费作业带纳入标记" "$(echo "$SNAP" | jq '[.items[] | select(.chargeable==true)] | length > 0')" "true"
SNAP_TOTAL=$(echo "$SNAP" | jq '.items | length')
SNAP_WITH_SCOPE=$(echo "$SNAP" | jq '[.items[] | select((.sourceEventIds | length) > 0)] | length')
check "快照保留原始事件范围" "$([[ "$SNAP_WITH_SCOPE" == "$SNAP_TOTAL" ]] && echo true || echo false)" "true"

echo "== 13. 调度看板三层分区 =="
BOARD=$(api "$DISPATCH" GET /api/semantic/dispatch-board)
check "看板覆盖全部已编译箱号" "$(echo "$BOARD" | jq 'length')" "7"
check "看板含可用/推断/待裁定三类计数" \
  "$(echo "$BOARD" | jq '[.[] | has("availableCount") and has("inferredCount") and has("pendingCount")] | all')" "true"

echo ""
echo "=========================================="
echo "语义验收回放完成：通过 $PASS 项，失败 $FAIL 项"
echo "=========================================="
[[ $FAIL -eq 0 ]]
