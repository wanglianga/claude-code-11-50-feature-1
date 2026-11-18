# 港口集装箱作业事件湖仓与航次对账服务

闸口放行、场桥移动、堆场盘点、装船确认由不同系统上报，同一箱子可能被多条链路并发改写。
本服务以 **事件账 + 不可变提交清单** 架构替代"各系统直接更新一张作业表"：

- **接入服务** 保存来源事件及其顺序标识（`sourceSystem + sourceSeq` 幂等）；
- **湖仓写入服务** 按箱号+作业分区生成**不可变提交**，按来源序号和业务时点检测冲突，
  无法排序的状态进入**待裁定**，不让数据库最后写入者决定箱子是否已装船；
- **航次清单服务** 从已提交数据**冻结截关快照**，只引用完整提交清单，
  分区提交不完整时整个冻结失败，不用半个分区发布航次；
- **对账服务** 将冻结快照与承运人回执**逐项比较**，差异由对账人员决定生成**费用调整单**；
- **截关后更正** 形成**修订候选**，裁定接受后生成费用调整单，**不改写原始结算**；
- **调度查询** 读取当前提交合并视图（承担合并延迟）；**结算只能引用已冻结航次版本**。
- **作业语义编译**（本批次新增）在不可变事件账与完整提交之上，把来源事件编译成
  “已完成作业 / 在途作业 / 中断待核 / 不可能状态”等可解释事实：维护按设备类型定义的
  作业阶段、允许转换、最小必要证据与可容忍时间间隔，区分**已证实状态、推断状态（含置信等级与撤销规则）、待裁定范围**；
  同设备时间重叠、互斥堆位同时落箱、阶段顺序违反生成冲突事实并阻止其进入可收费作业汇总；
  晚到的单条事件不能把已证实落箱无依据倒退。语义状态独立保存，修正只能生成新的解释版本。

## 架构取舍（按题目要求）

| 方案 | 优势 | 代价 | 选择 |
| --- | --- | --- | --- |
| 事件账 + 提交清单（本平台） | 每次更正可溯源到具体闸口/场桥/装船记录；补传更正不改写已锁定航次收费事实 | 调度查询需合并更多事件，存储和读取成本较高 | ✅ 采用 |
| 箱位版本表 + 同事务更正记录 | 当前查询更快 | 多来源并发时需维护版本边界与提交一致性 | 不采用 |

## 技术栈

Java 25 · Spring Boot 3.5 · PostgreSQL 18（JSONB、咨询锁、触发器）· Flyway · springdoc-openapi（Swagger UI）· JUnit 5

## 快速启动

### 方式一：Docker Compose（一键）

```bash
docker compose up --build
```

- 应用：http://localhost:8080 （首次构建需下载依赖，约几分钟）
- Swagger UI：http://localhost:8080/swagger-ui.html
- PostgreSQL：`localhost:5432`，库/账号/密码 `portdb / portops / portops`

### 方式二：本地开发

前置：JDK 25（`JAVA_HOME` 指向）、Maven 3.9+、PostgreSQL（默认 `127.0.0.1:5432`，库/账号/密码 `ccdev`）。

```bash
# 1. 准备数据库（按实际环境调整）
psql -h 127.0.0.1 -U ccdev -d ccdev -c "SELECT 1"   # 确认可连接；Flyway 自动建表

# 2. 启动（环境变量可覆盖数据源：DB_URL / DB_USER / DB_PASSWORD / SERVER_PORT）
mvn spring-boot:run
# 或：mvn package -DskipTests && java -jar target/port-lakehouse-1.0.0.jar
```

## 测试账号（HTTP Basic，仅本地开发用）

| 账号 | 密码 | 角色 | 权限 |
| --- | --- | --- | --- |
| `ingest` | `ingest123` | INGEST | 事件上报、样例回放、提交生成、语义编译触发 |
| `dispatcher` | `disp123` | DISPATCH | 调度视图、语义三层分区/冲突/可收费查询、冲突裁定 |
| `settlement` | `settle123` | SETTLEMENT | 订舱登记、清单冻结（含作业快照）、修订裁定、对账、结算与作业纳入/排除追溯 |
| `admin` | `admin123` | 全部 | 含 `/api/dev/reset`（仅本地） |

## 验证方式

### 1. 自动化测试（26 个用例）

```bash
# 测试使用独立数据库 ccdev_test（首次需创建：createdb -h 127.0.0.1 -U ccdev ccdev_test）
mvn test
```

覆盖（基线 18）：并列业务时点冲突进待裁定、裁定后胜选生效、乱序入库不覆盖装船确认、来源序号回退冲突、
幂等去重、未提交/待裁定时冻结失败且零残留、完整提交边界、提交与清单行不可变（触发器）、
截关后更正→修订候选→费用调整单且原始清单不改写、回执差异对账、角色越权拒绝。

