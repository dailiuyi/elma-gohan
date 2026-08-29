# SafeRegret v0.5 后端算法设计与审计交接

> 状态：阶段 0 与阶段 1 shadow 已实现；v0.5 尚未参与线上响应
> 审计日期：2026-08-30
> 审计基线：`main` / `adb238f`
> 当前算法：`risk-v0.3.1`、`taste-v0.1`、`recommendation-v0.4.1`

## 1. 目标与兼容边界

本次目标是在不修改前端、尽量不增加入参的条件下，重点改进客观 Risk 和推荐选择逻辑。

建议的新方案命名为 **SafeRegret v0.5**：

1. 可靠度感知的风险后验；
2. 安全约束下的稳健遗憾排序；
3. 有质量损失上限的多样化候选池；
4. 利用 reroll 和后续接受行为进行成对偏好学习。

以下接口保持不变：

- `CreateRecommendationRequest` 不增加字段；
- `RecommendationResponse` 不增加前端必需字段；
- 继续使用 `X-Anonymous-User-Id`；
- reroll、feedback、behavior、deep-evidence 路径不变；
- 风险仍输出 `riskScore`、`riskLevel`、`confidence`、`reasons` 和版本号。

可直接复用的现有输入和服务端数据：

- 位置、距离区间、预算区间、品类、dislikes、excludeRestaurantId；
- 匿名用户 TasteProfile、近 30 天饮食历史；
- RECOMMENDED、REROLL、ACCEPT、NAVIGATE、SKIP；
- LIKE、NORMAL、DISLIKE 和口味标签；
- 高德 POI、百度结构化 Evidence、实体匹配置信度；
- 候选级风险、分项得分、随机 seed 和 selection snapshot；
- 已缓存的按需 Deep Evidence。

## 2. 当前实现的关键发现

### 2.1 默认评论风险信号实际上处于休眠状态

`application.yml` 默认使用 File Evidence，而正式资源文件为：

```json
{
  "restaurants": []
}
```

因此默认链路中的模板评论、评论突发、近期评论趋势基本恒为未观测；实际 Risk 主要由高德/百度结构化评分、字段缺失、实体匹配和跨平台差异组成。

相关代码：

- `backend/src/main/resources/application.yml:134-137`
- `backend/src/main/resources/evidence/restaurant-evidence.json:1-3`
- `backend/src/main/java/com/elma/gohan/domain/risk/RuleBasedRiskEngine.java:75-111`

### 2.2 Risk 把“坏的可能性”和“证据不足”混在一起

当前六因子线性加权包括 rating、template、burst、trend、dataInsufficient 和 crossPlatformConflict。数据缺失既增加 `dataInsufficientRisk`，又降低 confidence；随后推荐层又把低 confidence 风险向固定的 50 收缩。

后果：

- 缺少评论时，template/burst/trend 被当成零风险，而不是未知；
- 数据缺失可能在 quality、risk、budget、confidence 多条路径重复影响排序；
- 60 分可参与排序，61 分无论置信度如何都会被完全删除；
- 高德和百度评分直接取平均，未考虑评论量、平台系统偏差和实体匹配置信度；
- 相对高价被放进客观 Risk，但它更接近 value/budget 问题。

相关代码：

- `RuleBasedRiskEngine.java:80-111,148-233,276-283`
- `RiskProperties.java:236-255,272-281`
- `LowRegretScorer.java:112-115`
- `RecommendationEngine.java:34-37`

### 2.3 首推是 raw score 加权彩票，不是最高分

当前引擎先按 LowRegretScore 排序，再把 Top-K 按 diversity key 分组；每组内部使用：

```text
weight = max(1, lowRegretScore)
```

进行加权随机抽取，第一组抽中的项直接成为 slot 1。

所以同组 80 分相对 70 分只有 `80/70 = 1.14` 倍优势。若同组有九家 70 分和一家 80 分，80 分候选的首推概率约为：

```text
80 / (80 + 9 × 70) = 11.27%
```

这与“直接替用户选出低后悔的一家”不一致；原始分数的整体平移也会改变抽取概率，说明它不适合作为概率权重。

相关代码：

- `DefaultRecommendationEngine.java:48-63,76-85,117-143`
- `WeightedRandomSelector.java:18-47`

### 2.4 Exploration bonus 是事后加分

探索候选先在 `planSelection` 中选定并放入首位，之后才调用 `withExploration` 加 5 分。因此 bonus 不参与“选谁”，只改变最终落库分数和分项快照。

