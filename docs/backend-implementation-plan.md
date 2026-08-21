# ELMA 今天吃什么 V0.1 后端实施计划

> 本文件是后端实现的唯一执行计划,自包含、不依赖会话上下文。任何一次开发会话开始前先读本文件与 [CLAUDE.md](../CLAUDE.md)、[contracts/openapi.yaml](../contracts/openapi.yaml),即可继续工作而无需回忆之前的过程。

## 1. 目标

实现 V0.1 后端 Demo 闭环:**定位 → POI 获取 → 硬过滤 → 风险过滤 → Low Regret 排序 → 主动推荐一家 → 换一家(最多 A/B/C) → 反馈**。

非目标(V0.1 明确不做):登录、画像训练、评论爬取、多平台证据、支付/团购/外卖、排行榜、社交、Redis、消息队列、微服务、Docker 集群、Python/AI Runtime、向量搜索。

## 2. 已确认的技术决策(2026-08-19,由负责人拍板)

| 决策项 | 结论 |
| --- | --- |
| 构建工具 | Maven(单模块) |
| 工程位置 | 仓库 `backend/` 子目录 |
| 数据访问 | Spring Data JPA + Flyway |
| 数据库 | 本机已安装的 PostgreSQL(开发库 `elma`,测试库 `elma_test`) |
| JDK / 框架 | Java 17 + Spring Boot 3.5.x(`spring-boot-starter-web`、`-data-jpa`、`-validation`、`flyway-core` + `flyway-database-postgresql`、`postgresql` driver、`-test`) |

## 3. 不可破坏的规则(摘自 CLAUDE.md / 契约,违反即返工)

1. 接口事实源是 `contracts/openapi.yaml`,**不得自行修改接口字段**;DTO 严格按契约实现。发现设计问题先说明,不先改。
2. 坐标统一 GCJ-02(高德原生即 GCJ-02,透传即可,不做偏移转换)。
3. 首次推荐固定最多 A/B/C 三个候选并保存展示游标;reroll 只在候选池内切换,耗尽后返回初始 A(`alternativesRemaining=0`),不产生第四家。
4. `alternativesRemaining`(0~2)是前端显示"换一家"的唯一依据。
5. 风险分数/等级/理由、推荐理由、算法版本全部服务端产生,前端只展示。
6. 高德 Key 只从后端环境变量 `AMAP_KEY` 读取;Key、高德原始 POI 结构、RiskEngine 内部规则、排序过程不得进入任何接口响应或日志。
7. `radius` 只允许 500/1000/2000/3000;`maxBudget` 单位为元,`null` 不限。
8. 业务错误统一 `ErrorResponse`(`code`/`message`/可选 `fieldErrors`/`traceId`),错误码只用契约枚举的四个:`VALIDATION_FAILED`、`NO_RECOMMENDATION_AVAILABLE`、`RECOMMENDATION_NOT_FOUND`、`POI_PROVIDER_UNAVAILABLE`。
9. 品类枚举未定:请求 code 按不透明大写字符串处理,响应回 `code` + `label`,不猜品类表。
10. 三个接口都要求 `X-Anonymous-User-Id`(UUID)请求头;**已发现的契约矛盾**(2026-08-19 实现时确认):契约把 reroll/feedback 路径写成字面量 `/recommendations/glm-5.3_common/...`,但同一操作又声明 `in: path`、`format: uuid` 的推荐会话 `id` 参数,两者矛盾(字面量路径下前端无法寻址会话)。后端按声明的 path 参数实现为 `/recommendations/glm-5.3_common/reroll` 与 `/recommendations/glm-5.3_common/feedback`;契约 YAML 应修正为 `/recommendations/glm-5.3_common/reroll` 形式,修正后后端无需改动。

## 4. 工程结构(技术方案第 33 节)

```
backend/
├─ pom.xml
├─ src/main/java/com/elma/gohan/
│  ├─ GohanApplication.java
│  ├─ controller/            # RecommendationController + api 包(DTO,严格对齐 openapi.yaml)
│  ├─ application/           # RecommendationService(编排:会话、候选池、游标、反馈)
│  ├─ domain/
│  │  ├─ restaurant/         # Restaurant 标准模型、RestaurantCandidate
│  │  ├─ risk/               # RiskEngine 接口、RuleBasedRiskEngine、RiskResult
│  │  ├─ recommendation/     # RecommendationEngine、硬过滤、LowRegretScorer、加权随机
│  │  └─ user/               # 匿名用户值对象(仅 userId,无登录)
│  ├─ provider/
│  │  ├─ poi/                # PoiProvider 接口 + amap/AmapPoiProvider、AmapClient(HTTP)、AmapResponseMapper
│  │  └─ evidence/           # EvidenceProvider 接口 + EmptyEvidenceProvider
│  ├─ infrastructure/
│  │  ├─ persistence/        # JPA 实体 + Spring Data repository
│  │  └─ web/                # 全局异常处理、ErrorResponse 工厂、traceId 过滤器
│  └─ config/                # AmapProperties、RiskProperties、RecommendationProperties
├─ src/main/resources/
│  ├─ application.yml        # 只含占位,敏感值全部走环境变量
│  └─ db/migration/          # Flyway V1__init.sql
└─ src/test/                 # 单元测试(不依赖网络)+ 集成测试(连本机 elma_test)
```

