# 百度补数与深挖可靠性 v0.4

本次提供后台补数、严格门店匹配、公开线索检索和消费参考页面。**80% 覆盖率 / 98% 正确率尚未完成真实样本验收**，不能用单元测试通过率代替。

## 行为与配置

| 配置 | 默认值 | 行为 |
| --- | --- | --- |
| `BAIDU_ASYNC_ENRICHMENT_ENABLED` | `true` | 前台最多两次通用分页，逐店检索交给持久化后台任务；关闭时恢复请求内完整召回 |
| `BAIDU_REALTIME_MAX_CALLS` | `2` | 前台百度调用预算 |
| `BAIDU_REALTIME_BUDGET_MS` | `2000` | 百度阶段共享截止时间，包含等待调用许可及 HTTP 请求；不包含高德召回、数据库连接获取或推荐计算 |
| `BAIDU_RECALL_MAX_CALLS` | `22` | 后台完整召回调用预算 |
| `BAIDU_RECALL_TIME_BUDGET_MS` | `8000` | 后台每次处理的召回时间预算 |
| `BAIDU_RATE_LIMIT_PER_SECOND` | `3` | 在原有本地保护之外，数据库调用通道至少间隔 400ms，并且同一时刻只执行一个 HTTP 调用；降低配置会进一步放慢 |
| `ENTITY_STRICT_MATCHING_ENABLED` | `true` | 开启品牌/分店/地址结构校验、最大权重分配及歧义拒绝；关闭时使用保留的旧匹配器 |
| `DEEP_IMPROVED_SEARCH_ENABLED` | `true` | 不限时间检索、品牌位置补查、额外摘要与严格同店过滤；关闭恢复旧查询形态 |

开关只影响对应链路。深挖响应新增字段保持可选，旧客户端继续读取既有风险与链接字段。检索及匹配缓存均区分算法版本；切换开关会重新校验缓存。

## 持久化与并发

Flyway V10 新增 `baidu_call_state`、`baidu_query_cache`、`baidu_enrichment_task`，并为映射增加 `match_algorithm_version`。

- 前后台通过 `CoordinatedBaiduProvider` 共用 PostgreSQL advisory lock。HTTP 调用期间保留锁；节流时间、冷却时间和查询结果独立提交。不同实例必须连接同一个数据库；不同数据库或其他应用共用 AK 不在此协调范围内。
- 精确相同的查询以参数哈希为键复用五分钟结果；并发请求等待同一调用完成后读缓存。不会缓存平台错误。
- 百度业务状态 `401` 冷却 30 秒，`302` 停止请求至下一个北京时间零点；本地许可等待超时为 `LOCAL_QUEUE_TIMEOUT`，协调数据库故障为 `COORDINATION_UNAVAILABLE`。原始密钥不写日志。
- 后台任务按排序后的门店集合、中心和半径去重。消费者跨实例互斥，处理前提交两分钟租约，崩溃后可重新领取。召回未完成十秒后继续，异常等租约结束后重试；任务最多 20 次、有效期一天，入队容量目标 1000 条。
- 完成或过期任务会清理。`NO_MATCH` 只有完整召回才可形成负缓存；基础平台证据有效时，不会仅因为缺少细评分重复查百度。
- 映射使用原子 upsert。晚到或不完整的前台结果不能覆盖新鲜后台匹配；后台写入只服务后续推荐，不重写已生成推荐及 reroll 快照。

## 匹配与诊断

严格匹配保留品牌、括号分店、商场、数字楼层和门牌信息；明确冲突拒绝匹配。缺失信息不补造，客服电话或距离不能单独救回弱名称。品牌归一化仍保留确定性的业态后缀处理，不把名称前缀相同视作已确认别名。

对合格候选求最大权重的一对一分配，保留零权重未匹配节点。逐条移除选中边，比较最优替代方案；总分差小于 0.08 时标为歧义。默认严格接受阈值 0.68，只能在调试集上校准。日志分别记录未召回、规则拒绝、UID 冲突、歧义以及预算/平台未完成状态。

楼层文本的中文数字、复杂地址别名和平台坐标标注质量仍需真实样本验证；百度请求继续显式使用 GCJ-02 输入与输出。

## 深挖与消费参考

首轮店名 + 长沙 + 分店/商圈，次轮品牌短名 + 长沙 + 位置词。两轮均不限时间，每来源最多两次，三个来源共享四秒截止时间；排队消耗预算，截止后不启动下一轮。已有成功来源保留；失败且无可用内容返回 `UNAVAILABLE`，成功检索为空才返回 `NO_DATA`。

