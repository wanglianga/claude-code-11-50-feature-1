-- 修正 V2 的版本化守卫：semantic_fact 与 semantic_conflict 列结构不同，
-- 不能共用同一触发器函数（V2 函数引用了 semantic_conflict 不存在的
-- source_event_ids/status/confidence 列，二次编译 SUPERSEDED 冲突时会报错）。
-- 拆分为两个表各自的守卫函数；语义不变：禁止删除，UPDATE 仅允许 ACTIVE→SUPERSEDED。

DROP TRIGGER IF EXISTS trg_semantic_fact_versioned ON semantic_fact;
DROP TRIGGER IF EXISTS trg_semantic_conflict_versioned ON semantic_conflict;
DROP FUNCTION IF EXISTS semantic_version_guard();

CREATE OR REPLACE FUNCTION semantic_fact_version_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'semantic_fact 记录不可删除：语义修正只能生成新的解释版本';
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
            RAISE EXCEPTION 'semantic_fact 记录内容不可变：仅允许 ACTIVE→SUPERSEDED 的版本更替（修正须生成新版本）';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION semantic_conflict_version_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'semantic_conflict 记录不可删除：语义修正只能生成新的解释版本';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.record_status <> 'ACTIVE' OR NEW.record_status <> 'SUPERSEDED'
           OR NEW.superseded_by_run IS NULL
           OR NEW.id <> OLD.id
           OR NEW.container_no <> OLD.container_no
           OR NEW.run_id <> OLD.run_id
           OR NEW.version <> OLD.version
           OR NEW.event_ids IS DISTINCT FROM OLD.event_ids
           OR NEW.conflict_type <> OLD.conflict_type
           OR NEW.severity <> OLD.severity THEN
            RAISE EXCEPTION 'semantic_conflict 记录内容不可变：仅允许 ACTIVE→SUPERSEDED 的版本更替（修正须生成新版本）';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_semantic_fact_versioned
    BEFORE UPDATE OR DELETE ON semantic_fact
    FOR EACH ROW EXECUTE FUNCTION semantic_fact_version_guard();

CREATE TRIGGER trg_semantic_conflict_versioned
    BEFORE UPDATE OR DELETE ON semantic_conflict
    FOR EACH ROW EXECUTE FUNCTION semantic_conflict_version_guard();