原则:模块化单体、不做形式主义 DDD;核心是 Risk / Recommendation / Provider 三层边界清楚——domain 不 import provider/infrastructure 的类。

## 5. 数据库设计(Flyway `V1__init.sql`,全部 UUID 主键)

六张表(契约与技术方案第 24/25 节):

- `restaurant` — 内部标准餐厅:`id`、`source`、`source_poi_id`(唯一约束 `source+source_poi_id`)、`name`、`latitude`、`longitude`、`category_code`、`category_label`、`rating`、`review_count`、`average_price`、`business_status`、`opening_hours`、`address`、`data_completeness`、`created_at`、`updated_at`。推荐时按 `(source, source_poi_id)` upsert。
- `risk_result` — `id`、`restaurant_id`、`risk_score`、`risk_level`、`reasons_json`、`algorithm_version`、`calculated_at`。每次评估插入,不覆盖(留历史版本对比)。
- `recommendation_log` — 每次创建推荐会话一条:`id`(即对外的 recommendationId)、`anonymous_user_id`、`request_condition_json`(快照)、`candidate_count`、`recommended_restaurant_id`(初始 A)、`risk_score`、`low_regret_score`、`risk_algorithm_version`、`recommendation_algorithm_version`、`created_at`。
- `recommendation_candidate` — 会话候选池:`id`、`recommendation_log_id`、`restaurant_id`、`slot`(1=A/2=B/3=C)、`risk_score`、`low_regret_score`、`shown`。游标语义:`shown` 标记 + 按 slot 排序;reroll 取下一个未展示的,耗尽返回 slot=1 且所有候选 `shown=true` 后 `alternativesRemaining=0`。
- `user_feedback` — `id`、`recommendation_log_id`、`restaurant_id`(关联会话当前展示项)、`anonymous_user_id`、`result`(LIKE/NORMAL/DISLIKE)、`created_at`。每次提交一条(契约注明不覆盖、不做 upsert)。
- `user_preference` — V0.1 建表即可:每次创建推荐会话写入 `anonymous_user_id` + 条件快照(最简用途),不做读取逻辑。

索引:`recommendation_log(anonymous_user_id, created_at)`、`recommendation_candidate(recommendation_log_id)`、`user_feedback(recommendation_log_id)`、`restaurant(source, source_poi_id)` 唯一。

## 6. 核心设计

### 6.1 PoiProvider / AmapPoiProvider

- 接口签名按技术方案第 8 节:`List<Restaurant> nearby(Location location, SearchCondition condition)`。
- `AmapClient` 用 Spring `RestClient` 调高德「周边搜索 v3」`GET /v3/place/around`:`key`(环境变量)、`location=lng,lat`、`radius`、`types=050000`(餐饮服务大类;初版计划误写 020000,已勘误)、`extensions=all`(取评分/人均)、`offset=25`、分页最多 2 页(足够 Demo;不足时以实际返回为准,不无限翻页)。超时 3s/5s,非 1 的 `status`、网络异常、超时统一抛内部 `PoiProviderException` → 全局处理为 502 `POI_PROVIDER_UNAVAILABLE`。
- `AmapResponseMapper` 把高德原始 JSON 转内部 `Restaurant`:`source="AMAP"`、`sourcePoiId`、GCJ-02 坐标透传、`biz_ext.rating`/`biz_ext.cost`、`typecode`→内部品类 code(截取大类映射,映射表放配置;查不到给通用 code + type name 作 label)、营业状态默认 `UNKNOWN`(around 接口无可靠营业状态,按方案第 18 节交给 RiskEngine 加分处理,不做 CLOSED 硬过滤误杀)。原始 JSON 不落日志、不进响应。
- `dataCompleteness`:按 rating/review_count/average_price/opening_hours/address 缺失项计算(枚举 `FULL`/`PARTIAL`/`MINIMAL`,V0.1 简单计数即可)。
- `EmptyEvidenceProvider` 返回空证据对象,`EvidenceProvider` 接口保留。

