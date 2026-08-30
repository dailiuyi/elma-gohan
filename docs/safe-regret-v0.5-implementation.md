# SafeRegret v0.5 实施日志

> 本文件是跨上下文接续入口。每完成一个可验证阶段后更新，不以聊天上下文作为唯一进度来源。

## 基线与兼容边界

- 仓库基线：`main` / `adb238f`（2026-08-30 开始实施时）
- 完整设计：`docs/safe-regret-v0.5-design.md`
- 前端和 OpenAPI 请求/响应契约保持不变
- 数据库迁移只追加 V9 及后续版本，不修改 V1～V8
- 工作区开始时已有未跟踪 `.claude/`，不属于本次改动

## 当前检查点

- 状态：阶段 0 与阶段 1 shadow 代码及两轮独立反馈修复完成；最新修复后的 PostgreSQL/Flyway/API 全量结果待独立 Agent 重新验收
- 当前任务：由独立验收 Agent 复跑全量测试和快照审计；通过后再收集新旧风险与排序分歧
- 已完成核心：reroll 状态/并发、exclude 选池语义、V9 全候选快照、RiskPosterior、SafeRegret、稳定分桶、动态 reroll 与 pairwise Evidence 纯领域核心
- 已接线：旧请求提交后异步计算 risk-v0.5 / recommendation-v0.5 shadow，仅查重和落盘使用有超时的短事务；失败不改变旧响应
- 尚未启用：risk-v0.5 门禁、recommendation-v0.5 响应、动态 reroll、成对偏好学习持久化
- 安全闩：`serving-enabled=true` 会在应用启动期失败，避免尚未接线时误标 CANDIDATE 或误以为已灰度

## 固定实施顺序

1. 阶段 0：reroll 状态、并发、反馈归属、exclude 快照语义
2. 阶段 1A：V9 决策快照与用户删除级联
3. 阶段 1B：RiskPosterior v0.5 shadow 计算与新旧分歧快照
4. 阶段 1C：shadow 可重放性和契约不变测试
5. 阶段 2：安全并集门禁和稳定服务端分桶，默认关闭 served v0.5
6. 阶段 3：SafeRegret、近似并列 softmax、质量受限 MMR
7. 阶段 4：动态 reroll 与成对偏好学习
8. 全量回归、文档、发布前生产/Flyway 实时核对

## 恢复规则

恢复工作时依次执行：

1. 阅读本文件与 `docs/safe-regret-v0.5-design.md` 第 13 节；
2. 执行 `git status --short`，保留用户和其他代理的现有改动；
3. 从“当前检查点”继续，不重做已通过验证的阶段；
4. 先运行当前阶段聚焦测试，再运行后端完整测试；
5. 每个阶段完成后在下方追加改动、验证结果和下一步。

## 阶段记录

### 2026-08-30 / 启动实施

- 已确认持久化设计与 `adb238f` 代码一致。
- 已确认算法基线为 `risk-v0.3.1`、`taste-v0.1`、`recommendation-v0.4.1`。
- 已确认业务代码尚未修改；设计文档为未跟踪新文件。
- 已启动阶段 0、V9 骨架和 RiskPosterior 核心的并行实施。

### 2026-08-30 / 阶段 0 完成

- reroll 使用 `recommendation_log` 悲观写锁，同一会话并发请求串行推进。
- 候选耗尽时记录最后候选的 REROLL，并把 `current_restaurant_id` 切回实际返回的首家。
- 已补耗尽后反馈归属与并发 reroll API 测试；测试源码编译通过。
- 本阶段曾把 `excludeRestaurantId` 前移到 Evidence/Risk 之前；后续独立审计发现会改变价格基线并可能排除唯一非 HIGH 候选，现已改为完整 Evidence/Risk 之后按可服务备选决定是否排除，见后续修复记录。
- API 集成断言尚未执行：本机 `localhost:5432` 没有测试 PostgreSQL；这是当前环境阻塞，不是断言失败。

### 2026-08-30 / 阶段 1 核心完成、接线中

