# DeepSeek 公开线索搜索

更新：2026-10-22。依据：本次任务提供的已核验 DeepSeek API 契约、`BraveWebEvidenceProvider`、`WebEvidenceMatcher` 和 `DeepEvidenceService`，以及 `/workspace/handoff/2026-10-22-elma-gohan-deepseek-search/sampling.md` 的长沙抽样记录。

`DeepSeekWebEvidenceProvider` 沿用 `DeepEvidenceProvider` 接口、三站点 `site:` 查询、完整店名/品牌加位置的两轮查询，以及 HTTPS 域名白名单和 URL 去重。Spring 默认继续注入 Brave；启用 DeepSeek 后以 `@Primary` 选择新 Provider，并将现有 Brave 实例作为失败或空结果回退。因此 Brave 已配置的百度 AI 搜索模式也保持有效。

配置位于 `elma.deep-evidence`：

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| `DEEPSEEK_ENABLED` | `false` | 选择 DeepSeek Provider |
| `DEEPSEEK_API_KEY` | 无 | 仅从进程环境读取，不绑定配置属性、不写日志 |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | 搜索服务地址 |
| `DEEPSEEK_MIN_RERANK_SCORE` | `0.2` | 最低 rerank_score（含边界）；0 关闭，启用时缺分也剔除 |
| `DEEPSEEK_MAX_AGE_DAYS` | `730` | 最长发布日期年龄（天，含边界）；0 不限，空日期通过 |
| `DEEPSEEK_MIN_SNIPPET_LENGTH` | `10` | 摘要最少 Unicode 码点数；0 关闭短摘要规则 |
| `DEEPSEEK_SHORT_SNIPPET_MULTIPLIER` | `0.5` | 短摘要综合分乘数，范围 0–1；0 剔除，1 不降权 |
| `DEEPSEEK_RELEVANCE_WEIGHT` | `0.7` | 相关度权重，非负 |
| `DEEPSEEK_RECENCY_WEIGHT` | `0.3` | 新近度权重，非负；两权重均为 0 时保持原顺序 |
| `DEEPSEEK_RECENCY_HALF_LIFE_DAYS` | `365` | 新近度每隔多少天减半；≤0 关闭新近度排序项 |

请求为 `POST /v1/tools/search`，Bearer 鉴权；JSON 仅含 `query`、`count`、`need_content:false`。查询按 Unicode 码点截断至 128 字，count 限制为 1–50。结果摘要依次选 snippet、summary、content，再经现有 `WebEvidenceMatcher` 校验，默认门店阈值为 0.72；日期按 RFC3339 解析。v2 先建立 v1 未筛选基线（仍要求标题、合法链接、同店校验通过，并按规范 URL 保留首次结果去重），再应用日期、最低分和短摘要规则。无标题、无链接或非白名单链接始终剔除，不受 v2 筛空回退影响。摘要缺失按长度 0 处理，长度规则作用于最终选取并截断至 500 码点的摘要。日期和长度参数应为非负整数。

筛选后按综合分降序稳定排序：`(相关度权重 × 相关度 + 新近度权重 × 新近度) × 摘要乘数`。相关度使用 DeepSeek 的 `rerank_score`，归一化为 `max(0, score) / (1 + max(0, score))`，缺分记 0；同店匹配置信度仍只用于门店校验。新近度为 `0.5 ^ (年龄天数 / 半衰期天数)`，按实际秒数换算天数；空日期记 0，未来日期按年龄 0 计算。半衰期 ≤0 时直接将新近度项记 0，不执行指数计算，排序仅由相关度和摘要乘数决定；相关度权重也为 0 时保持原顺序。长摘要乘数为 1。年龄较大但未超过阈值的结果通过新近度自然降权；分数相同时保留搜索顺序。

**筛选后为空就直接返回该次查询的 v1 基线及原顺序**，不再筛选或排序、不额外请求 DeepSeek，也不因 v2 筛空调用 Brave。因此全部过旧、低分或摘要过短仍有证据可用。只有 v1 基线本身为空时才继续品牌查询，两轮仍为空则在原始期限内回落 Brave；最终状态由 Brave 返回。

未启用、缺少密钥、HTTP/网络错误、超时或无效响应（含无效日期）均调用现有 Provider。总期限沿用 4 秒配置，并服从服务层传入的更短期限。DeepSeek 最多使用一半剩余预算，给 Brave 留出回退时间；回退继承原始期限，不再开启一个四秒窗口。

缓存继续由 `DeepEvidenceService` 统一写入 PostgreSQL，不新增 Provider 私有缓存：有效线索默认 12 小时，空结果 30 分钟，不可用 5 分钟。查询指纹升级为 `deepseek-query-v0.2`，包含 DeepSeek 开关及全部筛选、排序配置，切换参数时旧结果不会复用。

