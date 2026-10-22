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
| 已知鉴权/权限业务码、HTTP 401/403 | `CONFIGURATION_ERROR`，共享冷却 15 分钟，之后在同一数据库锁下串行探测 |
| 网络暂时失败、HTTP 5xx | 共享 500～999ms 退避；由有限次数后台任务重试，不在请求内立即循环 |
| 本地截止时间耗尽 | `LOCAL_QUEUE_TIMEOUT`，不误报网络故障或产生上游冷却 |

业务码 301 的永久额度问题修复后，管理员可在确认额度域内所有调用配置一致的前提下执行以下 SQL 恢复。此次未在生产执行：

```sql
UPDATE baidu_call_state
SET blocked_until=now(), failure_reason=NULL
WHERE id=1;
```

队列容量检查和插入放在持有独立事务锁的短事务中，硬上限 1000 条，明确返回 `ENQUEUED / DUPLICATE / FULL / DISABLED`。保留跨实例单消费者、两分钟租约、最多 20 次失败和一天过期。仅实际出站返回上游失败的消费轮次计数；本地排队、预算耗尽、协调不可用和单纯尚未完成不计数。

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


## PR#3 HOLD 修复复核（2026-10-22）

来源：本次任务卡及 `claude-review.md` 的 P1/P2 清单。执行主机 `grok-bot-vm-417527637`，仓库 `/workspace/wt/pr3-fix`，分支 `codex/recommendation-poi-optimization-plan`。只修改代码、测试和文档，不提交、不推送、不评论、不合并。

### P1 的决定与证据

1. **P1-1**：`CONFIGURATION_ERROR` 保留错误分类，移除 infinity 分支，统一冷却 15 分钟。HTTP 401/403 可能是网关或 WAF 临时拒绝，不能推导成永久 AK 故障。只有业务码 301（`PERMANENT_QUOTA`）永久阻断。两类错误均写 ERROR 告警，不输出 AK/URL；`elma.baidu.blocked{reason}` 暴露本实例最后观察到的共享阻断（按到期时间自动归零，并非持续轮询数据库）。协调层用两个实例验证 401/403 冷却范围、期间不调用、到期两路竞争只出站一次、之后成功恢复；301 的 infinity 测试保留。
2. **P1-2**：保留 `AMBIGUOUS` 触发第二页、不完整歧义不写有效负缓存、读取忽略 `reasonBudgetOrAvailability` 旧行。第一页有歧义不能证明其候选已完整；恢复旧行为会让不完整召回阻断后台补数，且可能压住后来得到的正确映射。代价是第一页仍有歧义且有后续结果时，多一次 V3；前台两次调用总预算保持不变，可能占用原本给 V2 的机会。测试只提供 AMBIGUOUS（无 NO_MATCH 混入），明确断言页序 0、1 各一次，并分别验证第二页不可用时立即到期、完整召回时正常歧义 TTL，以及未来到期但带不完整标记的旧行仍重新取证。“保持推荐策略”的冻结保证仅从 **EvidenceBundle 之后**开始，不包括外部召回、匹配和缓存编排。
3. **P1-3**：保留原始哈希基线，不重写预期。新增只读 Git 导出脚本，在临时目录取 `ead3da1^` 的全部 backend 源码；固定取 `0df2b7d` 的夹具及断言，只适配旧版构造器（旧推荐器的 HardFilter 参数、旧 shadow service 构造器）。未拷贝任何当前主代码到旧实现。新增旧实现生成的 `regression-readable.json`，保留候选 ID、分数、模式和完整 selectionSnapshot；当前测试逐场景比较，失败能指出字段差异。原冻结批次有 7 个 EXPLORATION 候选，断言固定为 7。

仓库根目录复核命令（Python 3.12+、JDK 17+、Maven；不需要数据库或地图 Key）：

```bash
python3 backend/tools/verify_recommendation_baseline.py
```

实际结果：