覆盖（语义编译 8，`SemanticCompilationFlowTest`）：完整链确认且可收费、缺失落箱的在途推断（不可收费）、
离线补传后新版本升级且超容忍窗降置信、同设备重叠双方均判不可能且阻止收费、
互斥堆位同时落箱与跨堆位不可能轨迹、晚到吊起不回退已证实状态（防倒退冲突）、
事实保留原始事件范围且历史版本可追溯、无吊起直接落箱的阶段顺序违反。

### 2. 验收回放脚本（36 项断言）

服务启动后执行（可重复执行，脚本开头自动重置业务表）：

```bash
./scripts/replay-acceptance.sh        # 或 BASE_URL=http://localhost:8080 ./scripts/replay-acceptance.sh
```

回放四个验收场景并逐项断言：

1. **并发位置更新**：装船确认先入库、场桥报文后入库（业务时点更早）→ 当前视图仍为已装船；
   场桥与装船同一业务时点冲突 → 待裁定，裁定前 `loaded` 不定，裁定后胜选生效、被取代报文可溯源。
2. **分区提交中断**：存在未提交事件时冻结 → 409 且清单表零残留；存在待裁定时冻结 → 409 零残留；
   裁定并补齐提交后冻结成功，`commitFrontier` 覆盖 6 箱 × 3 分区共 18 个提交。
3. **截关后更正**：更正报文（业务时点在截关前）提交后生成修订候选；冻结清单行不被改写；
   接受候选生成 +80.00 移箱费调整单；调度当前视图已反映更正。
4. **承运人回执差异**：6 箱中 4 箱匹配；CNU0004 装船事实与费用差异、CNU0006 回执缺失；
   差异分别生成调整单（+450.00）或忽略；结算视图原始结算（3260）与调整单（530）分列，总额 3790；
   绕过冻结清单直接结算被 400 拒绝。

### 3. Swagger UI 手工验证

打开 http://localhost:8080/swagger-ui.html ，用上方测试账号（Authorize → basicAuth）逐个接口调用。
OpenAPI 3.x 文档：http://localhost:8080/v3/api-docs

### 4. 语义编译验收回放（40 项断言）

```bash
./scripts/replay-semantic.sh
```

覆盖五类语义场景并逐项断言（箱号 SEM001–SEM008）：

1. **完整吊运**（SEM001）：HOIST→TRAVEL→DROP 完整链 → 已完成作业/`CONFIRMED`，进入调度可用事实，
   事实保留 3 条原始事件范围。
2. **缺失落箱**（SEM002，第一轮）：仅有吊起/移动 → 在途作业 `IN_FLIGHT/MEDIUM` 推断，
   带明确撤销规则，不与已证实状态混淆、不可收费。
3. **离线补传**（SEM002，第二轮）：设备恢复后补传 DROP → 新版本把作业升级为已完成；
   阶段间隔 45 分钟超出 30 分钟容忍窗，置信降为 `HIGH`（非 CONFIRMED），旧版本事实标记 SUPERSEDED 仍可追溯。
4. **并行设备冲突**（SEM004/SEM005）：同一台 QC-03 的两个吊运区间重叠 → 双方均生成 `DEVICE_OVERLAP`
   冲突、作业判 `IMPOSSIBLE` 且不可收费，系统不作二选一。
5. **跨堆位不可能轨迹**（SEM006/SEM007）：无新吊起的跨堆位落箱、同时点互斥堆位双落箱
   → `DUAL_SLOT_DROP` 冲突，两条作业都被阻止收费；带冲突冻结航次被 409 拒绝。
6. **晚到吊起防倒退**（SEM008）：已证实落箱后到达一条无落箱的晚到吊起 →
   生成 `NO_REGRESSION_GUARD` 冲突，状态保持 `LANDED/CONFIRMED` 不倒退；
   干净航次（SEM001/SEM008）冻结时固化独立的航次作业快照，结算可追溯每项作业的纳入/排除原因。

## API 概览