- V9 新增 `recommendation_decision_snapshot`，外键随 recommendation log 级联删除。
- 新增 `risk-v0.5` Beta 后验核心与 Evidence 批量适配；NO_DATA/UNAVAILABLE 不贡献低风险伪计数。
- 新增 `recommendation-v0.5` 稳健遗憾、同安全层近似并列 softmax、真实 propensity 和质量受限 MMR。
- 新增后端稳定实验分桶；shadow 默认开启，serving 默认关闭且 rollout 为 0。
- 新增 pairwise 差分 Evidence 纯领域核心，尚未接入长期画像持久化。
- 正在新增 shadow 编排与 V9 全候选快照写入；旧响应仍由 v0.4.1 生成。

### 2026-08-30 / 阶段 1 shadow 接线完成

- `RecommendationService.create` 保持旧 v0.4.1 响应，在主事务成功提交后触发 shadow；当前计算在事务外执行，仅查重和 `saveAndFlush` 使用短 `REQUIRES_NEW`，异常不回滚或改变已服务结果。
- V9 快照以 `recommendation_log_id + experiment_key` 唯一，使用稳定 UUID，并在普通重试时直接返回已有快照。
- 快照保存 hard-filter 后的 pre/post exclude manifest、完整候选原始字段/Evidence、RiskPosterior 的 `h/q/weight` 派生因子、served 与 shadow 风险、排序、slot、propensity、选择或排除原因、seed、配置哈希及 schema 版本。
- 配置哈希包含 posterior、Evidence、SafeRegret、特征桥接、风险策略和实际使用的 Taste 投影参数，并使用稳定键序列化。
- quality 桥接只采纳 `AVAILABLE` 平台证据；百度评分还要求实体 `MATCHED`，避免 NO_DATA 或误匹配抬分。
- `risk-v0.5` 只把可信高风险判为 BLOCKED；缺失证据保持 UNCERTAIN，不伪装为安全，也不直接制造额外风险罚分。
- `recommendation-v0.5` 已实现非包含预算下界、包含预算上界、稳健遗憾、可信安全优先、仅近同分 seeded softmax、真实首选 propensity 和质量受限 MMR。
- 隔离的 REROLL 不再直接污染长期 Taste；动态 reroll 与 A→B pairwise 差分更新已实现并测试，但阶段 1 不改变现有 reroll/画像行为。
- 39 个非数据库测试类共 `169 tests / 0 failures / 0 errors / 0 skipped`。
- `RecommendationApiTest` 已补 V9 版本/全候选断言、用户删除级联、reroll 耗尽反馈归属与并发推进；测试源码编译通过，但本机 `localhost:5432` 无 PostgreSQL，尚未执行真实 Flyway/JPA/API 断言。
- `contracts/validate_openapi.py` 当前因本机缺少 PyYAML 未运行成功；本轮没有修改 `contracts/` 或前端 `src/`。
- 当前改动未提交、未部署；工作区原有 `.claude/` 仍不属于本次改动。

### 2026-08-30 / 真实 PostgreSQL 集成验收通过

- 本机 PostgreSQL 17 服务可用，使用独立测试库 `elma_test` 完成真实 Flyway/JPA/API 验收；凭据仅进入验收 PowerShell 进程环境，结束后清除，未写入仓库或日志。
- Flyway `V9__recommendation_decision_snapshot.sql` 成功登记，`recommendation_decision_snapshot` 表存在；API 测试实际写入并读取 V9 快照，同时覆盖用户删除级联。
- 首次全量运行暴露 `target/classes` 中残留旧 V3 迁移的问题；改为 `mvn clean test` 后消除生成物污染。后续迁移验收必须从 clean 开始。
- 第二次运行暴露测试 POI 的声明距离与坐标不一致：缓存按坐标重算后 8 家均约 568 米。已修正 fixture 坐标，使 280～840 米声明距离与坐标一致，未放松业务断言，也未改变生产过滤逻辑。
- 最终结果：40 个测试报告、`192 tests / 0 failures / 0 errors / 0 skipped`；验收脚本状态 `0`。
- 打包成功：`backend/target/gohan-backend-1.0.0.jar`，大小 `58,863,589` 字节，SHA-256 `2BD0993324F513E88899ACC2BA31A864FB01CDE6051DD562F0C9BA7DE3248537`。
- `git diff --check` 通过；当前改动仍未提交、未部署，serving 安全闩保持关闭。

