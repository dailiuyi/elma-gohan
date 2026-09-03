# ELMA 今天吃什么 V1.0.0 后端

Java 17 + Spring Boot 3.5 + PostgreSQL 的模块化单体。高德与百度提供结构化 Evidence，`risk-v0.3.1` 只表达客观风险；`taste-v0.1` 和 `recommendation-v0.4.1` 负责匿名用户画像、近期历史、有限探索、个性化排序与确定性选择重放。Brave Web Search 仍只在用户主动深挖时提供公开弱线索。接口契约见 [`../contracts/openapi.yaml`](../contracts/openapi.yaml)，个性化规则见 [`../docs/V0.4-personalized-decision-loop.md`](../docs/V0.4-personalized-decision-loop.md)。

## 构建与测试

```bash
cd backend
mvn test              # 单元测试 + 集成测试(需要本机 PostgreSQL,见下)
mvn spring-boot:run   # 启动开发服务(默认 8080)
```

## 环境变量

| 变量 | 说明 | 默认 |
| --- | --- | --- |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | PostgreSQL 地址与开发库名 | `localhost` / `5432` / `elma` |
| `DB_USERNAME` / `DB_PASSWORD` | 数据库账号 | `postgres` / 空 |
| `AMAP_KEY` | 高德 Web Service Key,**只能放环境变量** | 空(未配置时推荐接口返回 502) |
| `AMAP_BASE_URL` | 高德 API 地址(测试 stub 用) | `https://restapi.amap.com` |
| `BAIDU_MAP_AK` | 百度 Place 服务端 AK，**只能放环境变量** | 空（自动降级） |
| `BAIDU_ENABLED` | 是否启用百度第二 Evidence 来源 | `true` |
| `BAIDU_BASE_URL` | 百度 API 地址（测试 stub 用） | `https://api.map.baidu.com` |
| `BAIDU_NAME_RECALL_MAX_RESTAURANTS` | 单次推荐最多逐店补召回的未匹配门店数 | `10` |
| `BAIDU_NAME_RECALL_MAX_REQUESTS` | 单次推荐最多逐店补召回的百度 API 请求数 | `20` |
| `BAIDU_NAME_RECALL_RADIUS_METERS` | 逐店严格周边检索半径（米） | `500` |
| `BAIDU_RECALL_MAX_CALLS` | 通用页、逐店召回和可选 V2 合计调用上限 | `22` |
| `BAIDU_RECALL_TIME_BUDGET_MS` | 整次百度召回时间预算（毫秒） | `8000` |
| `BAIDU_RATE_LIMIT_PER_SECOND` | 单 JVM 内所有百度 Place 请求共享的全局速率上限 | `3` |
| `BAIDU_RATE_LIMIT_MAX_WAIT_MS` | 单次百度 Place 请求等待全局限速许可的最长时间；超时按平台不可用处理 | `1000` |
| `EVIDENCE_PROVIDER` | Evidence 实现：`file` 或 `empty` | `file` |
| `EVIDENCE_FILE` | 标准化评论证据 JSON，可用外部文件覆盖 | `classpath:evidence/restaurant-evidence.json` |
| `BRAVE_ENABLED` | 是否启用按需公开 Web Evidence | `false` |
| `BRAVE_SEARCH_API_KEY` | Brave Search API Key，**只能放环境变量** | 空（自动降级） |
| `BRAVE_CONNECT_TIMEOUT_MS` / `BRAVE_READ_TIMEOUT_MS` | Brave 连接/读取超时 | `1500` / `3000` |
| `BRAVE_RESULT_COUNT` | 每个来源最多召回结果数 | `10` |
| `DEEP_EVIDENCE_CACHE_HOURS` / `DEEP_ANALYSIS_CACHE_HOURS` | Evidence / 分析缓存时长 | `12` / `6` |
| `DB_TEST_NAME` | 测试库名(仅 `src/test/resources/application.yml`) | `elma_test` |

本地准备:创建 `elma` 与 `elma_test` 两个数据库,Flyway 启动时自动建表。`DB_PASSWORD` 等口令一律走环境变量,仓库不含任何真实凭据。

## 架构

```
controller/        推荐闭环与用户数据删除接口，DTO 严格对齐 openapi.yaml
application/       推荐、反馈、行为、画像、近期历史 + 按需 DeepEvidenceService
domain/
  restaurant/      Restaurant 标准模型(第三方数据必须先转此模型)
  risk/            risk-v0.3.1:评分/模板/burst/连续趋势/数据不足/跨平台冲突
  recommendation/  高风险剔除 -> 个性化 LowRegretScore -> 有限探索 -> 6 家冻结候选池
  deep/            公开线索分析与 deep-risk-v0.1
provider/
  poi/             PoiProvider + AmapPoiProvider/AmapClient/AmapResponseMapper
  evidence/        File 评论 Evidence + 百度批量 Evidence + 实体匹配
  deep/            Brave Search Provider + Web Entity Match
infrastructure/    JPA 实体/仓库、Taste/行为/历史、Evidence/深挖缓存、全局异常处理
config/            Amap/Baidu/EntityResolution/Risk/Taste/Recommendation/DeepEvidence 配置属性
```

## File Evidence 格式

文件顶层为 `restaurants` 数组，每家以 POI 来源与外部 ID 对应餐厅，评论会映射成统一 `RestaurantEvidence`：

```json
{
  "restaurants": [{
    "source": "AMAP",
    "sourcePoiId": "B001",
    "fetchedAt": "2026-08-19T00:00:00Z",
    "reviews": [{
      "externalReviewId": "review-1",
      "text": "现场用餐记录",
      "rating": 4.5,
      "createdAt": "2026-08-18T12:00:00Z"
    }]
  }]
}
```

单餐厅最多读取 200 条。文件读取失败、JSON 损坏、无匹配餐厅或 Provider 异常都会转为 `UNAVAILABLE/NO_DATA`，主推荐继续运行。

## 人工验收

1. 设置 `AMAP_KEY`、`BAIDU_MAP_AK` 与 `DB_PASSWORD`；如需深挖，再设置 `BRAVE_ENABLED=true` 和 `BRAVE_SEARCH_API_KEY`，启动 `mvn spring-boot:run`。
2. `curl -X POST localhost:8080/api/v1/recommendations -H "Content-Type: application/json" -H "X-Anonymous-User-Id: <uuid>" -d '{"latitude":28.2282,"longitude":112.9388}'` -> 201,返回一家真实餐厅。
3. 检查响应中的 `evidenceSummary`；百度不可用时应为 `UNAVAILABLE`，推荐仍成功。
4. 用返回的 `recommendationId` 调 `/behaviors` 记录 ACCEPT/NAVIGATE/SKIP，并调 `/feedback` 提交 `{"result":"LIKE","flavorTags":["SPICY"]}`；相同事件 ID 重试不重复落库。
5. 对当前餐厅调用 `/api/v1/recommendations/<id>/deep-evidence`；三个来源先查询最近 31 天，严格门店匹配为 0 时各自最多追加一次不限时间查询，因此首次最多 6 次 Brave 调用；同一餐厅再次调用应命中缓存且不再查询。
6. 调用 `DELETE /api/v1/users/me/data` 后，确认该匿名用户的推荐、行为、反馈、饮食历史和画像均被删除，其他用户及共享餐厅数据保留。