这会导致同一候选同时存在：

- selection snapshot 中的探索前分数；
- recommendation candidate 中的探索后分数。

相关代码：

- `DefaultRecommendationEngine.java:63-72,108-143`
- `LowRegretScorer.java:83-95`

### 2.5 Taste 更新存在多特征归因混杂

一次 LIKE、NORMAL 或 DISLIKE 会使用同一个 delta 同时更新：

- category；
- price band；
- distance band；
- flavor tags。

ACCEPT、NAVIGATE、REROLL、SKIP 也沿用相同结构。用户可能只是因为距离远而 reroll，但系统会同时惩罚该品类和价格档，长期会制造伪相关。

相关代码：

- `TasteProfile.java:61-114`
- `BehaviorService.java:100-136`

### 2.6 reroll 耗尽存在反馈归属缺陷

候选耗尽后，`reroll()` 返回 `candidates.get(0)`，但没有把 `recommendation_log.current_restaurant_id` 更新回首家。反馈接口根据该字段确定当前餐厅。

因此可能出现：页面展示首家 A，但用户反馈实际写给最后一家 F。

必须在算法学习前先修复，否则会污染 Taste、饮食历史、行为指标和后续离线评估。

相关代码：

- `RecommendationService.java:292-317`
- `RecommendationService.java:320-360`
- `RecommendationApiTest.java:271-305`（现有测试没有在耗尽后继续提交反馈）

### 2.7 当前历史快照不足以严格回放新算法

`selection_snapshot_json` 位于旧 HIGH 过滤之后，只保存 POI key、diversity key、旧总分、Top-K 和探索资格。完整风险及推荐分项只保存最终最多六家；restaurant 和 Evidence 缓存还可能被后续请求覆盖。

因此现有历史数据不能精确回答：

- 新风险算法会不会放过旧 HIGH；
- Top-K 外候选用新算法能否成为首推；
- 新旧算法在同一原始 Evidence 上的精确反事实差异；
- 某个随机候选的真实选择概率。

## 3. SafeRegret v0.5 总体流程

```text
现有请求与匿名 UUID
  → 硬约束与候选召回
  → 多源 Evidence
  → 风险后验（均值 + 区间 + 可靠度）
  → 保守安全门
  → 稳健遗憾排序
  → 确定性首推
  → 有质量下限的 MMR 候选池
  → reroll / accept / navigate / feedback
  → 成对偏好更新
```

## 4. Risk v0.5：可靠度感知的风险后验

### 4.1 因子同时包含风险强度与可靠度

对每个证据信号 `k` 定义：

- `h_k ∈ [0,1]`：该信号指向坏体验的强度；
- `q_k ∈ [0,1]`：该信号本身的可靠度。

```text
q_k = availability
    × entityMatchConfidence
    × sampleSaturation
    × freshness
    × sourceTrust
```

批次内平台系统偏差校准比单候选因子具有更大的影响范围，因此校准样本还必须通过 freshness 资格线。默认评分 freshness 半衰期为 30 天，只有高德、百度两侧 `freshness >= 0.25`（即均不超过两个半衰期）且实体匹配置信度不低于 `0.78` 的配对，才能进入中位数及 `calibrationPairCount`。`observedAt` 为空、位于未来或超过该窗口的配对只按各自 freshness 参与单候选计算，不得影响批次偏差及其他候选的 residual trust。

使用 Beta 伪计数形成后验：

```text
alpha = kappa × priorMean + Σ(m_k × h_k)
beta  = kappa × (1 - priorMean) + Σ(m_k × (1 - h_k))
m_k   = configuredWeight_k × q_k

RiskPosterior ~ Beta(alpha, beta)
```

`priorMean` 初期使用全局/品类稳健先验，后续只能用客观或经审计的弱标签校准，不能直接把单个用户 DISLIKE 当食安或客观风险真值。

### 4.2 重新定义现有因子的职责

| 因子 | v0.5 职责 |
| --- | --- |
| 平台评分 | 连续风险强度，按平台和样本量收缩 |
| 近期下降 | 调整当前坏体验概率，按样本量收缩 |
| 模板评论 | 降低评论证据可信度 |
| 评论突发 | 降低评论证据可信度；先过滤未来时间和极老离群值 |
| 跨平台冲突 | 扩大区间/降低来源可信度；先扣除批次内平台系统偏差 |
| 数据缺失 | 只降低 confidence，不重复制造 risk penalty |
| 相对高价 | 移入推荐层的 `valueFit`，不参与客观 HIGH 门禁 |
| 缓存 Deep Evidence | 可选低权重弱证据，不同步阻塞主请求 |