### 2026-08-30 / 独立验收反馈修复

- `RiskPosterior` 从正态近似改为 Beta 逆分布真分位数；默认保守风险和区间上界为 Q80，边界 `Beta(8.08, 7.92)` 不再被错误放过。
- SafeRegret 在没有可信安全候选时改为确定性选择 `conservativeRisk` 最低者，并记录 `LOWEST_CONSERVATIVE_RISK_FALLBACK`；小候选池 P90 改为线性插值，不再退化为最大值。
- `excludeRestaurantId` 移到完整价格基线、Evidence 和旧风险计算之后；只有存在其他非 HIGH 候选才排除，避免旧前端把唯一可服务候选排掉。Flavor/Taste 特征也按 hard-filter 全集冻结。
- V9 `candidates` 改为保存 pre-exclude 的全部 hard-filter 候选；schema 2 把 exclude 前 `shadowCounterfactual` 与 exclude 后 `shadowActual` 的 score/rank/breakdown/propensity/slot 完整分开，被排除项的 actual 为空并显式记录原因。
- shadow after-commit 改为独立有界线程池入队，拒绝时不回压 HTTP 线程；失败最多重试 3 次，并通过 `elma.safe_regret.shadow.dispatch{outcome=queued|completed|retried|failed|dropped}` 观测缺失与积压结果。
- quality bridge 复用 risk rating 的 freshness 半衰期；未来时间或无时间的平台评分不再通过餐厅 fallback 绕过 freshness，百度质量权重同时包含实体匹配置信度和 freshness。
- `experimentKey` 在绑定和启动校验中限制为 1～64 字符，与 V9 `VARCHAR(64)` 一致。
- 停止把孤立 REROLL 写入长期 Taste 属于算法行为变化，因此 Taste 版本升为 `taste-v0.2`；实时首推和 reroll 响应均读取实际配置/日志版本，不再硬编码 `taste-v0.1`。OpenAPI 只更新版本示例，未改变字段或请求入参，前端 `src/` 未修改。
- 修复自检仅包括：全部测试源码编译，以及 8 个相关测试类共 `45 tests / 0 failures / 0 errors / 0 skipped`。这是局部修复检查，不是验收结论；未在本轮复跑 PostgreSQL/API 全量验收。
- 当前改动未提交、未部署，serving 安全闩保持关闭；前一节的 192-test 结果发生在本节修复之前，不能作为修复后的验收证据。

### 2026-08-30 / 第二轮独立验收反馈修复

- 平台偏差中位数校准新增高杠杆样本资格：高德与百度两端都必须通过与候选风险因子相同的 freshness 函数，且 freshness 至少为 `0.25`。默认半衰期 30 天，因此未来、空时间和超过 60 天的配对不能改变批次 bias 或其他候选的 residual trust。
- `RiskPosterior.confidence` 按设计改为概率支持 `[0,1]` 上的 `1 - (Q80 - Q20)`；纯先验约 `0.70742`，`Beta(8.08, 7.92)` 约 `0.7887`，门禁同步重校准为 `0.75`。旧测试不再假设“相同 evidence mass 必有相同 Beta 区间宽度”。
- 默认运行时加入 Prometheus registry，并暴露 `health,prometheus`；五类 shadow counter 可从 `/actuator/prometheus` 采集，不再只是进程内注册。
- shadow 调度与实际 capture 分成两个各 2 worker 的有界 daemon 池；一次任务共享 3 秒墙钟预算，超时取消 capture，查重和 `saveAndFlush` 各自在 2 秒短 `REQUIRES_NEW` 中执行。CPU 计算、两次排序和 JSON 序列化不再持有数据库事务/连接；严格 shutdown、立即中断和 daemon worker 共同保证任务不会无限阻止 JVM 退出。
- V9 payload 升为 feature schema 2：每个候选分别冻结 pre-exclude counterfactual 和 post-exclude actual 两套决策视图，不再把全集 P90 分数与实际池 slot/objective 混在同一行语义中。
- 根 README 的当前 Taste 说明改为 `taste-v0.2`，并保留历史会话返回自己落库版本的边界。
- 外部 Agent 在本轮修复前报告的全量结果为 `201 tests / 1 failure / 0 errors`；失败是旧区间宽度断言。该结果只作为问题来源，不是当前修复后的验收证据。
- 本轮已完成全部测试源码编译，并运行 15 个直接相关测试类共 `72 tests / 0 failures / 0 errors / 0 skipped`；快照测试还单独复跑，确认 pre/post-exclude P90 分数确实独立计算。这里只是修复自检，不代替独立 PostgreSQL/Flyway/API 全量验收。当前改动仍未提交、未部署，serving 安全闩继续关闭。

