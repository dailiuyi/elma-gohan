# DeepSeek 公开线索搜索

更新：2026-10-22。依据：本次任务提供的已核验 DeepSeek API 契约、`BraveWebEvidenceProvider`、`WebEvidenceMatcher` 和 `DeepEvidenceService`。

`DeepSeekWebEvidenceProvider` 沿用 `DeepEvidenceProvider` 接口、三站点 `site:` 查询、完整店名/品牌加位置的两轮查询，以及 HTTPS 域名白名单和 URL 去重。Spring 默认继续注入 Brave；启用 DeepSeek 后以 `@Primary` 选择新 Provider，并将现有 Brave 实例作为失败回退。因此 Brave 已配置的百度 AI 搜索模式也保持有效。

配置位于 `elma.deep-evidence`：

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| `DEEPSEEK_ENABLED` | `false` | 选择 DeepSeek Provider |
| `DEEPSEEK_API_KEY` | 无 | 仅从进程环境读取，不绑定配置属性、不写日志 |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | 搜索服务地址 |
| `DEEPSEEK_MIN_RERANK_SCORE` | `1.0` | 结果最低 rerank_score，含边界 |
| `DEEPSEEK_MAX_AGE_DAYS` | `365` | 最长结果年龄，空日期允许通过 |

请求为 `POST /v1/tools/search`，Bearer 鉴权；JSON 仅含 `query`、`count`、`need_content:false`。查询按 Unicode 码点截断至 128 字，count 限制为 1–50。结果摘要依次选 snippet、summary、content，再经现有 `WebEvidenceMatcher` 校验，默认门店阈值为 0.72；日期按 RFC3339 解析。低分、过期、非同店或非白名单结果丢弃，正常空结果最多查询两轮后返回 `NO_DATA`。

未启用、缺少密钥、HTTP/网络错误、超时或无效响应（含无效日期）均调用现有 Provider。总期限沿用 4 秒配置，并服从服务层传入的更短期限。DeepSeek 最多使用一半剩余预算，给 Brave 留出回退时间；回退继承原始期限，不再开启一个四秒窗口。

缓存继续由 `DeepEvidenceService` 统一写入 PostgreSQL，不新增 Provider 私有缓存：有效线索默认 12 小时，空结果 30 分钟，不可用 5 分钟。查询指纹包含 DeepSeek 开关、最低分和最长年龄，切换搜索策略或过滤参数时旧结果不会复用。

`DeepSeekWebEvidenceProviderTest` 使用与 Brave 测试相同的本机 `HttpServer` 模拟 HTTP，覆盖解析、三站点、过滤、去重、Unicode 截断、参数限制、超时和失败回退，以及 Spring Provider 选择。测试不调用真实搜索 API。

2026-10-22 超时回退修复：Spring JDK HTTP 的超时取消可能直接抛出 `CancellationException`，原先按异常类型列举的捕获范围会遗漏它。DeepSeek 搜索边界现统一捕获 `RuntimeException`，记录异常类型并在原始期限内调用 Brave。新增确定性测试分别注入取消异常、包装超时的 `CompletionException` 和其他运行时异常，避免仅依赖 HTTP 超时竞态覆盖回退行为。依据：`DeepSeekWebEvidenceProvider.fetch` 与 `DeepSeekWebEvidenceProviderTest.uncheckedSearchFailureFallsBackToBrave`。

本次在无 PostgreSQL 的环境中验证：`cd backend && mvn -q test -Dtest=DeepSeekWebEvidenceProviderTest` 连续执行 5 次，每次 31 个用例全部通过；随后执行 `mvn -q test '-Dtest=com.elma.gohan.provider.deep.*Test'`，三个测试类共 43 个用例全部通过，无失败、错误或跳过。改动仅保存在本地，未提交。

2026-10-22 验证：新增 Provider 测试 28 个全部通过。全量在 `backend/` 执行 `DB_HOST=127.0.0.1 DB_PORT=55432 DB_TEST_NAME=elma_test DB_USERNAME=postgres DB_PASSWORD= mvn -q test`，退出码 0，共 289 个用例，0 失败、0 错误、1 跳过（既有 `PoiMatchingBenchmarkTest.evaluateIndependentHoldout` 需要显式设置 `poi.benchmark`）。环境原先没有 PostgreSQL；验证使用 `/tmp` 中的临时 PostgreSQL 17 实例，仅监听本机地址，未改系统服务或项目依赖，验证后关闭。
