# ELMA 今天吃什么 V0.1 前端实施计划

## 执行状态

- 当前阶段：V0.1 前端自动化与微信开发者工具验收完成，等待真实后端与真机验收
- 已完成项：工程与视觉基线；定位与匿名身份；统一错误；创建推荐；reroll 与剩余次数；GCJ-02 地图导航；三种反馈与重复提交保护；所有操作 Loading/Error；API、Service、输入、状态及关键页面组件测试；真机验收清单
- 最后验证：Node 22.23.2 下 `pnpm typecheck`、`pnpm test:run` 和 `pnpm build:mp-weixin` 通过（10 个测试文件、32 项测试）；微信开发者工具重新编译后首页正常且项目错误数为 0；生成的微信 WXSS 与 `requiredPrivateInfos` 检查通过；源码边界与契约无改动检查通过
- 阻塞项：本机 `127.0.0.1:8080` 尚无可访问后端；真实接口、地图跳转与反馈落库仍需使用有效微信 AppID、契约兼容后端和真机完成验收
- 视觉截图：`elma-home-visual-gate-wide.png`、`elma-result-visual-gate-wide.png`（当前 Codex 任务可视化目录）

> 后续 Agent 开始工作前必须先阅读本文件、根目录 `AGENTS.md`、`docs/V0.1-development-tasks.md` 和 `contracts/openapi.yaml`。每完成一个阶段，立即更新本节的阶段、验证命令与阻塞项。

## Summary

- 使用 uni-app、Vue 3、TypeScript、Vite、pnpm 和 Node 22 LTS，优先交付微信小程序，同时保持 Android 可适配。
- 当前仓库只有产品文档和 OpenAPI 契约；初始化工程时不得覆盖 `contracts/`、`docs/`、中文方案文档及现有未跟踪的 `AGENTS.md`。
- 本计划保存于 `docs/frontend-v0.1-implementation-plan.md`，用于跨上下文持续恢复进度。

## Implementation Changes

### 1. 工程基线

- 在临时目录生成官方 uni-app Vue 3 TypeScript Vite 模板，再按文件选择性迁入当前仓库，提交 `pnpm-lock.yaml`。
- 配置 `dev:mp-weixin`、`build:mp-weixin`、`typecheck`、`test` 脚本，并补充根目录 README、本地运行步骤和微信开发者工具导入目录。
- 使用 `.env.example` 声明 `VITE_API_BASE_URL=http://localhost:8080/api/v1`；真实地址写入被忽略的 `.env.local`。不加入运行时 Mock。
- 不引入 Pinia 或 UI 组件库；使用 Vue `reactive` 构建轻量推荐状态模块。

### 2. 视觉确认门禁

先实现首页和结果页的可运行视觉骨架，在微信开发者工具中截图并等待用户确认；确认前不继续完整 API 联调。

- 奶白背景 `#F8F8FB`、墨蓝正文 `#18203A`、长春花蓝主色 `#5B61D6`、淡紫 `#D9D2F6`、薄荷 `#BFE8DB`。
- 使用系统中文字体、8px 间距节奏、24px 页面边距和克制的 12px 圆角。
- 仅用少量平面像素火花；禁止角色立绘、渐变、玻璃拟态、大量卡片和外卖 App 式布局。
- 首页只有定位状态、距离、预算、“随便”品类、不想吃输入和主按钮“帮我选”。
- 结果页只展示当前一家餐厅，不出现候选列表。

### 3. 业务闭环

- 在 `src/types` 建立与 `contracts/openapi.yaml` 一一对应的类型，不创造第二套字段含义。
- `src/api/recommendation.ts` 封装三个接口；底层基于 `uni.request`，自动携带持久化的 `X-Anonymous-User-Id`。
- 匿名 ID 使用存储键 `elma.anonymous-user-id.v1`，首次启动生成合法 UUID v4，之后复用。
- `LocationService` 使用前台 `uni.getLocation({ type: 'gcj02' })`；拒绝授权时通过 `PlatformService` 引导打开设置，不申请后台定位，不做前端逆地理编码。
- `NavigationService` 使用 `uni.openLocation`；页面不得直接调用 `wx.*`。
- 首页默认 `radius=1000`、`maxBudget=null`、`category=ANY`。品类区只提供“随便”。
- “不想吃”按中英文逗号和换行拆分，去空、去重；最多 10 项，每项最多 30 字。
- 请求期间禁用重复点击；成功后把推荐存入内存状态并进入结果页。
- 结果页展示餐厅名、格式化距离、人均价格、风险等级和服务端推荐理由。
- “换一家”只调用 reroll；是否显示按钮只依据 `alternativesRemaining`。
- 反馈映射为 `不错→LIKE`、`一般→NORMAL`、`踩坑→DISLIKE`，避免对当前餐厅重复提交。
- 按稳定错误码处理并保留 `traceId` 供排查。

## Public Interfaces

- `createRecommendation(request): Promise<RecommendationResponse>`
- `rerollRecommendation(recommendationId): Promise<RecommendationResponse>`
- `submitRecommendationFeedback(recommendationId, result): Promise<FeedbackResponse>`
- `LocationService.getCurrentLocation(): Promise<LocationCoordinates>`
- `NavigationService.openRestaurant(restaurant): Promise<void>`
- `PlatformService.openLocationSettings(): Promise<void>`

不修改 OpenAPI、后端 DTO、风险算法、排序逻辑或高德服务调用。

## Test Plan

- Vitest 覆盖 API、匿名 UUID、服务、状态、reroll 和反馈防重复。
- Vue Test Utils 覆盖首页默认值、输入校验、Loading、结果缺省字段、风险标签及换一家按钮。
- 执行 `pnpm typecheck`、`pnpm test --run`、`pnpm build:mp-weixin` 和 OpenAPI 校验。
- 微信开发者工具及真机验收完整流程和错误分支。
- 静态检查页面层没有 `wx.*`，构建产物和仓库没有高德 Key。

## Assumptions and Dependencies

- 后端需提供契约兼容地址；真机联调需 HTTPS 合法域名或开发工具临时关闭域名校验。
- 微信 AppID 和生产请求域名由用户在本地或平台配置中提供，不提交敏感配置。
- V0.1 不实现运行时 Mock、登录、评论分析、用户画像、RiskEngine、排序、随机选店或前端高德 Web Service。
- 视觉截图确认是唯一人工审批门；确认后按本计划完成业务与测试，不扩展 V0.1 范围。