| 分组 | 接口 | 说明 |
| --- | --- | --- |
| 01-事件接入 | `POST /api/ingest/events` · `POST /api/ingest/replay/{sample}` · `GET /api/ingest/events` | 事件入账（幂等）、本地样例回放、事件账查询 |
| 02-湖仓提交 | `POST /api/commits/generate[-all]` · `GET /api/commits` · `GET /api/commits/conflicts` · `POST /api/commits/conflicts/{id}/resolve` | 不可变提交生成、冲突查询与裁定 |
| 03-航次清单 | `POST /api/manifests/bookings` · `POST /api/manifests/freeze` · `GET /api/manifests/{id}` | 订舱登记、截关快照冻结、清单明细（含提交边界） |
| 04-修订候选 | `GET /api/revisions` · `POST /api/revisions/{id}/decide` | 截关后更正候选查询与裁定 |
| 05-对账 | `POST /api/recon/receipts[/replay/{sample}]` · `POST /api/recon/compare` · `POST /api/recon/diffs/{id}/adjust|ignore` | 回执入账、逐项比对、差异处理 |
| 06-调度视图 | `GET /api/dispatch/containers/{no}` · `GET /api/dispatch/containers/{no}/lineage` · `GET /api/dispatch/partitions/{p}/containers` | 当前合并视图（逐字段溯源 + 语义三层分区）、事件溯源、分区堆位 |
| 07-作业语义编译 | `POST /api/semantic/compile[-all]` · `GET /api/semantic/containers/{no}/view` · `GET /api/semantic/dispatch-board` · `GET /api/semantic/facts[/history]` · `GET /api/semantic/facts/{id}/traceability` · `GET /api/semantic/conflicts` · `GET /api/semantic/chargeable` · `GET /api/semantic/runs` · `GET /api/semantic/rules` | 语义编译、三层分区视图、事实版本历史、作业溯源、冲突、可收费汇总、编译版本、设备规则 |
| 08-结算 | `GET /api/settlement/manifests/{id}/charges` · `GET /api/settlement/manifests/{id}/adjustments` · `GET /api/settlement/manifests/{id}/job-snapshot` · `GET /api/settlement/quote`（拒绝演示） | 只能引用已冻结航次版本；作业快照追溯纳入/排除 |

## 本地事件样例（`src/main/resources/samples/`）

| 文件 | 内容 |
| --- | --- |
| `bookings.json` | 航次 V2026-11A 订舱 6 箱（CNU0001–CNU0006） |
| `gate-events.json` | 闸口放行 6 条（GATE#1–6） |
| `vessel-events.json` | 装船确认 6 条（VESSEL#1–6） |
| `crane-events.json` | 场桥移动 8 条（CRANE#1–7,9；含 CNU0002 迟到重复报文、CNU0003 乱序报文） |
| `yard-events.json` | 堆场盘点 2 条（YARD#1–2） |
| `correction-events.json` | 截关后更正 1 条（CRANE#8：CNU0005 漏报移箱，业务时点在截关前） |
| `carrier-receipts.json` | 承运人 MASK-LINE 回执 5 份（CNU0004 主张未装船、CNU0006 缺失） |
| `semantic-1-complete.json` | 语义场景1：SEM001 完整 HOIST→TRAVEL→DROP 吊运链 |
| `semantic-2-missing-drop.json` / `semantic-3-backfill.json` | 语义场景2/3：SEM002 缺失落箱 → 离线 45 分钟补传 |
| `semantic-4-device-overlap.json` | 语义场景4：QC-03 时间重叠操作 SEM004/SEM005 |
| `semantic-5-impossible-trajectory.json` | 语义场景5：SEM006 跨堆位无吊起落箱、SEM007 同时点互斥堆位双落箱 |
| `semantic-6a-confirmed-landed.json` / `semantic-6b-late-hoist.json` | 语义场景6：SEM008 已证实落箱后的晚到吊起（防倒退） |

## 关键设计

- **排序键**：`(bizTime, sourceSystem, sourceSeq)`，与入库先后无关 → 后到的消息无法覆盖先到的装船确认。
- **冲突检测**（箱号维度跨分区）：业务时点并列且效果冲突（`BIZ_TIME_TIE`）、来源序号回退却声称更晚业务时点
  （`SOURCE_SEQ_REGRESSION`）→ 相关事件 `PENDING_ARBITRATION`，不产生提交；人工裁定胜选事件后重新生成提交。
- **不可变性**：`lake_commit`、`manifest_item` 禁止 UPDATE/DELETE，`ingest_event`、`voyage_manifest` 禁止 DELETE
  （数据库触发器兜底，应用层亦无相应接口）。
- **冻结原子性**：完整性校验（无未提交/待裁定的截关前事件）+ 单事务写入清单与清单行 → 要么完整发布，要么零残留。
- **可解释性**：清单行 `basisCommitIds` 记录费用基于哪版提交；费用调整单 `basis` 记录清单行/提交/触发事件/对账差异。
- **串行化**：同一箱号提交生成、同一航次冻结分别使用 PostgreSQL 咨询锁（`pg_advisory_xact_lock`）。
- **设备阶段规则**（`semantic_phase_rule` 表，可按设备类型维护）：场桥为 HOIST（吊起）→TRAVEL（移动）→DROP（落下），
  允许转换 `HOIST→TRAVEL / HOIST→DROP / TRAVEL→DROP`，最小必要证据为 HOIST+DROP，
  相邻阶段可容忍 30 分钟、作业开放上限 2 小时；闸口/盘点/装船为权威单点证据。
  每次编译把规则整体快照进 `semantic_compile_run`，规则后改不影响历史解释版本。