```text
BASELINE_OK revision=0e0d9f90321442fa098622bcbd7399bc4a647822 cases=104 served=104/104 shadow=104/104 config=104/104
READABLE_SUMMARY backend/target/recommendation-baseline-readable.json
```

脚本再次运行还会比较已保存的可读摘要；日志写入 `backend/target/recommendation-baseline-verification.log`，临时源码目录退出时清理。复核证明固定输入在旧代码上的输出相同；不证明这份夹具历史上究竟何时生成，也不覆盖地图实时响应。

### P2 逐项处理

| 审查项 | 处理与验证 |
| --- | --- |
| P2-1 消费连接网络超时 | 在任何 SQL 前设置 1250ms network timeout，保留 1s query timeout；真实 PostgreSQL 连接 spy 验证调用顺序。 |
| P2-2 领取消耗重试 | 领取只续两分钟租约。通过跨调度线程传播的出站失败标记，每轮有实际上游失败时才加一次 attempts；21 次本地失败后仍为 0，上游失败后重启不重复计数。仍受一天总 TTL 限制。 |
| P2-3 worker Error | 未捕获异常处理器记录错误类型、增加 `elma.baidu.worker.failures`、释放所有等待者，后续调用返回 `DISPATCHER_FAILED`；Provider 实现 Actuator HealthIndicator，worker 死亡时 DOWN，不自动重启。 |
| P2-4 出站结果丢失 | 已获得结果后，冷却、缓存、解锁、连接关闭的协调 IO 失败仍返回该结果，记录告警与 `elma.baidu.coordination.write.failure`；测试在出站后临时重命名状态表制造真实写入失败并恢复。出站前协调失败继续禁止请求。 |
| P2-5 过期任务占队列 | 在合并和容量判断前移除已到期的排队任务，完成其 Future；worker 被占住时也能接收替代任务，过期任务不执行。 |
| P2-6 nanoTime 符号 | 拿锁等待传入由当前请求剩余预算构造的真实截止时间，删去 `Long.MAX_VALUE` 哨兵；锁占用预算测试保留。 |
| P2-7 锁顺序与事务超时 | 直接调用代理后的 SessionWriter.save，给相反候选池设置“首条 store 前”双线程闩锁，断言双方先写相同门店且保存候选顺序不变。另一连接持有门店行锁，验证写事务在约五秒取消语句且没有新增会话。 |
| P2-8 松断言 | 50 路用例要求至少一条 NO_DATA，其他只能为明确 LOCAL_QUEUE_TIMEOUT；前台优先测试要求后台在观测序列的 index=2 执行。 |
| P2-9 回放/消融断言 | 104 组可读旧基线 + 7 个探索样本断言；六组消融完整报告逐字段比较，补 7 天同类、30 天不喜欢同类与边界外可探索、同店 LIKE/NORMAL 均扣 30 分。固定报告移除机器耗时，不将其视为可复现指标。 |
| P2-10 探索率与配置哈希 | 新会话在 request_condition_json 保存实际 servedExplorationRate，不改表结构；导出只从该字段读取，缺失的历史记录不输出，不猜 0.20。API 集成测试改为 0.73 并验证导出、回放和历史记录拒绝；移除未校验的 recordedConfigHash 输入/输出。V9 完整配置哈希仍保存在原表。 |

验证：修复前 `mvn -f backend test` 为 284 run / 0 fail / 0 error / 1 skip；修复后 302 run / 0 fail / 0 error / 1 skip，BUILD SUCCESS（2026-10-22 本地 PostgreSQL，日志 `/tmp/pr3-after-test.log`）。基线复核脚本同日实际运行返回 `BASELINE_OK`。

限制：未请求真实百度/高德、未上线或验证真实 AK 配额；阻断 gauge 是每实例最后观测值；真实 POI 人工样本仍按既定规则跳过。旧记录没有探索率，必须从可靠部署记录人工补齐后才能用严格导出查询，不提供猜测路径。手动验收：定位推荐 → 下一页 → 改条件重推 → 提交反馈，核对候选与证据状态；限流用 stub 先验收。
