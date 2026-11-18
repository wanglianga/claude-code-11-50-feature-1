-- 集装箱作业语义编译与状态一致性检查
-- 在冻结基线（事件账/不可变提交/航次快照）之上新增独立保存的语义层状态：
--   设备阶段规则 / 编译运行版本 / 作业事实(版本化) / 语义冲突(版本化) / 航次作业快照
-- 修正只能生成新的解释版本：事实与冲突只允许 ACTIVE→SUPERSEDED，禁止内容改写与删除。

-- ============ 按设备类型定义的作业阶段规则 ============
CREATE TABLE semantic_phase_rule (
    id                  BIGSERIAL PRIMARY KEY,
    device_type         VARCHAR(24) NOT NULL UNIQUE,           -- CRANE/GATE/YARD/VESSEL
    phases              JSONB       NOT NULL,                  -- 有序阶段定义 [{code,name}]
    allowed_transitions JSONB       NOT NULL,                  -- 允许转换 [["HOIST","TRAVEL"],...]
    min_evidence        JSONB       NOT NULL,                  -- 最小必要证据（阶段计数/完整链要求）
    tolerances          JSONB       NOT NULL,                  -- 可容忍时间间隔（秒）
    updated_by          VARCHAR(64),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============ 语义编译运行（每次编译一个新版本） ============
CREATE TABLE semantic_compile_run (
    id              BIGSERIAL PRIMARY KEY,
    container_no    VARCHAR(16) NOT NULL,
    run_seq         BIGINT      NOT NULL,                      -- 箱号内单调递增的解释版本号
    trigger_mode    VARCHAR(16) NOT NULL,                      -- MANUAL/ALL
    event_frontier  JSONB       NOT NULL,                      -- 本次编译使用的事件水位（各来源最大 seq + 事件 id 上限）
    rule_snapshot   JSONB       NOT NULL,                      -- 本次编译使用的规则快照（规则后改不影响旧版本）
    fact_count      INT         NOT NULL DEFAULT 0,
    conflict_count  INT         NOT NULL DEFAULT 0,
    created_by      VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (container_no, run_seq)
);
CREATE INDEX idx_sem_run_container ON semantic_compile_run (container_no, run_seq);

-- ============ 作业事实（版本化，只增不改） ============
CREATE TABLE semantic_fact (
    id                 BIGSERIAL PRIMARY KEY,
    container_no       VARCHAR(16) NOT NULL,
    fact_type          VARCHAR(24) NOT NULL,                  -- JOB（作业）/ STATE（箱子状态事实）
    job_no             VARCHAR(48),                            -- 逻辑作业号（箱号+链序号）
    status             VARCHAR(24) NOT NULL,                  -- COMPLETED/IN_FLIGHT/INTERRUPTED/IMPOSSIBLE
    confidence         VARCHAR(16) NOT NULL,                  -- CONFIRMED/HIGH/MEDIUM/LOW
    device_type        VARCHAR(24),
    device_id          VARCHAR(40),
    phase_state        JSONB       NOT NULL,                   -- 各阶段证据 {HOIST:{eventIds,bizTime,action},...}
    source_event_ids   JSONB       NOT NULL,                   -- 本事实使用的原始事件范围（事件账 id）
    first_event_id     BIGINT,
    last_event_id      BIGINT,
    start_biz_time     TIMESTAMPTZ,
    end_biz_time       TIMESTAMPTZ,
    from_location      VARCHAR(64),
    to_location        VARCHAR(64),
    chargeable         BOOLEAN     NOT NULL,                   -- 是否可进入可收费作业汇总
    exclude_reason     TEXT,                                   -- 不可收费/待裁定的可解释原因
    conflict_ids       JSONB       NOT NULL DEFAULT '[]',
    inference_note     TEXT,                                   -- 推断依据、置信等级与撤销规则
    version            INT         NOT NULL,                   -- 由第几次编译(run_seq)产出
    run_id             BIGINT      NOT NULL REFERENCES semantic_compile_run (id),
    record_status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE/SUPERSEDED
    superseded_by_run  BIGINT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sem_fact_container ON semantic_fact (container_no, record_status);
CREATE INDEX idx_sem_fact_job ON semantic_fact (container_no, job_no);
CREATE INDEX idx_sem_fact_charge ON semantic_fact (chargeable, record_status);

-- ============ 语义冲突事实（版本化，只增不改） ============
CREATE TABLE semantic_conflict (
    id                 BIGSERIAL PRIMARY KEY,
    container_no       VARCHAR(16) NOT NULL,
    conflict_type      VARCHAR(40) NOT NULL,
    -- DEVICE_OVERLAP 同设备不可能时间重叠 / DUAL_SLOT_DROP 互斥堆位同时落箱
    -- PHASE_ORDER 阶段顺序违反 / NO_REGRESSION_GUARD 晚到事件不得倒退已证实状态
    severity           VARCHAR(16) NOT NULL,                  -- HIGH/MEDIUM
    detail             TEXT        NOT NULL,
    event_ids          JSONB       NOT NULL,                   -- 冲突涉及的原始事件范围
    related_fact_ids   JSONB       NOT NULL DEFAULT '[]',
    version            INT         NOT NULL,
    run_id             BIGINT      NOT NULL REFERENCES semantic_compile_run (id),
    record_status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    superseded_by_run  BIGINT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sem_conflict_container ON semantic_conflict (container_no, record_status);

-- ============ 航次作业快照（冻结时固化语义解释，独立于清单费用快照） ============
CREATE TABLE manifest_job_snapshot (
    id              BIGSERIAL PRIMARY KEY,
    manifest_id     BIGINT      NOT NULL UNIQUE REFERENCES voyage_manifest (id),
    voyage_no       VARCHAR(32) NOT NULL,
    run_frontier    JSONB       NOT NULL,                      -- {箱号: semantic_compile_run.id}
    fact_count      INT         NOT NULL,
    conflict_count  INT         NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE manifest_job_snapshot_item (
    id               BIGSERIAL PRIMARY KEY,
    snapshot_id      BIGINT      NOT NULL REFERENCES manifest_job_snapshot (id),
    container_no     VARCHAR(16) NOT NULL,
    job_no           VARCHAR(48),
    fact_type        VARCHAR(24) NOT NULL,
    status           VARCHAR(24) NOT NULL,
    confidence       VARCHAR(16) NOT NULL,
    device_id        VARCHAR(40),
    from_location    VARCHAR(64),
    to_location      VARCHAR(64),
    chargeable       BOOLEAN     NOT NULL,
    exclude_reason   TEXT,
    fact_id          BIGINT      NOT NULL,
    source_event_ids JSONB       NOT NULL,
    UNIQUE (snapshot_id, container_no, job_no)
);
CREATE INDEX idx_snapshot_item_snapshot ON manifest_job_snapshot_item (snapshot_id);

-- ============ 版本化不可变约束 ============
-- 语义事实/冲突：禁止删除；UPDATE 仅允许 ACTIVE→SUPERSEDED 的版本更替，内容列不得改写。
CREATE OR REPLACE FUNCTION semantic_version_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION '% 表记录不可删除：语义修正只能生成新的解释版本', TG_TABLE_NAME;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.record_status <> 'ACTIVE' OR NEW.record_status <> 'SUPERSEDED'
           OR NEW.superseded_by_run IS NULL
           OR NEW.id <> OLD.id
           OR NEW.container_no <> OLD.container_no
           OR NEW.run_id <> OLD.run_id
           OR NEW.version <> OLD.version
           OR NEW.source_event_ids IS DISTINCT FROM OLD.source_event_ids
           OR NEW.status <> OLD.status
           OR NEW.confidence <> OLD.confidence THEN
            RAISE EXCEPTION '% 表记录内容不可变：仅允许 ACTIVE→SUPERSEDED 的版本更替（修正须生成新版本）', TG_TABLE_NAME;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_semantic_fact_versioned
    BEFORE UPDATE OR DELETE ON semantic_fact
    FOR EACH ROW EXECUTE FUNCTION semantic_version_guard();

CREATE TRIGGER trg_semantic_conflict_versioned
    BEFORE UPDATE OR DELETE ON semantic_conflict
    FOR EACH ROW EXECUTE FUNCTION semantic_version_guard();

CREATE TRIGGER trg_job_snapshot_immutable
    BEFORE UPDATE OR DELETE ON manifest_job_snapshot
    FOR EACH ROW EXECUTE FUNCTION deny_modify();

CREATE TRIGGER trg_job_snapshot_item_immutable
    BEFORE UPDATE OR DELETE ON manifest_job_snapshot_item
    FOR EACH ROW EXECUTE FUNCTION deny_modify();

-- ============ 规则默认值 ============
-- 场桥：提箱(HOIST)→移动(TRAVEL)→落下(DROP) 三阶段严格链；
-- 相邻阶段可容忍 30 分钟间隔（设备短暂离线补传），作业整体开放上限 2 小时。
INSERT INTO semantic_phase_rule (device_type, phases, allowed_transitions, min_evidence, tolerances) VALUES
('CRANE',
 '[{"code":"HOIST","name":"吊起"},{"code":"TRAVEL","name":"移动"},{"code":"DROP","name":"落下"}]',
 '[["HOIST","TRAVEL"],["HOIST","DROP"],["TRAVEL","DROP"]]',
 '{"jobRequiredPhases":["HOIST","DROP"],"confirmedChain":["HOIST","TRAVEL","DROP"],"phaseMinCount":{"HOIST":1,"TRAVEL":1,"DROP":1}}',
 '{"maxPhaseGapSeconds":1800,"maxJobOpenSeconds":7200,"overlapToleranceSeconds":0}'),
('GATE',
 '[{"code":"GATE_PASS","name":"闸口通行"}]',
 '[]',
 '{"jobRequiredPhases":["GATE_PASS"],"confirmedChain":["GATE_PASS"],"phaseMinCount":{"GATE_PASS":1}}',
 '{"maxPhaseGapSeconds":0,"maxJobOpenSeconds":0,"overlapToleranceSeconds":0}'),
('YARD',
 '[{"code":"YARD_CHECK","name":"堆场盘点"}]',
 '[]',
 '{"jobRequiredPhases":["YARD_CHECK"],"confirmedChain":["YARD_CHECK"],"phaseMinCount":{"YARD_CHECK":1}}',
 '{"maxPhaseGapSeconds":0,"maxJobOpenSeconds":0,"overlapToleranceSeconds":0}'),
('VESSEL',
 '[{"code":"LOAD_CONFIRM","name":"装船确认"}]',
 '[]',
 '{"jobRequiredPhases":["LOAD_CONFIRM"],"confirmedChain":["LOAD_CONFIRM"],"phaseMinCount":{"LOAD_CONFIRM":1}}',
 '{"maxPhaseGapSeconds":0,"maxJobOpenSeconds":0,"overlapToleranceSeconds":0}');
