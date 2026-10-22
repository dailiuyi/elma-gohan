# 推荐与百度 POI 优化实施记录

日期：2026-10-22。用户以 `Implement the plan` 批准[既定方案](recommendation-poi-optimization-plan-20261018.md)。本轮完成第一阶段的本地稳定性重构，按阶段收尾；推荐策略对照与生产切换进入下一阶段。

主机：`elma-ubuntu-pc`；仓库：`/home/elma/文档/Code/elma-gohan`；分支：`codex/recommendation-poi-optimization-plan`；基线：`0e0d9f9`。用户随后要求先提交第一阶段，再开始算法阶段；本记录随第一阶段本地提交保存，未推送或部署。原有 `README.md.bak` 保留。

## 第一阶段改动

### 一个百度出站调度入口

`CoordinatedBaiduProvider` 接受全部 Place 查询，使用 `BaiduRequestDispatcher` 的单 worker、有界队列和同查询 Future 合并。队列默认最多等待 64 个不同查询，满时返回 `LOCAL_QUEUE_FULL`；前台连续执行三个任务后给后台一次机会。一个等待者超时不取消其他等待者的查询，已过期的排队任务不再出站。

保留同数据库跨实例 PostgreSQL advisory lock、查询缓存与共享冷却。删去旧 `BaiduPlaceRateLimiter` 及其单元测试，限流验证移到实际协调器与本地 HTTP stub 测试，没有保留第二条在线限速路径。`BaiduPlaceApiProvider` 负责 HTTP 与响应转换，应用其他模块使用 primary 协调 Provider。

- `BAIDU_RATE_LIMIT_PER_SECOND` 默认从 3 改为 2，单通道最多一个在途调用。
- 调用结束后到下一次开始至少间隔 `max(400ms, ceil(1000/QPS))`。默认至少 500ms，实际吞吐低于 2 QPS；从完成时刻计间隔可避免冷连接或网络延迟压缩真实出站间隔。
- `BAIDU_RATE_LIMIT_MAX_WAIT_MS=1000` 继续控制拿到跨实例锁后等待下一次出站时刻的时间。跨实例锁等待和本地排队使用调用本身的截止时间。
- 新增 `BAIDU_DISPATCH_QUEUE_CAPACITY=64`。此容量按不同查询计数，活动查询另占一个位置。
- 复用一个 JDK HTTP 客户端，每次请求保留独立超时。

### 连接与事务边界

`BaiduDatabase` 从业务 Hikari 配置复制同一数据库连接设置，建立独立的最多两条连接：一条供出站执行者，一条供跨实例补数消费者。两条连接均不从业务池借用；排队的 HTTP 调用者只等待 Future。协调连接获取最多等待 250ms，SQL 和网络等待有超时；调用者等待 Future 仍受自己的总预算约束。

`RecommendationService.create` 先完成召回、取证、风险、画像与排序，再进入最长五秒的会话写事务，原子保存门店、风险、日志、候选与行为。影子计算仍在提交后调度。缓存映射读取/写入是独立短操作，不持有推荐会话事务。

门店写入改为 `ON CONFLICT(source,source_poi_id)`，不再先查后插；保留原门店 ID、创建时间，较旧数据不覆盖较新数据。先按 source/POI ID 的固定顺序写门店，再按随机候选顺序保存会话，避免两个随机池形成相反的门店行锁顺序。

出站截止时间覆盖调度、协调连接、锁等待及 HTTP。它不保证整个推荐接口两秒返回：高德召回、业务映射查询/写入和推荐持久化有各自的耗时与数据库边界。即使百度通道释放了业务连接，其他业务流量也可能让业务池变忙，不能把此次隔离当成全站延迟承诺。

### 错误分类、冷却和补数

| 错误 | 处理 |
| --- | --- |
| 百度业务码 401/402、HTTP 429 | 数据库共享冷却 30～31 秒；单通道在冷却结束后串行探测 |
| 业务码 302 | 停止至下一个北京时间零点，沿用既有日配额重置策略 |
| 业务码 301 | `PERMANENT_QUOTA`，共享阻断至管理员修复并重置 |
| 已知鉴权/权限业务码、HTTP 401/403 | `CONFIGURATION_ERROR`，停止自动请求，等待配置修复及重置 |
| 网络暂时失败、HTTP 5xx | 共享 500～999ms 退避；由有限次数后台任务重试，不在请求内立即循环 |
| 本地截止时间耗尽 | `LOCAL_QUEUE_TIMEOUT`，不误报网络故障或产生上游冷却 |

永久额度/配置问题修复后，管理员可在确认额度域内所有调用配置一致的前提下执行以下 SQL 恢复。此次未在生产执行：

```sql
UPDATE baidu_call_state
SET blocked_until=now(), failure_reason=NULL
WHERE id=1;
```

队列容量检查和插入放在持有独立事务锁的短事务中，硬上限 1000 条，明确返回 `ENQUEUED / DUPLICATE / FULL / DISABLED`。保留跨实例单消费者、两分钟租约、最多 20 次尝试和一天过期。

