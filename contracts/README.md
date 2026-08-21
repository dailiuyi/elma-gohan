# ELMA API 契约说明

V0.4 的机器可读接口事实源是 [`openapi.yaml`](./openapi.yaml)。本文只解释已确定的跨端规则，不定义第二套 DTO。

## 接口范围

| 用途 | 方法与路径 | 请求 DTO | 成功响应 DTO |
| --- | --- | --- | --- |
| 推荐一家 | `POST /api/v1/recommendations` | `CreateRecommendationRequest` | `201 RecommendationResponse` |
| 换一家 | `POST /api/v1/recommendations/{id}/reroll` | 无请求体 | `200 RecommendationResponse` |
| 用户反馈 | `POST /api/v1/recommendations/{id}/feedback` | `SubmitFeedbackRequest` | `201 FeedbackResponse` |
| 用户行为 | `POST /api/v1/recommendations/{id}/behaviors` | `SubmitBehaviorRequest` | `201/200 BehaviorResponse` |
| 按需深挖 | `POST /api/v1/recommendations/{id}/deep-evidence` | 无请求体 | `200 DeepEvidenceResponse` |

五个接口都要求 `X-Anonymous-User-Id` 请求头，值为客户端首次启动生成并持久化的 UUID。`id` 是推荐会话 ID，不是餐厅 ID。行为和深挖只允许操作属于该用户且已经展示的餐厅。

## 已确定规则

1. 前端定位和服务端 POI/导航坐标统一使用 GCJ-02。
2. 创建推荐只返回当前一家，不返回候选列表。
3. 服务端保存最多 6 个候选；首次推荐之外允许最多 5 次 reroll。候选耗尽后的调用返回初始推荐，不产生第七家。
4. `alternativesRemaining` 是前端是否显示“换一家”的唯一判断字段。
5. 反馈体包含 `LIKE`、`NORMAL` 或 `DISLIKE`，并可附最多 3 个不重复口味标签；同一会话和餐厅只接受一次反馈。
6. 风险分数、风险等级、置信度、风险理由、推荐理由和算法版本全部由服务端产生，前端只展示最多两条风险理由。
7. 高德/百度服务端 Key、第三方 POI 原始结构、内部匹配特征、RiskEngine 和排序过程均不进入接口响应。

## 品类筛选

`category` 接受 `MEAL`、`CHINESE`、`HOT_POT`、`BARBECUE`、`NOODLES`、`FAST_FOOD`、`WESTERN`、`JAPANESE_KOREAN`、`DESSERT_DRINK`、`ANY`。缺省仍为 `MEAL`，用户无需先配置筛选条件；前端只提供可选纠偏。餐厅响应继续返回细品类 code 与 label，映射和多样化重排由服务端负责。

## DTO 摘要

### CreateRecommendationRequest

- 必填：`latitude`、`longitude`。
- 服务端兼容默认：`radius=1000`、`minDistance=null`、`maxBudget=null`、`minBudget=null`、`category=MEAL`、`dislikes=[]`。
- 当前首页默认请求：`minDistance=null`、`radius=500`、`minBudget=20`、`maxBudget=40`。
- “不想吃”输入支持空格、中英文逗号和换行分隔，最多形成 10 个去重关键词。
- `minDistance` 是不包含的距离下界，`radius` 是包含的距离上界；`radius` 只允许 500、1000、2000、3000 米。
- `minBudget` 是不包含的人均预算下界，`maxBudget` 是包含的预算上界；任一字段为 `null` 表示该方向不限。
- 省略新增下界字段时保持旧客户端的累计上限行为；下界必须严格小于对应上界。

### RecommendationResponse

- `recommendationId`：后续 reroll 和 feedback 使用的推荐会话 ID。
- `restaurant`：导航和展示所需的标准餐厅摘要。
- `risk`：可解释、带版本的风险结果；`confidence` 为 0～1，内部 factors 不对客户端公开。
- `evidenceSummary`：V0.3 可选兼容字段；后端正常返回高德/百度评分摘要、门店匹配状态和一条一致性说明，不含平台 POI ID 与匹配特征。
- `reasons`：服务端生成的推荐理由。
- `personalization`：Taste 匹配分、画像可信度、选择模式、个性化理由及 `taste-v0.1` 版本。
- `alternativesRemaining`：剩余未展示替代项数量，范围 0～5。

### SubmitFeedbackRequest / FeedbackResponse

- 请求包含 `result`，可选 `flavorTags` 支持 `SPICY/SWEET/OILY/SALTY/LIGHT`，最多 3 个且不可重复。
- 响应返回反馈 ID、推荐会话 ID、实际餐厅 ID、反馈值和记录时间。

### SubmitBehaviorRequest / BehaviorResponse

- 客户端仅可提交 `ACCEPT/NAVIGATE/SKIP`，并携带持久化 `eventId` 与已展示的 `restaurantId`。
- 首次事件返回 201；相同 `eventId` 重试返回已有结果和 200，不会重复学习画像。

### DeepEvidenceResponse

- `baseRisk` 来自推荐会话冻结快照；`deepRisk` 使用 `deep-risk-v0.1`，最多在基础风险上调整 ±10 分。
- `sourceCoverage` 展示高德、百度及三个公开搜索来源的真实状态；`links` 每站最多 3 条，不返回摘要全文。
- Web 公开线索只用于辅助判断，不改变候选排序、reroll、反馈或 TasteProfile。

### ErrorResponse

所有业务错误统一返回 `code`、`message`、可选 `fieldErrors` 和 `traceId`。前端按稳定的 `code` 分支处理，`message` 可直接展示，`traceId` 用于排查。
