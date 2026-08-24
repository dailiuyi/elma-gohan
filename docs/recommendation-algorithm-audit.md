# 推荐算法技术审计报告

> 历史审计说明：本报告基于 `614b0dd` 附近的 V0.2 实现形成，用于保留问题证据与决策背景，不代表当前 V0.4.1 的公式。采纳后的实际行为以 `V0.4-personalized-decision-loop.md` 和当前代码为准；部分建议已明确不采纳。

## 1. 审计说明

本报告审计 `backend` 中的 `risk-v0.2` 与 `recommendation-v0.2` 实现，重点检查风险评分、Low Regret 排序、个性化校正、多样化、reroll 和推荐理由。

审计依据包括：

- [`LowRegretScorer`](../backend/src/main/java/com/elma/gohan/domain/recommendation/LowRegretScorer.java)
- [`DefaultRecommendationEngine`](../backend/src/main/java/com/elma/gohan/domain/recommendation/DefaultRecommendationEngine.java)
- [`RuleBasedRiskEngine`](../backend/src/main/java/com/elma/gohan/domain/risk/RuleBasedRiskEngine.java)
- [`RuleBasedRecentTrendDetector`](../backend/src/main/java/com/elma/gohan/domain/risk/RuleBasedRecentTrendDetector.java)
- [`HardFilter`](../backend/src/main/java/com/elma/gohan/domain/recommendation/HardFilter.java)
- [`RecommendationService`](../backend/src/main/java/com/elma/gohan/application/RecommendationService.java)
- [`application.yml`](../backend/src/main/resources/application.yml)
- [`openapi.yaml`](../contracts/openapi.yaml)

结论分为四类：

- **确认缺陷**：实现存在可复现的错误、歧义或不可解释性。
- **确认机制风险**：代码行为属实，但严重程度需要真实分布或线上指标验证。
- **产品策略**：实现与现有文档或契约一致，但可能与“低后悔”目标冲突。
- **原判断需修正**：问题方向成立，但原示例或影响范围不准确。

## 2. 总体结论

| 项目 | 审计结论 | 优先级 |
| --- | --- | --- |
| confidence 压缩风险差异 | 确认，但 HIGH 风险会在排序前剔除，新店 confidence 也不必然为 0 | 高 |
| rating 与 risk 双重计分 | 确认；completeness 与 dataInsufficient 也存在共线 | 高 |
| 不限预算固定 0.8 | 不改变同一次请求排序；预算语义和推荐理由存在问题 | 中 |
| 距离分依赖 radius | 确认；有效权重随半径变化 | 中 |
| Taste 校正主导排序 | 机制确认；实际主导程度需用候选分差验证 | 高 |
| risk 内部权重与 trend 悬崖 | 确认；阈值需要历史数据校准 | 高 |
| 加权随机不可复现 | 确认缺陷 | 高 |
| 双层多样化 | 确认；属于显式但过强的产品策略 | 高 |
| reroll 耗尽回初始推荐 | 确认；契约明确规定，但缺少低后悔降级路径 | 中 |
| dislike 子串匹配 | 确认缺陷 | 高 |
| 同批候选均价作为价格基线 | 确认机制风险 | 高 |
| confidence 不考虑新鲜度 | 确认缺陷 | 高 |
| 推荐理由阈值失真 | 确认，另有不限预算理由误导 | 中 |

总体上，当前算法不是六个相互独立因子的简单加权模型。评分、数据完整度、证据数量和风险会沿多条路径重复影响最终结果，系统因此天然偏好数据充足、历史口碑稳定的成熟门店。该偏好可以是合理的安全策略，但目前没有被明确建模、量化和验收。

## 3. 系数分配审计

### 3.1 risk 权重与 confidence 收缩

当前风险因子为：

```text
effectiveRisk = confidence * riskScore
              + (1 - confidence) * uncertaintyRisk

riskFactor = 1 - effectiveRisk / 100
riskContribution = 20 * riskFactor
```

其中 `uncertaintyRisk=50`。因此 confidence 越低，风险差异越向 50 收缩；confidence 为 0 时，所有原始风险分都得到相同的 10 分风险贡献。

原判断需要两点修正：

1. `riskScore > 60` 会被判为 `HIGH` 并在 Low Regret 排序前剔除。因此 `riskScore=95` 的候选不会与 30 分候选在推荐池内并列。
2. 新店或无外部证据不必然得到 confidence=0。POI 的评分、评论数、营业时间和价格四项齐全时，外部证据为空仍会得到 confidence=0.25；只有 POI 字段也完全缺失时才会降到 0。