### 4.3 输出与门禁

风险均值表示最可能的风险，区间表示证据不确定性。用于展示和排序的保守风险可采用后验 `Q80`：

```text
conservativeRisk = Q80(RiskPosterior)
riskScore = round(100 × conservativeRisk)
credibleIntervalWidth = Q80(RiskPosterior) - Q20(RiskPosterior)
normalizedCredibleIntervalWidth = credibleIntervalWidth / (1 - 0)
confidence = 1 - normalizedCredibleIntervalWidth
```

风险后验的概率支持固定为 `[0,1]`，因此区间宽度本身已经归一化；confidence 直接反映当前 Q20–Q80 区间的集中程度，不再使用 `evidenceMass / (priorStrength + evidenceMass)` 代替。相同 evidence mass 但风险强度不同的 Beta 后验可以有不同区间宽度，测试不得继续要求其 confidence 或区间宽度完全相等。

默认先验 `Beta(3.2, 4.8)` 的 confidence 约为 `0.7074`，而风险门禁边界样例 `Beta(8.08, 7.92)` 的 confidence 约为 `0.7887`。因此 v0.5 的 `minimumDecisionConfidence` 重新校准为 `0.75`：纯先验/无证据候选仍为不确定，具有足够证据且 Q80 达到 `0.61` 的候选仍可进入可信 HIGH 门禁。此数值必须继续通过 shadow 分层观测校准，不得沿用旧 confidence 量纲直接比较。

门禁分层：

1. 可信 HIGH：直接剔除；
2. 可信安全：可进入首推和探索；
3. 不确定：不等于 HIGH，但增加一次统一 uncertainty penalty，默认不进入探索；
4. 若没有可信安全候选，选择保守风险最低者并明确“信息相对有限”。

## 5. Recommendation v0.5：安全约束的稳健遗憾

### 5.1 可行性先于排序

先按以下层级建立候选集合：

1. 明确满足距离、品类、dislikes、营业和预算约束；
2. 价格等关键字段未知的 fallback；
3. 明确违反约束的候选删除。

只要第 1 层足以形成候选池，第 2 层不得成为首推。这样既不会把缺失误判为超预算，也不会让未知价格冒充满足预算。

### 5.2 避免强项完全补偿短板

把安全、质量、Taste、预算、距离、近期重复统一映射到 `[0,1]` utility。对每一维使用候选集的稳健目标（例如 P90，而不是可能异常的单个最大值）：

```text
regret_ij = max(0, target_j - utility_ij)

RobustRegret_i =
    lambda × max_j(weight_j × regret_ij)
  + (1 - lambda) × Σ(weight_j × regret_ij)
  + uncertaintyPenalty_i

SafeRegretScore_i = 100 × (1 - RobustRegret_i)
                     - recentExposurePenalty_i
```

含义：

- 第一项限制候选最差短板；
- 第二项保留总体表现；
- 安全同时是进入候选集的约束；
- Taste 权重随画像置信度增长；
- 缺失值使用先验并只增加一次不确定性惩罚；
- 预算区间主要表达可满足约束，不再暗中假设越便宜越好；
- 距离可改为绝对步行时间的饱和函数，避免仅因扩大 radius 而涨分；
- recent diversity 与 recent penalty 合并为一个连续时间衰减项。

### 5.3 首推只在近似并列时随机

正常情况下，slot 1 直接选择最低 RobustRegret 的可信安全候选。

只有同一安全层且分数差不超过配置阈值，或可信区间明显重叠时，才使用可重放 softmax/Gumbel：

```text
P(i) proportional to exp((score_i - bestScore) / temperature)
```

必须保存真实 propensity，才能进行 IPS/SNIPS 等离线评估。

### 5.4 候选池使用带质量上限的 MMR

首推之后，依次选择：

```text
next = argmax(
    SafeRegretScore(candidate)
  - diversityWeight × maxSimilarity(candidate, selected)
)
```

同时增加质量下限：

```text
candidate.score >= bestRemaining.score - maxDiversityLoss
```

相似度可直接使用现有服务器数据：

- category；
- flavor tags；
- price band；
- distance band。

这样可以给出真正不同的 reroll 候选，但不会为了凑品类均衡让明显较差者挤掉高分项。

探索最多占一个 slot，必须满足可信安全和最大分差限制；探索价值必须在选择前进入 objective，不能事后修改总分。

## 6. Reroll 与成对偏好学习