### 2026-08-30 / 修复后发布前全量验收

- 使用仓库内隔离的 PostgreSQL 17.11 实例和空库 `elma_test`，从 `mvn -B clean test` 开始完成真实 Flyway/JPA/API 全量验收；Flyway V1～V9 全部成功，V9 history 为 success，`recommendation_decision_snapshot` 表存在。
- 最终结果：`213 tests / 0 failures / 0 errors / 0 skipped`，`BUILD SUCCESS`。该结果覆盖第二轮反馈修复后的完整代码，不再复用此前的局部测试结论。
- OpenAPI 校验通过：`CONTRACT_OK openapi=3.0.3 operations=6 schemas=25`；`git diff --check` 无空白错误，仅有 Git 的 LF/CRLF 工作区提示。
- 发布包生成成功：`backend/target/gohan-backend-1.0.0.jar`，大小 `63,471,467` 字节，SHA-256 `77AB2C2399BDC2034A4DB9E34988558AF6BC4CC26C55E46B182482AA7000BE4B`。
- serving 安全闩继续关闭；生产激活前仍必须实时核对服务器当前应用版本与 Flyway 状态，不能仅凭本地通过直接覆盖。

### 2026-08-30 / 生产发布完成

- 功能提交 `5bff1ff` 已推送到 GitHub `main`，本地与远端哈希一致；生产发布包 SHA-256 为 `77AB2C2399BDC2034A4DB9E34988558AF6BC4CC26C55E46B182482AA7000BE4B`。
- 发布前确认生产服务为 `active/running`、Java 17、PostgreSQL 数据库 `elma`、Flyway 当前版本 8；生产旧 JAR 与新 JAR 的 V1～V8 SQL 在规范化换行后逐文件哈希一致，新版本仅追加 V9。
- 新 JAR 先在 `127.0.0.1:18081` 以零流量 canary 启动，使用真实生产配置成功迁移 V8→V9并返回健康 `UP`，随后正常停止；正式 8081 服务在 canary 期间持续在线。
- 旧 JAR 已备份为 `/opt/elma-gohan/releases/app-before-5bff1ff-68464144.jar`，SHA-256 为 `6846414428B63E1EC5BA982C33E78CA8210971F0A6464FFF934AE9A28215FC3F`；正式 JAR 通过临时文件加原子 `mv` 切换并重启。
- 发布后确认：systemd `active/running`、`/actuator/health` 为 `UP`、Flyway V9 success、`recommendation_decision_snapshot` 存在、`/actuator/prometheus` 返回五类 shadow counter，重启后无 warning 日志。
- 无副作用 API 冒烟测试通过：缺失经纬度的 POST 在直连 8081 和 Nginx 8080 均返回预期 `400 VALIDATION_FAILED`，健康检查随后仍为 `UP`。serving 安全闩保持默认关闭，v0.5 仍只运行 shadow。

## 下一次恢复入口

1. 阅读本文件“当前检查点”和最后一条阶段记录；不要重新实现已通过的纯领域核心。
2. 独立验收 Agent 在 `backend/` 使用测试数据库凭据执行 `mvn clean test`；不得复用可能含旧迁移的 `target/classes`，并等待异步 shadow 快照落库后断言。
3. 从 `recommendation_decision_snapshot` 收集新旧风险分歧、无可选率、首推变化、propensity 和请求尾延迟。
4. 未拿到 shadow 观测与风险抽样复核前，不移除 serving 安全闩，不让 v0.5 参与响应。
5. 发布前重新核对目标环境当前应用版本和 Flyway 状态；本日志不能代替实时检查。
