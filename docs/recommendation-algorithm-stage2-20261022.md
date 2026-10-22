# 推荐算法职责整理与回放对照

日期：2026-10-22。用户要求先提交第一阶段、再进行下一阶段。第一阶段已提交为 `ead3da1`；本轮在本机 `/home/elma/文档/Code/elma-gohan` 的 `codex/recommendation-poi-optimization-plan` 分支继续实施。用户随后以“创建一个pr 我来人工审核”授权提交、推送当前分支并创建 Draft PR；合并、部署与算法切换仍待单独授权。

本阶段完成行为保持的职责整理、冻结输入回归、V7/V9 选池回放及六组诊断性消融。线上仍使用 `recommendation-v0.4.1` / `risk-v0.3.1`，权重未修改，v0.5 serving 安全闩保持关闭。

## 实现与职责

- `RecommendationService` 保留召回、一次硬过滤、风险/偏好加载和决策编排；引擎输入明确为已通过硬过滤的候选，只负责风险准入、评分和选池。API 测试断言每次创建只执行一次硬过滤。
- `RecommendationFeatures` 冻结每家候选的质量、安全、偏好、预算、距离和近期历史特征，评分、理由、探索资格使用同一组数值。预算/距离适合度、品类权重不再为理由重复计算。`ScoreBreakdown` 和对外响应结构不变。
- `RecentFoodIndex` 每个排序批次只遍历一次历史，再按门店/品类查表。历史特征提取从逐候选多次扫描的 `O(NH)` 收敛为 `O(H+N)`；整体仍有排序等成本。v0.4 和异步 v0.5 各建立自己的不可变索引，不共享可变缓存。保留七天/三十天的包含边界、最强门店惩罚及原有近期多样性公式。
- `UserPreference` 深拷贝口味标签集合，同时保留原遍历顺序，避免异步计算期间外部集合变化或浮点累计顺序改变。旧的逐候选历史扫描方法已移除。
- `RecommendationSessionWriter` 专门以五秒短事务保存完整会话并注册提交后 shadow；`RecommendationResponseAssembler` 只读取已计算/持久化的分项与理由；`RecommendationSnapshotJson` 管理 JSON 存储边界。`RecommendationService` 从 564 行缩至 323 行。历史会话缺省字段继续按原存储契约读取。
- `SafeRegretShadowCalculator` 负责无 IO 的冻结输入计算；`SafeRegretShadowService` 只保留开关、幂等查重和持久化。在线 shadow 与离线诊断调用同一套计算代码。exclude 前后候选相同时复用同一结果，存在实际排除时分别计算，避免混用两套 P90 目标与排名。

这些变动减少计算与职责重复，没有直接移除评分中的重复信号或更换推荐策略。

## 冻结回归与样本边界

在修改算法代码前，基于 `ead3da1` 冻结 13 个人工场景、8 个固定 seed，共 104 次决策。固定时间为 `2026-10-22T12:00:00+08:00`。基线文件是 `backend/src/test/resources/recommendation/regression-ead3da1.json`，记录 v0.4 完整结果、V9 完整 JSON payload 与配置哈希的摘要；生成后没有为通过测试而重写。

场景包括冷启动与 Top-K、单平台、百度不可用、过期证据、exclude、缺失字段、稀疏池、预算/距离边界、排除品类、七天/三十天历史边界、个性化、同分和平台冲突。

整理后 104 次结果均与冻结基线一致，包含分数、理由、选池、探索模式、影子 pre/post-exclude 视图及配置哈希。额外使用 1000 条固定随机历史与七天精确边界做索引语义对照。

这些样本用于机制和边界验证，不包含真实门店标注、真实用户反馈或线上候选分布，不能推导接受率、踩坑率或上线收益。商场楼层和连锁分店的同店匹配继续由既有匹配测试覆盖，本轮没有取得新的人工核验集。

## v0.4 / v0.5 对照结果

完整数值见[机器可读报告](reports/recommendation-replay-20261022.json)。104 次决策中：

- 首推不同 73 次，两套算法均未出现空结果。
- v0.4 抽中候选与该次最高基础分的最大差距为 **30.0225 分**，出现在缺失字段场景。差距按探索加分前的基础分计算。
- v0.5 有 24 次因为没有达到可信安全门槛的候选，使用既有“保守风险最低”确定性策略；这不等于已经证实门店安全。

| 场景 | 首推不同 / 8 | v0.4 最大基础分差 |
| --- | ---: | ---: |
| 冷启动 / Top-K | 7 | 8.1750 |
| 单平台 | 7 | 6.7200 |
| 百度限流不可用 | 7 | 6.7200 |
| 过期证据 | 7 | 8.1750 |
| exclude 后选池 | 7 | 8.1750 |
| 缺失字段 | 3 | 30.0225 |
| 单家候选 | 0 | 0 |
| 预算 / 距离边界 | 4 | 6.1964 |
| 排除条件 | 6 | 8.1750 |
| 近期历史边界 | 7 | 8.1750 |
| 个性化 | 6 | 8.4350 |
| 同分 | 8 | 0 |
| 平台冲突 | 4 | 5.2200 |

“首推不同”表达策略变动，不能等同于变好。同分场景即使首推全变，也没有基础分质量差异；风险门槛和预算层级也会让 v0.5 合理地避开全局最高分候选，不能把所有分差归因于随机性。

## 重复信号与消融

对双平台评分一致、其他输入固定的控制样本，将评分从 4.8 改为 3.9：质量分项使最终分降低 **3.6**，风险安全分项再降低 **4.925**，合计 **8.525**。这是当前默认参数的实测路径分解，不是新的权重建议。