### 6.2 RiskEngine(规则模型,阈值全部配置化)

`RuleBasedRiskEngine` 实现 `RiskEngine`,规则按方案第 17/18 节,**每条阈值/加分都来自 `RiskProperties`(`application.yml` 中 `elma.risk.*`),代码中禁止出现裸数字阈值**:

- 评分档位(≥4.5 / 4.2~4.5 / 4.0~4.2 / <4.0 → +0/+5/+15/+30;rating 缺失按 <4.0 档处理并在 reasons 说明)。
- 评价数过低 +10~15(阈值配置)。
- 营业信息缺失 +10;价格缺失 +5。
- 价格异常(高于同请求候选均值×配置倍数)+10。
- 等级映射:0~20 LOW / 21~40 MEDIUM_LOW / 41~60 MEDIUM / 61+ HIGH;边界值写进配置。
- 输出 `RiskResult{riskScore, riskLevel, reasons[](≥1 条中文), algorithmVersion="risk-v0.1"}`(版本号也来自配置)。
- 高风险(HIGH,即 score≥61)在推荐引擎层被过滤,不主动推荐。

### 6.3 RecommendationEngine

流程(方案第 21 节):

1. **硬过滤**:距离 ≤ radius(高德已按半径查,服务端再校验一次距离值兜底)、预算(averagePrice 非空且 ≤ maxBudget;averagePrice 为 null 且 maxBudget 非空时**保留**——无法判定不算违反,交给 RiskEngine 价格缺失加分)、品类(code 精确匹配,`ANY`/缺省不过滤)、营业状态(CLOSED 剔除,UNKNOWN 保留)。
2. **dislikes 过滤**(V0.1 简单语义):任一 dislike 关键词命中餐厅名称/品类 label 即剔除;不做分词、不做同义词。
3. **风险过滤**:剔除 HIGH。
4. **LowRegretScore**:各因子加权求和(权重全部配置化):基础质量(rating/reviewCount)、距离(越近越高)、预算匹配度、品类匹配(ANY 记满分)、数据完整度、风险(score 越低越高)。公式写在一个 `LowRegretScorer` 类,单独可测。
5. **Top-K 加权随机**:K=5(配置);按 LowRegretScore 归一化为权重,不放回抽取 3 家作为 A/B/C 候选池(候选不足 3 家时池子可小于 3,`alternativesRemaining = 池大小 - 1`);A 为当前推荐。
6. 响应 `reasons[]`(1~5 条):由得分因子生成(距离近/预算合适/评分稳定/数据完整等),同样由服务端中文文案配置或代码生成,规则透明。

### 6.4 会话 / reroll / 反馈(application 层)

- `POST /recommendations`:校验请求(经纬度范围、radius 枚举、maxBudget 1~10000、dislikes ≤10 条各 1~30 字,失败→400 `VALIDATION_FAILED` + fieldErrors)→ 全流程 → 候选不足 1 家→422 `NO_RECOMMENDATION_AVAILABLE`;成功→201,upsert restaurant、插 risk_result、recommendation_log、candidate、user_preference。
- `POST /recommendations/glm-5.3_common/reroll`:按 `id` + 匿名用户头查会话(不存在/不匹配→404 `RECOMMENDATION_NOT_FOUND`)→ 取下一个未展示候选;耗尽→返回 slot=1 的 A,`alternativesRemaining=0`;200。
- `POST /recommendations/glm-5.3_common/feedback`:按会话当前展示餐厅落一条 `user_feedback`→201 `FeedbackResponse`。
- 会话过期策略:V0.1 不做过期(404 描述里的"已过期"留待后续),保持最小实现。
- `traceId`:一个 servlet Filter 生成(优先复用请求头 X-Trace-Id,否则 UUID),放 MDC 供日志,异常时写入 `ErrorResponse.traceId`。
- 校验头 `X-Anonymous-User-Id`:非 UUID 格式→400。

## 7. 实施顺序(每步完成即可独立验证)

> 2026-08-19:Step 1~7 已全部完成,`mvn test` 34 个用例全绿;Step 8 文档已交付。真实高德 Key 的手工冒烟待负责人执行。