### 6.1 会话内动态 reroll

候选集合仍可冻结，但 reroll 顺序不必机械遵循 slot。用户拒绝 A 后，从未展示候选中选择：

```text
next = argmax(
    baseScore
  - similarityToLastRejected
  - similarityToAllRejected
)
```

这让下一家在品类、价格、距离或口味上真正产生变化。

### 6.2 长期画像改为成对更新

可直接利用现有行为形成偏好对：

```text
REROLL A → ACCEPT/NAVIGATE/LIKE B
得到 B > A
```

只更新 `feature(B) - feature(A)` 中不同的部分：

- 品类相同，不更新 category；
- 价格档不同，才给 price evidence；
- 距离档不同，才给 distance evidence；
- 用户明确选择的 flavor 才更新 flavor。

孤立的 reroll 只做本会话临时降权，不立即同时惩罚所有长期特征。每个 feature 单独维护 exposure、positive、negative、support/confidence，不能继续只使用一个全局 confidence。

## 7. Deep Evidence 的使用方式

Deep Evidence 默认关闭且存在网络时延、成本和选择偏差，不应直接放进所有推荐请求的同步主链路。

建议：

1. 主请求优先复用未过期缓存；
2. 对“排名分差小且风险不确定性高”的候选计算 Value of Information；
3. 只对最高 VOI 的 1～2 家异步刷新；
4. 刷新结果服务于未来推荐，不阻塞当前响应；
5. 用户主动 deep-evidence 仍返回独立详情，但其结果可进入后续风险快照。

近似策略：

```text
VOI = riskUncertainty × rankFlipProbability × candidateImpact / acquisitionCost
```

## 8. Shadow 日志与数据库建议

当前最高迁移为 V8。实现时只追加 V9，不改写 V1～V8。

建议新增 `recommendation_decision_snapshot`，以 `recommendation_log_id` 外键并 `ON DELETE CASCADE`，保存：

- experiment variant；
- served/shadow 算法版本；
- 所有硬过滤后候选，而非只保存最终六家；
- 决策时餐厅原始字段和 Evidence 状态；
- 新旧 risk mean/upper/confidence/block；
- 新旧 recommendation breakdown/rank；
- hard filter、risk block、Top-K 等排除原因；
- seed、propensity、config hash；
- pre/post exclude 顺序；
- 最终 slot 和 MMR 选择理由；
- feature schema version。

feature schema 2 必须把同一候选的两种决策上下文分开冻结：`shadowCounterfactual` 使用 pre-exclude 全集重新计算目标/P90，`shadowActual` 使用 post-exclude 实际池重新计算并保存 score、rank、breakdown、首选 propensity、slot、selection propensity、objective 与 reason。被请求排除的候选仍保留完整 counterfactual，但 actual 必须为空并记录 `EXCLUDED_BY_REQUEST`；不得把全集分数与实际池 slot 混成一套字段。

阶段 1 的可用性隔离也是快照契约的一部分：主事务 `afterCommit` 只允许向有界队列提交，计算、排序和 JSON 序列化不得持有数据库事务；查重与落盘使用独立短事务和明确超时。单次 capture 需要共享墙钟预算，超时取消；worker 必须有界且不能在关闭时无限阻止 JVM 退出。默认部署必须提供可采集的 queued/completed/retried/failed/dropped 指标，本实现通过 Prometheus Actuator endpoint 暴露。

新增的用户关联日志必须随 `/api/v1/users/me/data` 删除；共享餐厅和公共 Evidence 仍保留。

## 9. 评估指标

### 9.1 推荐指标

- 首家 ACCEPT / NAVIGATE；
- 首次 reroll 率；
- 每会话 reroll 数；
- 任一候选 ACCEPT / NAVIGATE；
- SKIP、DISLIKE；
- 候选耗尽率；
- diversity 带来的分数损失；
- 首推确定性和重放一致性。

必须按以下维度分层：

- cold/warm user；
- risk confidence；
- Evidence 状态；
- 品类；
- recall 是否 incomplete；
- normal/exploration；
- 首家与 reroll 后候选。

当前 `v_recommendation_metrics` 使用 recommendation log 的首家 selection mode 聚合整个会话；用户 reroll 后接受另一种 mode 的候选时会错归因。新指标应按实际 `recommendationId + restaurantId` 曝光候选统计。

### 9.2 Risk 指标

`DISLIKE` 是口味、距离、价格、服务等混合弱标签，不能证明食品安全或欺诈风险。

Risk 评估应分两层：