另一个控制实验只把质量计算读取的完整度从 FULL 改为 MINIMAL，保持原风险和其他数据固定，总分降低 **3.5**。这证实完整度有独立质量影响；它与风险/置信度的累计影响仍需真实样本校准。

消融每次只改一条计算路径，不改风险准入、原始证据或生产配置。v0.4 去掉分项时其余权重不重新归一化，所以“最高分数变化”用于定位信号影响，不能直接当成可部署的评分方案。

| 诊断性变化 | 首推变化 / 104 | 有序候选池变化 / 104 | 候选最高分数变化 |
| --- | ---: | ---: | ---: |
| v0.4 去掉排序中的 riskSafety，保留原风险门禁 | 0 | 1 | 24.8125 |
| v0.4 仅中和质量分中的完整度差异 | 0 | 0 | 0 |
| v0.4 去掉近期多样性加权项，保留近期扣分 | 0 | 0 | 8.0000 |
| v0.5 去掉统一不确定性扣分，保留冻结保守风险 | 0 | 0 | 10.0000 |
| v0.5 将质量效用固定为 0.70 | 1 | 17 | 14.9227 |
| v0.5 去掉近期曝光扣分 | 16 | 16 | 40.0000 |

本批缺失字段候选为 INFERRED，分类惩罚已经把其质量分裁到零，因此完整度中和没有再改变该候选分数；不能据此认定完整度没有影响。同样，首推没有变化也不能说明某条路径可以删除：分数变化可能被抽样数量、品类轮询、门槛或固定 seed 掩盖。

本机诊断的 v0.4“评分与选池”中位数约 55μs、P95 约 163μs；v0.5“后验、特征、选池、序列化”中位数约 1621μs、P95 约 3940μs。两组工作量不同，均不含网络或数据库，不是线上延迟指标，也不作为性能回归阈值。

## 可重复执行的工具

生成同一批对照和消融数据，无需数据库和地图 Key：

```bash
cd backend
mvn -B -Dtest=RecommendationRegressionTest,RecommendationReplayTest,RecentFoodIndexTest test
```

输出在 `backend/target/recommendation-replay/`：`report.json`、`example-input.json`。相同输入的决策与消融计数可重复，计时数据会随 JVM 和机器负载波动。

独立 CLI 不启动 Spring，不连接数据库或地图服务。在仓库根目录：

```bash
mvn -B -f backend/pom.xml dependency:build-classpath -Dmdep.outputFile=target/replay-classpath.txt
replay_classpath="backend/target/classes:$(cat backend/target/replay-classpath.txt)"
java -cp "$replay_classpath" com.elma.gohan.application.replay.RecommendationSelectionReplay \
  backend/target/recommendation-replay/example-input.json \
  backend/target/recommendation-replay/example-output.json
```

本次实际执行返回 `scope=FROZEN_SELECTION_ONLY`、`servedPoolMatches=true`、`shadowDifferences=[]`。

`backend/tools/export_recommendation_replay.sql` 是只读导出查询，按推荐 ID 联合 V7 日志、原候选顺序与 V9 快照，只导出 v0.4.1 / v0.5、schema 2、seed 一致的记录。需要操作者明确提供该部署当时的 `served_exploration_rate` 和完整 `shadow_configuration` JSON；这些不能从现有 V7/V9 自动推测。SQL 经真实 PostgreSQL/Flyway/API 集成测试验证，导出由本地 POI stub 生成的会话后可成功重放。

工具重放 V7 的随机选池，以及 V9 已冻结特征的分数、排名、slot、选择概率和目标值；不重算风险、匹配、画像或历史变化。V9 未保存完整评论和全部配置，`recordedConfigHash` 仅原样保留供核对，工具不会宣称已重建并验证整个配置哈希。缺少字段、特征或 manifest 不一致时拒绝输入，不补默认值。

## 验证与阶段结论

使用临时 PostgreSQL 17 容器、`127.0.0.1:55439` 的空测试库和本地高德/百度 stub 执行：

```bash
cd backend
DB_HOST=127.0.0.1 DB_PORT=55439 DB_USERNAME=elma_test DB_PASSWORD= DB_TEST_NAME=elma_test mvn -B clean verify
```

首次完整结果为 282 tests、0 failures、0 errors、1 skipped。新增只读导出集成验证、标签冻结顺序检查后，最终 `mvn -B verify` 为 **284 tests、0 failures、0 errors、1 skipped**；即 283 项通过，真实 POI 人工样本验收按原规则跳过。控制变量分解也包含在最终回归中，另做过一次 2 项诊断测试复跑。最终全量日志：`/tmp/elma-gohan-algorithm-final-20261022.log`；诊断复跑日志：`/tmp/elma-gohan-ablation-20261022.log`。

验证包括原有 API、并发创建、reroll、反馈、影子快照、单次硬过滤、冻结回归和读库导出回放。打包成功，契约仍为 `CONTRACT_OK openapi=3.0.3 operations=6 schemas=26`，`git diff --check` 通过。本轮没有修改前端、接口契约或数据库迁移。

验证完成后已移除本轮临时 PostgreSQL 容器 `elma-gohan-algorithm-test-20261022` 及其匿名卷，55439 端口不再监听。测试和回放日志保留于上述路径。

阶段结论：职责与重复计算已收敛；评分路径的重复影响得到量化。由于缺少真实接受率和风险校准样本，本阶段不发布删除某个因子的权重方案，也不解除 v0.5 serving 安全闩。后续先对获得授权的真实快照做相同回放并人工复核风险差异，再冻结灰度验收门槛。本轮按用户要求提交并创建 Draft PR 供人工审核；未执行合并、生产部署或算法切换。