- [x] **Step 1 工程骨架**:`backend/` 下 Maven 工程、Spring Boot 启动、连本机 PG(`localhost:5432/elma`,用户名密码走环境变量 `DB_USERNAME`/`DB_PASSWORD` 或本机默认)、Flyway V1 空迁移可跑通、`GET /actuator/health` up(引入 actuator,仅 health)。
- [ ] **Step 2 契约层**:DTO + Controller 三个端点(先返回 501/占位)、`@Valid` 校验、全局异常处理 + ErrorResponse 工厂 + traceId 过滤器。此步完成后可用契约 example 请求逐一核对 400/404 路径。
- [ ] **Step 3 domain + provider**:`Restaurant` 模型、`PoiProvider`/`EvidenceProvider` 接口、`AmapClient` + `AmapPoiProvider` + Mapper、`AmapProperties`(`AMAP_KEY` 环境变量,缺失时启动告警、调用时报 502)。
- [ ] **Step 4 RiskEngine**:`RiskProperties` + `RuleBasedRiskEngine` + 单元测试(每条规则、边界档位、HIGH 过滤阈值)。
- [ ] **Step 5 RecommendationEngine**:硬过滤、dislikes、风险过滤、`LowRegretScorer`、Top-K 加权随机 + 单元测试(权重行为用固定随机种子测)。
- [ ] **Step 6 持久化与会话**:JPA 实体/Repository、创建推荐全链路 201、reroll 游标、feedback 落库。
- [ ] **Step 7 集成测试**:连 `elma_test`(每个测试类事务回滚或 `@Sql` 清库),覆盖 201/400/404/422/502 与契约 example 一致;高德用假 HTTP(MockWebServer 或可配置 base-url 指向本地 stub),绝不依赖真实 Key。
- [ ] **Step 8 交付**:backend/README.md(启动方式、环境变量清单:`DB_USERNAME`、`DB_PASSWORD`、`AMAP_KEY`、可选 `AMAP_BASE_URL`)、手工验收步骤;把构建/测试命令补进根 CLAUDE.md「常用命令」一节;勾选 [docs/V0.1-development-tasks.md](V0.1-development-tasks.md) 中后端相关条目。

## 8. 测试策略

- **单元测试**(不依赖 Spring 上下文/网络):RiskEngine 全部规则与阈值边界、硬过滤各维度、LowRegretScorer、加权随机(固定 seed 断言分布/不放回)、reroll 游标状态机(A→B→C→A)、Mapper 的高德 JSON→Restaurant 转换(含字段缺失)。
- **集成测试**(本机 PG `elma_test` + stub 高德):三个接口的状态码与响应体形状对齐契约;匿名用户头不匹配→404;候选耗尽行为;反馈归属当前展示餐厅。
- 手工冒烟:真实 `AMAP_KEY` 下创建一次推荐,确认真实 POI 闭环。

## 9. 已识别的设计注意点(实现前无须再问,按此执行)

1. `glm-5.3_common` 路径段:实现时确认契约存在矛盾(见第 3 节第 10 条),后端将其作为推荐会话 id 的路径参数位(`/recommendations/glm-5.3_common/reroll`),待契约方修正 YAML。
2. 高德 around 接口无可靠营业状态 → `businessStatus` 多数为 UNKNOWN,CLOSED 硬过滤基本不触发,真正的把关交给 RiskEngine 的"营业信息缺失 +10"。契约响应 `businessStatus` 枚举含 UNKNOWN,合法。
3. `walkingMinutes` 服务端估算:`distanceMeters / 80m/min` 向上取整,最低 1(契约 minItems=1);注释注明不代表实时路线规划。
4. `averagePrice` 为 null 且 maxBudget 非限时不剔除(见 6.3),理由:数据缺失 ≠ 超预算。
5. 契约规定 reroll 无请求体、feedback 只有一个字段;DTO 用 `additionalProperties=false` 语义(Jackson `FAIL_ON_UNKNOWN_PROPERTIES` 保持默认开启即可)。
6. `recommendation_log` 每个会话一条(创建时写),reroll 不另插 log 行,只更新 candidate 的 `shown` 与会话当前展示项(记录在 recommendation_log 或单独列,实现时二选一,保持最小)。
7. 日志中不得打印高德 Key 与原始响应体;`logs/` 目录不得提交(加入 .gitignore)。

## 10. 验收门槛(全部满足才算完成)

1. 三个接口行为与 `contracts/openapi.yaml` 逐字段一致(以契约为准核对响应字段名/可空性/枚举)。
2. 真实高德 Key 下能完成"创建推荐→两次 reroll→耗尽返回 A→反馈"全链路。
3. `alternativesRemaining` 语义正确,永远不出现第四家。
4. 每次推荐落 recommendation_log(含条件快照、候选数、双算法版本、分数)。
5. 仓库与响应中无高德 Key、无第三方原始结构。
6. `mvn test` 全绿;构建命令已写入根 CLAUDE.md。