1. `DISLIKE after NAVIGATE` 等仅作为弱代理；
2. 对按 score/confidence/Evidence 状态分层抽样的餐厅做盲审或后续 Evidence 复核，再统计校准误差、误杀和漏放。

建议指标：

- Brier score / ECE（只在有合格标签时）；
- 旧 HIGH / 新非 HIGH 分歧；
- 旧非 HIGH / 新 HIGH 分歧；
- 不确定候选占比；
- Evidence 来源覆盖；
- HIGH 曝光率必须为 0；
- P95 延迟和外部调用量。

## 10. 推荐实施顺序

### 阶段 0：先修数据污染缺陷

1. 修复 reroll 耗尽时的 current restaurant 状态；
2. 增加耗尽后反馈归属测试；
3. 处理并发 reroll，避免重复选择同一未展示候选；
4. 明确 exclude 在选池前生效或保存 post-exclusion manifest。

### 阶段 1：只做观测，不改变响应

1. 新增 V9 shadow snapshot；
2. 保存全部候选和完整特征；
3. 100% 同请求计算 v0.4.1 与 v0.5；
4. 继续只返回 v0.4.1；
5. 检查延迟、分数分布、风险门禁分歧和快照可重放性。

### 阶段 2：Risk 灰度

1. 初期采用“旧 HIGH 或新 HIGH 都不展示”的安全并集门禁；
2. 以 anonymous UUID 的服务端 hash 稳定分桶；
3. 1% → 5% → 20% → 50% → 100%；
4. 保留一键回到旧算法的配置开关。

### 阶段 3：Recommendation 灰度

1. 先启用确定性首推；
2. 再启用近似并列 softmax；
3. 再启用带质量上限的 MMR；
4. 最后启用动态 reroll 和成对偏好学习。

## 11. 必补测试

### Risk

- 风险强度单调性；
- 证据质量增加时区间收窄；
- NO_DATA 与明确低风险不等价；
- 未来评论和极老离群时间不进入 burst/trend；
- 平台偏差校正后残差冲突；
- 纯未知候选不伪装为零风险；
- HIGH 永不进入 served pool；
- 所有后验结果可重放。

### Recommendation

- 明显最高分候选默认成为首推；
- 仅近似并列项具有非零随机概率；
- propensity 与实际选择一致；
- exploration objective 在选择前生效；
- MMR 不超过配置的质量损失上限；
- reroll 后候选与被拒项更不相似；
- 成对更新只影响不同特征；
- 缺失字段只产生一次不确定性惩罚。

### API / persistence

- 旧请求与响应契约不变；
- shadow 模式响应字节级不变；
- 同一 seed 可重放；
- 耗尽后反馈归属正确；
- 同用户实验分桶稳定；
- 删除用户数据时 shadow 日志级联删除。

## 12. 设计阶段验证与工作区状态（历史记录）

本轮重跑以下 10 类算法测试：

- RuleBasedRiskEngineTest
- RuleBasedRecentTrendDetectorTest
- SlidingWindowBurstDetectorTest
- JaccardTemplateCommentDetectorTest
- CrossPlatformConsistencyAnalyzerTest
- LowRegretScorerTest
- DefaultRecommendationEngineTest
- RecentFoodHistoryTest
- TasteProfileTest
- WeightedRandomSelectorTest

结果：`40 tests / 0 failures / 0 errors / 0 skipped`。

通过只说明当前实现满足当前测试预期，不反驳本文件指出的概率语义、特征归因、数据可用性和状态一致性问题。

以上是设计阶段的基线记录；实现后的最新验证以
`docs/safe-regret-v0.5-implementation.md` 为准。工作区原有未跟踪目录 `.claude/`，
不得把它误认为本次改动。

## 13. 下一步继续入口

阶段 0、V9、`RiskPosterior`、SafeRegret shadow 和纯领域 pairwise 核心已经实现，
不要从本设计重新生成一套实现。继续工作时：

1. 先读 `docs/safe-regret-v0.5-implementation.md` 的当前检查点；
2. 在真实测试 PostgreSQL 上执行 V9/Flyway/API 集成验收；
3. 收集同请求新旧风险分歧、排序变化、无可选率和请求尾延迟；
4. 用户确认风险语义和观测结果后，再接入阶段 2 的安全并集门禁；
5. 最后依次灰度 recommendation-v0.5、动态 reroll 和成对画像持久化。

发布前必须实时核对生产应用版本和 Flyway 状态，不能依据旧审计记录推断线上仍与本地一致。