不过，0～60 区间内的风险区分度确实被压缩。例如 confidence=0.25 时，风险 30 与风险 60 在总分中的差距仅为 1.5 分，而不做收缩时差距为 6 分。

**结论：确认机制风险。** 高风险硬过滤降低了灾难性影响，但中风险候选之间的安全区分度仍不足。

### 3.2 rating、risk 与数据完整度共线

rating 同时进入两个路径：

- `ratingFactor = rating / 5`，权重 25。
- `ratingRisk`，权重为风险内部 0.25，再通过外层 risk 权重 20 和 confidence 影响总分。

以 3.9 分为例，相比 5.0 分：

- rating 项损失 `25 * (1 - 3.9 / 5) = 5.5` 分。
- 当前阈值下 3.9 小于 `fair-min=4.0`，所以 `ratingRisk=100`，不是 60。
- confidence=1 时，风险路径再损失 5 分；confidence=0.25 时再损失 1.25 分。

同样，价格、营业信息和评论数量缺失既降低 `DataCompleteness`，又增加 `dataInsufficientRisk`，并可能降低 confidence。数据质量因此至少通过三条路径影响排序。

**结论：确认机制风险。** 表面权重不能代表真实敏感度，算法会系统性偏好数据丰富的成熟门店。应在算法说明中明确这是安全偏好，并通过消融实验决定是否保留重复惩罚。

### 3.3 budget 的真实影响

当前实现为：

```text
maxBudget = null       -> factor = 0.8
price is null          -> factor = 0.5
price > maxBudget      -> 已被 HardFilter 剔除
price <= maxBudget     -> factor = 1 - 0.5 * price / maxBudget
```

不限预算时所有候选固定得到 12 分，因此不会改变同一次请求内的相对排序。原判断中“不填预算更偏好便宜店”不成立。

填写预算后，预算内候选的 factor 接近 0.5～1.0，候选间最多产生约 7.5 分差距。真正需要确认的是产品语义：`maxBudget` 是硬上限，还是同时表示“越便宜越好”。当前代码采用后者，可能过度奖励远低于预算的门店。

此外，不限预算时 factor 恰好为 0.8，会满足推荐理由阈值并生成“预算合适”，即使用户没有提供预算。

**结论：原排序判断需修正，但存在预算语义和解释层缺陷。**

### 3.4 distance 对 radius 的敏感性

距离因子为：

```text
distanceFactor = 1 - distanceMeters / radius
```

同一家餐厅仅因用户调整搜索半径，就会得到不同的距离分。半径越大，搜索范围内常见近距离候选的得分越集中；半径越小，靠近边界的候选被快速压低。距离项相对其他因子的有效权重因此不是稳定的 20 分。

`walkingSpeedMetersPerMinute=80` 目前只在响应组装时计算预计步行时间，没有用于排序。采用基于绝对步行时间的分段或饱和函数，可以使不同半径下的距离偏好更稳定。

**结论：确认机制风险。** 是否改为步行时间口径属于产品取舍，需要用不同城市和不同 POI 密度的数据验证。

### 3.5 Taste 校正的幅度与截断

Taste 校正为：

```text
normalized = 0.50 * categoryWeight / maxAbsoluteWeight
           + 0.25 * priceWeight / maxAbsoluteWeight
           + 0.25 * distanceWeight / maxAbsoluteWeight

tasteAdjustment = 15 * clamp(normalized, -1, 1)
finalScore = clamp(baseScore + tasteAdjustment, 0, 100)
```

单独把品类权重累积到上限即可产生 ±7.5 分，三类信号同向时最多达到 ±15 分。候选基础分差较小时，Taste 足以改变 Top-10 和候选池构成。

最终 clamp 发生在 Taste 叠加之后。基础分已接近 100 的候选会因正向 Taste 一起堆积到 100，丢失候选间区分度。现有测试只验证 Taste 能改变排序方向，没有覆盖饱和、最大差值和候选池稳定性。

**结论：确认机制风险。** “Taste 会主导排序”仍需用真实候选基础分分布验证，但当前 ±15 的上限与“轻量校正”定位不一致。

### 3.6 risk 内部权重与 trend 悬崖

风险内部权重为：

| 因子 | 权重 |
| --- | ---: |
| rating | 0.25 |
| template | 0.20 |
| burst | 0.15 |
| trend | 0.15 |
| dataInsufficient | 0.25 |

rating 与 dataInsufficient 合计 0.50，说明风险模型一半权重直接指向历史口碑和数据质量。template 与 burst 合计 0.35，用于识别评论异常；trend 为 0.15。