每次补数先检查当前门店映射，只处理尚未完成的门店。持久化的有效映射就是门店级进度，不另存一份容易不同步的完成列表。任务集合或位置不同仍可复用已完成门店与精确查询缓存；本轮没有引入空间近似缓存或跨任务逐页游标。

分页未完成时，未匹配和歧义结果均不得变成有效负缓存。缓存读取、后台完成判断和映射 upsert 同时检查 `reasonBudgetOrAvailability`；即使历史数据带有未来有效期，该标记也不能覆盖新鲜成功映射或让后台任务提前结束。完整召回后的正常歧义缓存保留。

### 指标

新增 `elma.baidu.dispatch`、`elma.baidu.result`、`elma.baidu.outbound`、`elma.baidu.cache`、`elma.baidu.queue.wait`、`elma.baidu.connection.wait`、`elma.baidu.permit.wait`、`elma.baidu.outbound.duration`，以及 `elma.baidu.enrichment*` 的入队、完成、剩余、重试、过期、尝试耗尽和任务年龄指标。

标签使用固定原因枚举，不使用查询、门店 ID 或用户 ID。原始业务码继续写脱敏日志，完整 URL 与 AK 不写日志。

## 本地验证

测试使用临时 Docker 容器 `elma-gohan-test-20261022`、PostgreSQL 17、`127.0.0.1:55439` 的 `elma_test` 空库，以及本地高德/百度 HTTP stub；未读取生产密钥，未请求真实地图 API。

先增加错误分类及不完整歧义测试，在修复前得到 13 项中的 4 项断言失败；修复后聚焦测试通过。连接隔离通过占满仅有两条连接的业务池后并发调用协调器验证，不把源码隐患直接写成已确认的线上根因。

最终后端命令：

```bash
cd backend
DB_HOST=127.0.0.1 DB_PORT=55439 DB_USERNAME=elma_test DB_PASSWORD= DB_TEST_NAME=elma_test mvn -B verify
```

最终结果：277 tests，0 failures，0 errors，1 skipped，`BUILD SUCCESS`；即 276 项通过，真实 POI 人工样本验收按原规则跳过。完整日志：`/tmp/elma-gohan-final-verify-20261022.log`。此前还完成过一次 `mvn -B clean verify`，结果相同；最后的 `verify` 包含后续优先级与超时分类修正。

JAR 冒烟：使用同一临时测试库在 `127.0.0.1:18082` 启动打包结果，百度禁用且高德地址指向本地不可用端口，避免外部调用；`curl --fail --silent --show-error http://127.0.0.1:18082/actuator/health` 返回 `{"status":"UP"}`。Flyway V10 success。冒烟完成后正常停止此进程，临时 PostgreSQL 容器和其匿名数据卷已移除；18082/55439 均不再监听。冒烟日志：`/tmp/elma-gohan-smoke-20261022.log`。

验收覆盖：

- 50 个相同查询合并一次，短等待者超时后其他等待者仍获得结果。
- 不同查询的有界队列、过期任务不执行、前台优先及后台进度。
- 占满业务连接池时的 50 个不同调用，在预算内成功或明确超时，实际在途峰值为 1，默认滚动一秒请求数不超过 2。
- 两个 provider 实例共享真实数据库锁和缓存，401/402/301/302 跨实例阻断。
- 999 条队列同时抢最后一个位置，得到一次入队、一次已满，最终恰好 1000 条。
- 后台重启与租约恢复，完成门店不重试，不完整歧义不结束任务。
- 两个推荐请求同时取证时均无推荐事务，最终均返回 201；无重复门店或唯一键冲突。
- 晚到的不完整 NO_MATCH/AMBIGUOUS 不覆盖新鲜成功映射。
- 既有 API 形状、reroll、反馈、影子快照与其他领域测试。

`python3 contracts/validate_openapi.py` 返回 `CONTRACT_OK openapi=3.0.3 operations=6 schemas=26`。接口和数据库 schema 均未修改，未增加或改写 Flyway 迁移。前端无改动，未执行前端构建或视觉自动化。

## 下一阶段与尚未验证项

下一阶段只做算法的职责整理、固定 seed/时间回放和 v0.4/v0.5 消融对照。当前 `recommendation-v0.4.1` 与 `risk-v0.3.1` 继续服务；v0.5 的 serving 安全闩保持关闭，未启用动态 reroll、成对偏好学习或新权重。

生产构建、实际连接池状态、控制台“3”究竟表示何种额度，以及开发/生产/其他程序是否共用额度，仍待发布前核对。同数据库的多实例受统一控制；不同数据库或其他程序共用额度，需要统一调用出口或明确分配子预算。本次未将实际生产异常归因为已经复现的唯一根因。

手动验收：首次定位推荐 → 连续“下一页” → 修改条件重新推荐；核对耗时、候选、反馈和证据状态。百度限流情形先使用测试 stub 验收。推送、PR、生产部署及真实额度验证需要单独授权。