- **语义事实与置信等级**：完整链为 `COMPLETED/CONFIRMED`；缺落箱且在开放时限内为 `IN_FLIGHT/MEDIUM` 推断，
  超时限为 `INTERRUPTED/LOW`；离线补传闭合但阶段超容忍窗的，降级 `COMPLETED/HIGH`。
  推断事实都写明**撤销规则**（后续 DROP 到达如何升级、何时转待核），且推断永远不能倒退已证实事实。
- **三类语义冲突**：`DEVICE_OVERLAP`（同设备吊运区间重叠，跨箱对称留痕）、
  `DUAL_SLOT_DROP`（同时点互斥堆位落箱 / 无新吊起的跨堆位轨迹）、`PHASE_ORDER`（违反允许转换或缺吊起）；
  另有 `NO_REGRESSION_GUARD`（晚到未闭合吊起不得倒退已证实状态）。冲突作业置 `IMPOSSIBLE` 且 `chargeable=false`，
  可收费汇总（`GET /api/semantic/chargeable`）只纳入未被冲突阻止的事实，排除项必带 `excludeReason`。
- **版本化修正**：`semantic_fact`/`semantic_conflict` 禁止删除，UPDATE 仅允许 `ACTIVE→SUPERSEDED`
  （V3 迁移的表级触发器兜底）；每次编译产生单调递增的 `run_seq`，`GET /api/semantic/facts/history` 可核对全部解释版本。
- **状态分库保存**：事件接入（`ingest_event`）、语义编译（`semantic_*`）、调度视图（读时合并）、
  航次快照（费用快照 `manifest_item` 与作业快照 `manifest_job_snapshot*` 分表）各自独立保存状态，互不覆盖。
- **每个事实可追溯**：`source_event_ids` 保留事实使用的原始事件范围，
  `GET /api/semantic/facts/{id}/traceability` 返回 INCLUDED/EXCLUDED 结论、原因与原始事件明细。

## 已执行的验证结论（本仓库交付前实际运行）

- `mvn test`：26 个用例全部通过（CommitConflictFlowTest 5 · ManifestFreezeFlowTest 4 · RevisionAndReconFlowTest 3 ·
  SecurityFlowTest 6 · SemanticCompilationFlowTest 8）。
- `./scripts/replay-acceptance.sh`：36 项断言全部通过（输出 `通过 36 项，失败 0 项`）。
- `./scripts/replay-semantic.sh`：40 项断言全部通过（输出 `通过 40 项，失败 0 项`）。
- `mvn package -DskipTests`：成功产出 `target/port-lakehouse-1.0.0.jar`（与 Dockerfile 构建阶段同一打包命令）。
- Docker 交付物：本环境无 Docker 守护进程，无法在容器内执行 `docker build`；
  `docker-compose.yml` 为有效 YAML，Dockerfile 经静态检查，其构建步骤（Maven 打包 + JRE 运行）已由本地等价命令实测，
  在装有 Docker 的宿主机执行 `docker compose up --build` 即可一键启动。

## 目录结构

```
├── Dockerfile / docker-compose.yml / docker/maven-settings.xml
├── scripts/replay-acceptance.sh          # 基线验收回放（36 项断言）
├── scripts/replay-semantic.sh            # 语义编译验收回放（40 项断言）
├── src/main/resources/db/migration/
│   ├── V1__init.sql                      # 基线 schema + 不可变触发器
│   ├── V2__semantic_compilation.sql      # 语义规则/事实/冲突/作业快照表
│   └── V3__fix_semantic_version_guards.sql  # 事实/冲突分表版本守卫修正
├── src/main/resources/samples/*.json     # 基线 + 语义五类场景事件样例
└── src/main/java/com/portops/lakehouse/
    ├── ingest/      接入服务（事件账）
    ├── commit/      湖仓写入（不可变提交、冲突裁定）
    ├── semantic/    作业语义编译（阶段规则、事实版本化、冲突检测、作业快照）
    ├── manifest/    航次清单（截关快照、修订候选）
    ├── recon/       对账（回执、比对、差异）
    ├── settlement/  结算（冻结版本引用、费用调整单、费用表、作业快照追溯）
    ├── dispatch/    调度查询（读时合并视图 + 语义三层分区）
    └── common/ config/  状态折叠器、异常、安全与 OpenAPI 配置
```

> 说明：`/api/dev/reset` 仅供本地验收重复回放，由 `DEV_RESET_ENABLED`（默认 `true`）控制，生产部署必须设为 `false`。