趋势下降使用离散值 100，稳定为 10，上升和未知为 0。因此一旦跨过 `rating-delta=0.4` 或负面评论比例差 0.2，趋势贡献会从约 1.5 分跃升到 15 分。单独的趋势下降不会超过 HIGH 阈值，但与低评分、数据不足或刷评信号叠加时容易改变风险等级。

**结论：确认机制风险。** 0.4 的阈值是否过陡不能只靠静态代码判断，需要通过季节性、样本量和平台评分口径变化的数据回测。

## 4. 整体算法审计

### 4.1 加权随机不可复现

[`WeightedRandomSelector`](../backend/src/main/java/com/elma/gohan/domain/recommendation/WeightedRandomSelector.java) 本身支持固定 seed，但调用方使用 `System.nanoTime()`。`recommendation_log` 和候选快照均不保存 seed。

数据库可以回答“本次最终选中了哪些候选”，但无法重放“相同输入为何抽中这些候选而不是另一些”，也无法完整恢复 Top-10 之前被淘汰的候选。这限制了确定性测试、问题回放和 A/B 归因。

**结论：确认缺陷。** 应把 seed 作为推荐会话的一部分生成、传入、落库，并在算法版本不变时支持确定性重放。

### 4.2 双层多样化覆盖全局分数

排序流程包含两次品类轮询：

1. 全量分数降序后，第一次轮询重排并截取 Top-10。
2. Top-10 再按品类分组，每组内部加权随机，每轮从各组各取一个，直到组成最多 6 家候选池。

`LinkedHashMap` 的组顺序由全量排序中各品类首次出现的位置决定，因此头部品类仍有先手优势；但第二轮又强制各组近似等额进入候选池。结果是多样性两次覆盖全局 Low RegretScore，低分小组候选可能挤掉更高分候选。

**结论：确认产品策略风险。** 代码与文档的“多样化”目标一致，但强度高于普通的去重或配额约束，应只保留一层明确的多样化策略。

### 4.3 reroll 耗尽缺少降级路径

服务保存最多 6 家候选。首次展示一家，之后最多提供五家未展示候选；全部耗尽后，再次调用 reroll 会返回首次推荐，不重新查询，也不放宽条件。

该行为与现有 OpenAPI 和说明文档一致，因此不是实现偏差。不过连续 reroll 可以视为弱拒绝信号，回到首推与低后悔目标相悖。接口只返回 `alternativesRemaining=0`，无法区分“刚展示最后一个新候选”和“已经耗尽并回到首推”。

**结论：产品设计缺口。** 需要独立的 exhausted 状态，并在耗尽时提示调整筛选、重新查询或明确结束，而不是静默回退。

### 4.4 dislike 子串匹配误杀

`HardFilter` 直接执行：

```text
name.contains(dislike) || categoryLabel.contains(dislike)
```

实现没有去除空白、大小写归一、简繁或中英文归一、词边界、同义词、食材结构，也不匹配标准化细品类 code。因此既可能误杀名称中偶然包含关键词的门店，也可能漏掉真正相关的品类或食材。

**结论：确认缺陷。** 不应直接把自由文本子串命中作为高召回硬过滤；至少应区分标准品类排除和自由文本偏好，并为模糊命中采用软降权或结构化映射。

### 4.5 价格异常基线混入品类差异

`RecommendationService` 对硬过滤后的全部候选统一计算平均价格，再由 RiskEngine 判断：

```text
restaurantPrice > poolAveragePrice * 1.5
```

在 `ANY` 或内部包含多个细品类的搜索中，统一均价混合了正餐、小吃、甜品、咖啡等天然价格差异。高价品类可能因此得到额外 20 点 `dataInsufficientRisk`，虽然价格本身并不代表数据不足或欺诈风险。

**结论：确认机制风险。** 基线至少应按可比较品类分组，并优先使用中位数或稳健分位数；样本不足时不触发异常规则。

### 4.6 confidence 与证据新鲜度脱节

confidence 只使用：

- POI 四字段完整率。
- 评论数量相对 30 条目标的比例。
- 评论文本、评分和时间字段的覆盖率。

评论实际年龄和 Evidence 的 `fetchedAt` 都不参与计算。30 条多年以前的结构完整评论可以得到与近期评论相同的 evidence confidence。

趋势检测只使用最近 30 天和此前 90 天的数据。证据全部超过 120 天时，trend 返回 `UNKNOWN`，对应风险为 0；这会形成“高 confidence、未知趋势、零趋势风险”的危险组合。

**结论：确认缺陷。** confidence 应同时表达完整性、样本量与新鲜度，过期证据需要衰减或显式标记为 stale。

### 4.7 推荐理由与评分状态不一致

