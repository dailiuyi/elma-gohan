# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 仓库现状

这是「ELMA 今天吃什么」前后端仓库。当前版本为 V0.3.2：默认推荐保持高德 + 百度与 `risk-v0.3`，并在进入细品类映射前验证高德 POI 的餐饮身份；用户点击“深挖一下这家”后才通过正规 Web Search API 查询公开索引线索，并生成不参与排序的 `deep-risk-v0.1`。接口事实源是 [contracts/openapi.yaml](contracts/openapi.yaml)，当前增量规则见 [docs/V0.3.2-product-polish.md](docs/V0.3.2-product-polish.md)。

两份方案文档（`elma-gohan_V0.1_Demo_技术与产品方案.md`、`elma-gohan产品介绍.md`）是项目起点，不得覆盖或重写。

## 常用命令

验证 OpenAPI 契约（唯一已存在的可执行检查，修改 `contracts/openapi.yaml` 后必须运行）：

```bash
python contracts/validate_openapi.py
```

需要 `pyyaml`。脚本会校验操作集合（只允许四个 POST 接口）、operationId、`X-Anonymous-User-Id` 头、反馈 DTO 形状以及所有 example/default 与 schema 一致，输出 `CONTRACT_OK` 或 `CONTRACT_INVALID`。

后端工程在 `backend/`（Java 17 + Spring Boot 3.5 + Maven 单模块）：

```bash
cd backend && mvn test
```

集成测试连本机 PostgreSQL 测试库 `elma_test`（环境变量 `DB_TEST_NAME`/`DB_USERNAME`/`DB_PASSWORD` 可覆盖）；高德在测试中被本地 stub 替代，百度测试配置默认关闭，不需要真实 Key。启动开发服务需 `DB_PASSWORD`、`AMAP_KEY`，真实双平台 smoke test 还需 `BAIDU_MAP_AK`，详见 [backend/README.md](backend/README.md)。

后端工程建立后的构建/测试命令在工程落地时补充到本节。

## 接口契约（事实源）

接口事实源是 [contracts/openapi.yaml](contracts/openapi.yaml)，说明见 [contracts/README.md](contracts/README.md)。前后端实现不得自行定义第二套 DTO 字段含义；改契约必须先改 YAML 并通过验证脚本。

四个接口（均要求 `X-Anonymous-User-Id` 请求头，值为客户端生成的匿名 UUID）：

- `POST /api/v1/recommendations` — 创建推荐会话，只返回一家（201）
- `POST /api/v1/recommendations/{id}/reroll` — 换一家（200）
- `POST /api/v1/recommendations/{id}/feedback` — 用户反馈，请求体只有 `result`（LIKE/NORMAL/DISLIKE）（201）
- `POST /api/v1/recommendations/{id}/deep-evidence` — 只深挖当前展示餐厅，无请求体（200）

路径中的 `id` 是推荐会话 ID，不是餐厅 ID。

### 不可破坏的规则

1. 坐标统一 GCJ-02（前端定位、服务端 POI、导航一致）。
2. 服务端最多保存 6 个不同候选；首次推荐之外允许最多 5 次 reroll，耗尽后返回初始推荐，不产生第七家。
3. `alternativesRemaining`（0～5）是前端是否显示“换一家”的唯一判断字段。
4. 风险分数/等级/理由、推荐理由、算法版本全部由服务端产生，前端只展示。
5. 高德与百度服务端 Key 只从后端环境变量读取；Key、平台 POI ID、原始结构、内部匹配特征、RiskEngine 和排序过程不得进入接口响应。
6. `radius` 只允许 500/1000/2000/3000 米；`maxBudget` 单位为人民币元，`null` 表示不限。
7. 业务错误统一 `ErrorResponse`（`code`/`message`/可选 `fieldErrors`/`traceId`），前端按稳定 `code` 分支处理。
8. 请求品类只允许 `MEAL`、`FAST_FOOD`、`DESSERT_DRINK`、`ANY`，缺省为 `MEAL`；响应继续给细品类代码和 `label`。

## 架构（目标形态）

模块化单体（Java 17 + Spring Boot + PostgreSQL + 高德 Web Service），包结构建议见技术方案第 33 节（controller / application / domain / provider / infrastructure / config）。

四个核心接口必须保持独立、可替换：

- `PoiProvider`（V0.1 实现 `AmapPoiProvider`）— 附近餐厅查询；第三方数据必须先转成内部 `Restaurant` 标准模型（含 `sourcePoiId`、`dataCompleteness` 等），原始结构不得进入业务核心。
- `EvidenceProvider` — File 评论 Evidence 扩展点继续保留；`PlatformEvidenceProvider` 使用批量检索并统一映射成 `PlatformEvidence`。百度失败、无匹配与字段缺失均须降级，不能中断高德主推荐。
- `EntityResolver` — 使用名称、GCJ-02 坐标、地址、电话执行确定性一对一匹配；模糊匹配不得自动绑定。
- `RiskEngine` — `risk-v0.3` 可配置规则模型（非 ML），输出六项 factors（含跨平台评分冲突）、`riskScore`(0~100)、`riskLevel`、`confidence`、`reasons[]` 和版本；高风险项（61+）不主动推荐。
- `RecommendationEngine` — 硬过滤 → Evidence/Risk → 高风险剔除 → LowRegretScore（含可信度校正与 TasteProfile）→ Top-10 多样化 → 有限加权随机 → 最多 6 家候选池。

推荐流程：定位 → 高德 POI → 硬过滤 → 百度批量检索 → Entity Resolution → 跨平台一致性 → Risk → 高风险过滤 → 排序 → 主动推荐一家 → 反馈更新画像。每次推荐必须落 `recommendation_log`（请求条件快照、候选数、首次推荐餐厅、两种算法版本、分数），并把证据摘要冻结在风险结果和候选池中，reroll 不重新调用百度。数据库用 Flyway 建表，核心表新增 `external_entity_mapping`。

## V0.3.1 明确边界

允许通过正规 Web Search API 消费 B站、小红书和大众点评的公开索引标题、URL、摘要与时间，但禁止直接抓取这些平台页面、评论区、字幕、用户资料或登录态内容。仍不实现抖音/美团/点评非公开接口、登录、支付/团购/外卖、Redis、消息队列、微服务、Python/AI Runtime、LLM、Embedding、向量搜索或协同过滤。深挖结果不得改变推荐排序、候选池、reroll 或 TasteProfile。

## Git 约定

当前集成分支为 `main`；新开发分支继续使用清晰、窄范围的 Conventional Commit。