Brave 读取最多五段 `extra_snippets`；生产沿用百度 AI 搜索的 `references.content` 上下文辅助同店识别。品牌名本身不足以确认门店。其他分店和多店合集不计入本店线索。成功缓存 12 小时、确认空结果 30 分钟、失败 5 分钟。没有读取平台正文，也没有引入登录态采集或付费数据服务。

响应新增 `consumptionReferences` 和 `suggestedSearchTerm`。参考项分别表达平台人均、营业时间、公开摘要提到的消费关键词，携带来源、链接及已知日期；未知日期不推测。页面首先展示这些参考，公开关键词不写成已核实事实。空结果提供搜索词复制，搜索词不计入已找到的证据。

## 验收与复现

开发检查：`mvn test`、`pnpm typecheck`、`pnpm test:run`、`pnpm build:mp-weixin`、`py contracts/validate_openapi.py`。Node 使用 22，pnpm 使用 11。Python 需要 PyYAML。

`BaiduCoordinationIntegrationTest` 使用 `DB_TEST_NAME`（默认 `elma_test`，必须以 `_test` 结尾）的独立临时 schema，执行真实 PostgreSQL 锁、缓存、401/302 冷却和租约恢复测试，仅访问本地 HTTP stub。正常完成后删除这个 schema，不删除测试库。

真实 POI 验收单独运行：

```powershell
cd backend
mvn '-Dtest=PoiMatchingBenchmarkTest' '-Dpoi.benchmark=D:/path/to/labelled-pois.json' test
```

输入是 JSON 数组，每项包含：

- `district`、`batchId`、`split`（`TUNE` / `HOLDOUT`）、`scenario`（`MALL_FLOORS` / `NEARBY_CHAINS` / `ALIASES` / `STREET_STORES`）。同一商圈不能跨调试集与验收集，同一推荐批次使用相同 batchId。
- `humanVerified: true`、至少两个 `verificationSources`（原始同店核验来源链接）；`expectedBaiduId` 是人工核验 UID，确认百度无对应门店则为 null。
- `restaurant` 使用内部 Restaurant 字段；识别必需字段为 sourcePoiId、name、latitude、longitude、address，可提供 telephone。
- `realtimeCandidates` / `backgroundCandidates` 使用 PlatformEvidence 字段；每项必须明确 source、providerPoiId、status=`AVAILABLE`、name、latitude、longitude、address，可提供 telephone、observedAt 等。要保留错误候选，不能只输入标准答案。

至少 300 家互不重复门店，其中独立验收集至少 200 家、每类至少 20 家。报告输出 `target/poi-matching-report.json`，比较旧/新算法的即时与后台补全结果。覆盖率以双平台存在的门店为分母；正确率以所有自动接受的匹配为分母。后台覆盖率 >=80% 且正确率 >=98% 才通过。没有输入数据时，此项测试明确跳过，不表示验收成功。

深挖另取同一组至少 50 家真实门店，固定来源与观察时间，记录改前/改后原始命中数、同店命中数、来源失败率、延迟及链接可访问性；对同一批用户记录“获得有用信息”和“找到正确门店链接”两项任务结果。不要用展示链接数量替代用户正反馈。

## 当前交付边界

2026-09-06 发布已完成，详见 [发布与验收记录](release-evidence-v04-20260906.md)。服务器现有数据源为百度 AI 搜索，已保留 `BAIDU_AI_SEARCH_ENABLED`、密钥和端点配置；Brave 路径保留兼容能力。查询版本区分供应商，避免旧缓存混用。百度 AI 请求结构按[官方百度搜索文档](https://cloud.baidu.com/doc/qianfan-api/s/Wmbq4z7e5)及旧生产包核对。

仍无至少 300 家独立标注、50 家深挖对照数据及真实用户任务测试。一次线上冒烟不代表达到 80% / 98%，也不能证明三个平台都有内容。

此前本地基础实现全量后端 259 项中 258 项通过、1 项真实 POI 验收跳过。发布增量的百度 AI 兼容和队列版本校验 13 项定向测试全部通过；服务器全量后端复跑曾因资源压力中止，不记为通过。前端 68 项、运营生成器 10 项测试通过；类型检查、H5、小程序构建及契约校验通过。控制台原有的两项标题断言已通过同步源页面修复，没有降低断言。