“踩坑风险低”只在原始 `riskLevel=LOW`，即 riskScore 不超过 20 时生成。一个字段齐全、评分优秀但没有外部证据的餐厅，其 `dataInsufficientRisk=85`，加权后 riskScore 约为 21，刚好无法获得该理由。因此 LOW 在无证据场景中会非常稀少。

另一方面，不限预算时固定 `budgetFactor=0.8`，刚好满足理由条件，导致系统在用户没有预算要求时仍生成“预算合适”。理由还使用原始 riskLevel，而排序使用 confidence 校正后的 effectiveRisk，两者可能表达不同的安全程度。

“综合匹配度较高”只在其他理由全部为空时出现。它是否高频不能仅凭代码确定，需要统计真实响应理由分布。

**结论：确认解释层缺陷。** 推荐理由应基于用户实际约束和最终有效因子生成，并对理由覆盖率、区分度和真实性建立测试。

## 5. 额外发现

### 5.1 Taste decay 不是时间衰减

`TasteProfile` 在每次新反馈时把全部旧权重乘以 0.95，`updatedAt` 没有参与衰减计算。长时间不反馈不会自动淡化历史偏好；频繁反馈反而会更快衰减旧记录。

因此现有说明中的“太久不反馈会逐渐恢复”与实现不符。应将其描述为“按反馈事件衰减”，或者基于经过时间实现真正的时间衰减。

### 5.2 reroll 状态表达不完整

`alternativesRemaining=0` 同时覆盖两个状态：

- 当前刚展示候选池中的最后一家新餐厅。
- 候选已经耗尽，本次返回了首次推荐。

前端通常会在值为 0 时隐藏按钮，因此正常 UI 流程不容易触发第二种状态，但 API 重试、并发请求或其他客户端仍可能触发。契约应提供明确状态，或把耗尽定义为幂等返回最后一家并附带 exhausted 标记。

## 6. 建议优先级

### P1：先恢复可解释性和安全语义

1. 生成并持久化随机 seed，使候选池可确定性重放。
2. 把证据年龄和 `fetchedAt` 纳入 confidence，修复 stale evidence 的高置信问题。
3. 将价格异常基线改为同品类稳健统计，并设置最小样本量。
4. 重构 dislike：结构化品类硬过滤，自由文本采用规范化匹配和软降权。
5. 通过消融实验量化 rating、completeness、dataInsufficient 的重复惩罚。

### P2：稳定排序权重

1. 只保留一层多样化，并明确品类配额与分数损失上限。
2. 降低 Taste 最大调整幅度或改为基础分的有界比例校正。
3. 将距离改为绝对步行时间的分段或饱和函数。
4. 对 trend 使用连续风险函数，并按样本量收缩趋势影响。
5. 明确预算是硬上限还是价格偏好，避免同时承担两种语义。

### P3：完善交互和说明

1. 为 reroll 增加 exhausted 状态和调整条件的降级路径。
2. 基于最终有效因子生成推荐理由，修复“不限预算却预算合适”。
3. 在算法文档中明确成熟门店偏好、多样化强度和 Taste 最大影响。

## 7. 测试与验证状态

本次审计执行了以下验证：

- `LowRegretScorerTest`
- `DefaultRecommendationEngineTest`
- `HardFilterTest`
- `WeightedRandomSelectorTest`
- `TasteProfileTest`
- `RuleBasedRiskEngineTest`
- `RuleBasedRecentTrendDetectorTest`
- `SlidingWindowBurstDetectorTest`
- `JaccardTemplateCommentDetectorTest`

上述算法单元测试全部通过。

全量 `mvn test` 共发现 47 项测试，其中 13 项 API 集成测试因本机 PostgreSQL `localhost:5432` 未启动而报错；其余 34 项通过，没有断言失败。该结果只能说明现有测试预期得到满足，不能排除本报告指出的敏感度、共线性、回放性和产品语义问题。

OpenAPI 校验脚本未成功运行，因为当前 Python 环境缺少 `yaml` 模块（PyYAML）。本次审计未修改 API 契约。

## 8. 审计边界

本报告基于静态代码、配置、现有文档和单元测试进行核验，没有使用真实线上候选分布、用户反馈率、reroll 率、踩坑率或 A/B 数据。以下判断仍需数据实验确认：

- risk 外层权重 20 是否过低。
- Taste 是否在真实候选池中经常主导排序。
- trend 的 0.4 阈值是否造成大量季节性误伤。
- LOW 理由和“综合匹配度较高”的实际出现频率。
- 多样化带来的质量损失与用户感知收益。

在这些数据可用之前，不建议只通过调整单个权重发布新算法版本；应先建立离线回放集、分数分布报告和可复现随机机制。
