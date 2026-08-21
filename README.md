# ELMA 家今天的饭 V0.3.1

LowRegret V0.3.1 是 Java 模块化单体后端与 uni-app 微信小程序前端。默认推荐仍只使用高德、百度与 `risk-v0.3`；用户对当前餐厅感兴趣时，可点击“深挖一下这家”，按需读取 B站、小红书和大众点评的公开 Web 搜索线索，生成独立 `deep-risk-v0.1`。接口事实源是 [`contracts/openapi.yaml`](contracts/openapi.yaml)，增量设计见 [`docs/V0.3.1-on-demand-deep-evidence.md`](docs/V0.3.1-on-demand-deep-evidence.md)。

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

当前闭环：首页默认正餐，可选纠偏为中餐（Chinese）、火锅、烧烤、粉面、小吃快餐、西餐、日韩料理、饮品甜品或随便；“不想吃”支持空格、中英文逗号和换行分隔。服务端返回一家推荐并允许测试用户最多重新选择 5 次；“就它了”通过 `uni.openLocation` 打开当前餐厅；三种反馈会更新 TasteProfile，并从下一次新推荐开始影响排序。当前会话候选池冻结，reroll 不重复且不会被反馈重排。

## 配置与边界

`VITE_API_BASE_URL` 应包含 `/api/v1`。真实环境配置写入 `.env.local`，不要提交高德 Key 或其他敏感信息。前端不得实现风险计算、推荐排序、餐厅随机选择或高德 Web Service 调用。

后端百度链路由 `BAIDU_ENABLED` 控制，服务端 AK 只允许通过 `BAIDU_MAP_AK` 注入。百度失败、无匹配或字段缺失都不会中断高德主推荐；File Evidence 继续作为评论型扩展点。开发环境变量与降级规则见 [`backend/README.md`](backend/README.md)。

按需深挖由 `BRAVE_ENABLED` 控制，密钥只通过后端 `BRAVE_SEARCH_API_KEY` 注入。它只消费搜索 API 返回的标题、URL、摘要与时间，不访问结果网页、评论区或账号数据；结果只作为弱线索，不改变推荐排序、候选池、reroll 或 TasteProfile。

网络请求统一通过 `src/api/client.ts`，页面不得直接调用 `uni.request`。定位和权限设置分别通过 `src/services/location.ts` 与 `src/services/platform.ts`，页面不得直接调用 `wx.*`。
