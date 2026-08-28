# ELMA 今天吃什么 V1.0.0

LowRegret V1.0.0 是 Java 模块化单体后端与 uni-app 微信小程序前端。`risk-v0.3.1` 只判断餐厅的客观风险；`taste-v0.1` 从显式反馈、轻量行为与近期饮食历史形成匿名用户画像；`recommendation-v0.4.1` 将两者保持隔离后组合成个性化 LowRegretScore，并保存可确定性重放的随机种子与候选快照。接口事实源是 [`contracts/openapi.yaml`](contracts/openapi.yaml)，个性化规则见 [`docs/V0.4-personalized-decision-loop.md`](docs/V0.4-personalized-decision-loop.md)，正式版变更见 [`docs/releases/v1.0.0.md`](docs/releases/v1.0.0.md)。

## 环境

- Node.js 22 LTS
- pnpm 11
- 微信开发者工具（真机验收时需要 AppID）

```powershell
pnpm install
Copy-Item .env.example .env.local
pnpm dev:mp-weixin
```

在微信开发者工具中导入 `dist/dev/mp-weixin`。本地 H5 视觉检查可运行 `pnpm dev:h5`。

## 验证

```powershell
pnpm typecheck
pnpm test:run
pnpm build:mp-weixin
```

当前闭环：首页默认正餐，可按互斥的距离与预算区间筛选；服务端只返回一家推荐，并允许测试用户最多重新选择 5 次。“就它了”、导航、换一家和跳过形成隐式行为；三态反馈可附带最多 3 个口味标签。长期画像和近期饮食历史只影响下一次新推荐，当前会话最多 6 家候选的风险、个性化分项和顺序快照保持冻结。

首页“隐私与数据”页面会说明位置、匿名编号、推荐与反馈数据的用途。用户可以一键删除当前匿名编号关联的推荐会话、行为、反馈、饮食历史、口味标注和个性化画像，并同时清除本地匿名身份。

## 配置与边界

`VITE_API_BASE_URL` 应包含 `/api/v1`。真实环境配置写入 `.env.local`，不要提交高德 Key 或其他敏感信息。前端不得实现风险计算、推荐排序、餐厅随机选择或高德 Web Service 调用。

后端百度链路由 `BAIDU_ENABLED` 控制，服务端 AK 只允许通过 `BAIDU_MAP_AK` 注入。百度失败、无匹配或字段缺失都不会中断高德主推荐；File Evidence 继续作为评论型扩展点。开发环境变量与降级规则见 [`backend/README.md`](backend/README.md)。

按需深挖由 `BRAVE_ENABLED` 控制，密钥只通过后端 `BRAVE_SEARCH_API_KEY` 注入。它只消费搜索 API 返回的标题、URL、摘要与时间，不访问结果网页、评论区或账号数据；结果只作为弱线索，不改变推荐排序、候选池、reroll 或 TasteProfile。

网络请求统一通过 `src/api/client.ts`，页面不得直接调用 `uni.request`。定位和权限设置分别通过 `src/services/location.ts` 与 `src/services/platform.ts`，页面不得直接调用 `wx.*`。
