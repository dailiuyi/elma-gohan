# 后端异常路径补测记录

日期：2026-10-23。仓库：`/workspace/wt/more-tests`；分支：`chore/more-tests-20261023`。

本次只新增测试与调整本地 HTTP 测试桩，未修改 `src/main`、API 契约或依赖。未执行 git commit、推送或部署。

补测方向：限流拒绝、中断标记与后续恢复；缓存失败传播、并发跟随者异常、失败后的重试、无诊断与零成功页不缓存、显式失效；网页空结果、无效响应、失败后回退恢复、旧查询模式回退和 URL 去重；深挖来源失败、空值、缓存写失败、执行队列拒绝、超时取消与分析失败后的清理；地图连接、超时、重置、空响应重试和无效坐标、字段截断；百度协调层禁用、序列化与数据库故障。

本分支无 DeepSeek，测试对应现有 Brave／百度 AI 搜索与 DeepEvidenceService。外部 HTTP 使用本地测试服务器或 Spring MockRestServiceServer，不访问真实地图或搜索接口。测试使用固定虚构凭据。缓存 TTL 与正常并发合并已有测试，本次保留并通过完整回归。

## 实测覆盖率

来源：同一工作区修改前、修改后的全量 Maven 测试生成的 `backend/target/site/jacoco/jacoco.csv`；口径为 LINE_COVERED / (LINE_COVERED + LINE_MISSED)，整体按全部 CSV 行求和，不按类名去重。

| 类 | 修改前 | 修改后 | 新增测试执行数（含参数化） |
| --- | --- | --- | --- |
| 整体 | 5974/6582（90.76%） | 6040/6582（91.77%） | 50 |
| AmapRateLimiter | 18/19（94.74%） | 19/19（100.00%） | 2 |
| BaiduPlaceRateLimiter | 20/21（95.24%） | 21/21（100.00%） | 2 |
| PoiRecallCache | 90/94（95.74%） | 93/94（98.94%） | 5 |
| BraveWebEvidenceProvider | 139/155（89.68%） | 144/155（92.90%） | 9 |
| DeepEvidenceService | 183/217（84.33%） | 203/217（93.55%） | 7 |
| AmapClient | 181/203（89.16%） | 200/203（98.52%） | 9 |
| AmapResponseMapper | 129/142（90.85%） | 138/142（97.18%） | 12 |
| CoordinatedBaiduProvider | 57/64（89.06%） | 62/64（96.88%） | 4 |

总计新增 35 个测试方法，参数化展开后新增 50 次测试执行；测试数从 300 增至 350，覆盖行增加 66 行。

## 验证

修改前、修改后均执行：

```bash
mvn -B -Dmaven.repo.local=/tmp/elma-m2 -Djacoco.append=false -f backend/pom.xml test
```

修改后结果：`BUILD SUCCESS`，Tests run: 350，Failures: 0，Errors: 0，Skipped: 1。唯一跳过项为基线已有的 `PoiMatchingBenchmarkTest`，原因是系统属性 `poi.benchmark` 未设置；本次未新增跳过项。新增测试均执行通过。

另已执行两组定向测试，第一组 66 次通过；第二组初次因测试内重设 Mockito 抛错桩触发异常而失败，改为按顺序返回后已由上述完整回归验证通过。`git diff --check` 通过。

## 改动文件

- `backend/src/test/java/com/elma/gohan/provider/poi/amap/AmapRateLimiterTest.java`
- `backend/src/test/java/com/elma/gohan/provider/evidence/BaiduPlaceRateLimiterTest.java`
- `backend/src/test/java/com/elma/gohan/provider/poi/PoiRecallCacheTest.java`
- `backend/src/test/java/com/elma/gohan/provider/deep/BraveWebEvidenceProviderTest.java`
- `backend/src/test/java/com/elma/gohan/application/DeepEvidenceServiceTest.java`（新增）
- `backend/src/test/java/com/elma/gohan/provider/poi/amap/AmapClientTransportTest.java`（新增）
- `backend/src/test/java/com/elma/gohan/provider/poi/amap/AmapResponseMapperTest.java`
- `backend/src/test/java/com/elma/gohan/provider/evidence/CoordinatedBaiduProviderTest.java`（新增）
- `docs/backend-test-coverage-20261023.md`（本记录）