`DeepSeekWebEvidenceProviderTest` 使用与 Brave 测试相同的本机 `HttpServer` 模拟 HTTP，覆盖解析、三站点空结果回退、默认 730 天与 0 不限、低分剔除、摘要降权/剔除、综合排序及权重/半衰期调整、全部筛空回退 v1、去重、Unicode 截断、参数限制、超时和失败回退，以及 Spring Provider 选择。测试不调用真实搜索 API。

2026-10-22 超时回退修复：Spring JDK HTTP 的超时取消可能直接抛出 `CancellationException`，原先按异常类型列举的捕获范围会遗漏它。DeepSeek 搜索边界现统一捕获 `RuntimeException`，记录异常类型并在原始期限内调用 Brave。新增确定性测试分别注入取消异常、包装超时的 `CompletionException` 和其他运行时异常，避免仅依赖 HTTP 超时竞态覆盖回退行为。依据：`DeepSeekWebEvidenceProvider.fetch` 与 `DeepSeekWebEvidenceProviderTest.uncheckedSearchFailureFallsBackToBrave`。

本次在无 PostgreSQL 的环境中验证：`cd backend && mvn -q test -Dtest=DeepSeekWebEvidenceProviderTest` 连续执行 5 次，每次 31 个用例全部通过；随后执行 `mvn -q test '-Dtest=com.elma.gohan.provider.deep.*Test'`，三个测试类共 43 个用例全部通过，无失败、错误或跳过。

2026-10-22 验证：新增 Provider 测试 28 个全部通过。全量在 `backend/` 执行 `DB_HOST=127.0.0.1 DB_PORT=55432 DB_TEST_NAME=elma_test DB_USERNAME=postgres DB_PASSWORD= mvn -q test`，退出码 0，共 289 个用例，0 失败、0 错误、1 跳过（既有 `PoiMatchingBenchmarkTest.evaluateIndependentHoldout` 需要显式设置 `poi.benchmark`）。环境原先没有 PostgreSQL；验证使用 `/tmp` 中的临时 PostgreSQL 17 实例，仅监听本机地址，未改系统服务或项目依赖，验证后关闭。

2026-10-22 第一版策略调整：长沙抽样中小红书 10/10 家无结果，点评日期集中在 2022–2023 年，部分店的分数全部低于 1.0。因此空结果也回落 Brave，分数下限和最长天数均默认 0（不限），先保证结果覆盖，后续再做筛选。依据：上述 sampling.md。

本次第一版调整验证：`mvn -f backend test` 退出码 0，`BUILD SUCCESS`；共 296 个用例，0 失败、0 错误、1 个既有跳过（`PoiMatchingBenchmarkTest.evaluateIndependentHoldout` 需显式设置 `poi.benchmark`）。其中 `DeepSeekWebEvidenceProviderTest` 35 个用例全部通过，覆盖三站点空结果回退、原始期限传递、默认放行旧日期与低分/缺少分数，以及显式配置后仍可筛选。

2026-10-22 v2 决策：默认启用 730 天、最低分 0.2、短摘要半权重，以相关度 0.7 / 新近度 0.3 排序。保留 v1 未筛选基线作为筛空回退，兼顾时效与覆盖率；标题、链接及同店条件属于原有证据有效性要求，不新增绕过路径。依据：本次 v2 任务要求、`DeepSeekWebEvidenceProvider.map` 与新增的 mock HTTP JUnit 用例。上述 v1 验证记录保留为历史，v2 验证见下文。

v2 实际验证（2026-10-22）：`env -u DEEPSEEK_API_KEY mvn -q -f backend test -Dtest=DeepSeekWebEvidenceProviderTest` 退出码 0，44 个用例全部通过（较 v1 新增 9 个测试方法）；`env -u DEEPSEEK_API_KEY mvn -f backend test` 退出码 0、`BUILD SUCCESS`，共 305 个用例，0 失败、0 错误、1 个既有跳过。测试进程先移除真实 DeepSeek 密钥环境变量，Provider HTTP 测试只访问本机模拟服务器。`git diff --check` 通过；未调用真实搜索接口，未提交。

2026-10-23 P2 修复：半衰期 ≤0 按关闭新近度项处理，在计算处校验配置值后再做除法，避免零半衰期与未来日期组合产生 NaN，以及负半衰期与极旧日期组合产生 Infinity。依据：本次任务要求、`DeepSeekWebEvidenceProvider.rankingScore`。新增参数化测试覆盖 0、-1、Integer.MIN_VALUE，混合未来、3000 天前、空日期和近期结果，验证相关度排序以及相关度权重为 0 时保持原序。验证结果记录于根目录 `result.md`。
