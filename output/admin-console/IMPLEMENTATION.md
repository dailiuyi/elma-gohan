# 管理控制台实施约定

升级从 c4b883f 开始。Java backend/ 与业务 contracts/ 不改。新版部署到已有 /console/，现有 Basic Auth 保留。旧合页保存为 /console/legacy/，/console/app/ 与业务代理保留。独立 Python 服务仅监听 127.0.0.1:8092，由 Nginx 认证后代理 /console/data/v1/。数据库只读、聚合查询、地级市聚合，无用户级明细或任意 SQL 接口。

## HTTP 与前端约定

- GET /console/data/v1/state -> {csrfToken, refresh:{running,startedAt,finishedAt,error}, history:[{id,snapshotAt,from,to}], currentId}
- GET /console/data/v1/snapshot?id=可选历史ID -> Snapshot（无快照时 404）
- POST /console/data/v1/refresh，Content-Type application/json，X-ELMA-CSRF header 为 state 返回值。body {from:'YYYY-MM-DD',to:'YYYY-MM-DD'}；202 开始异步刷新，409 已在刷新，400 日期错误。最多 90 天，to 不超过上海当前日期；含同长度前一周期对比。刷新失败保留原快照。
- UI 先 GET state，GET snapshot；选择日期后点“应用区间”启动刷新，刷新按钮重复当前选定区间；轮询 state 完成后拉最新快照。期间展示旧快照并明确区间，不能把旧数据标成新选择区间。历史快照可只读查看与导出。
- 断网、无数据、403、超时有可重试状态。GET 路由 no-store，不把凭据传到浏览器，所有 POST 验证 Origin/Host 和 CSRF。刷新 180 秒上限、单任务互斥，保存最多 12 个快照，仅写独立快照目录。

## Snapshot JSON

尽量兼容 output/database-guide/generate_dashboard.py 的 schemaVersion=1，允许新增字段。

- id: string
- meta: {snapshotAt,periodStart,periodEnd,windowDays,timezone:'Asia/Shanghai',sourceMode:'database'|'fixture',readOnlyVerified,queryCount,queryDurationMs,warnings:string[]}
- overview: {totalRecommendations,totalAnonymousIds,totalRestaurants,totalFeedbacks,periodRecommendations,periodActiveIds,periodNewIds,periodReturningIds,averageCandidateCount}
- funnel: {recommendationSessions,acceptedSessions,navigatedSessions,feedbackSessions,feedbackCount,dislikedSessions,acceptanceRate,navigationRate,feedbackRate}
- daily: [{metricDate,recommendations,activeIds,newIds,accepts,navigations,rerolls,feedbacks,dislikes}]
- behaviors: [{behaviorType,eventCount,sessionCount}]（既有键若不同服务端统一这里）
- feedback: [{result,feedbackCount}]
- risks: [{riskLevel,recommendationCount}]
- categories: [{category,recommendationCount}]
- algorithms: [{algorithmVersion,selectionMode,recommendationCount}]
- evidenceReliability: {queuedTasks,retryingTasks,freshQueries,coolingDown}
- tableRows: Record<string,number>
- shadow: 兼容旧结构；capabilities 与 warnings 如实标明缺失能力。
- locations: {totalAnonymousIds,totalRequests,points:[{code,label,province,longitude,latitude,anonymousIds,requests,lowSample}],unmappedRequests?,note?}。点坐标只能是行政区中心，不是精确用户位置。统计范围和全局 from/to 一致；一个匿名标识仅分配一个主要活动城市，使全国占比分母可比。未归属量必须明确。
- analytics.comparison: {previousFrom,previousTo, current:{requests,activeIds,newIds,acceptedSessions,acceptanceRate,feedbackRate}, previous:同结构}。跨日去重在 SQL 完成，不能用 daily.activeIds 相加。
- analytics.frequency: [{label,users}]，区间每个匿名标识请求次数分桶 1次/2–3次/4–7次/8次及以上。
- analytics.heatmap: [{weekday:1..7,hour:0..23,requests}]，上海时区。
- analytics.retention: [{cohortDate,cohortSize,day1:number|null,day7:number|null,day14:number|null,day30:number|null}]，比例 0..1；首访 cohort、精确第N天再次推荐，不足观察期必须 null，最多90行。
- analytics.quality: {mappingStatuses:[{status,count}],deepStatuses:[{source,status,count}],totalMappings,mappingsWithRatings,freshMappings}。MATCHED 无评分区别 NO_MATCH；非人工真值准确率。

UI：左侧导航，运营总览、使用与留存、推荐效果、用户地图、证据质量、数据管理。旧产品演示/表结构/人工连接通过 /console/legacy/ 入口保留。现代但克制：浅色默认、深色切换、蓝/青强调、清晰留白，中文可读；SVG 图表无 CDN，尊重减少动画。地图组件负责行政区底图、城市排名与详情、气泡/强度显示、搜索、省份筛选、缩放复位和 CSV；小屏可用。

## 文件所有权

- data worker: output/admin-console/server/*.py 和 tests/admin_console/*.py。
- UI worker: output/admin-console/src/App.vue、components/MetricCard.vue、TrendChart.vue、DataBars.vue、src/styles.css、src/types.ts、src/analytics.ts、tests/admin-console.spec.ts。地图通过 import GeoExplorer from './components/GeoExplorer.vue'；props locations 与 rangeLabel:string。
- map worker: output/admin-console/src/components/GeoExplorer.vue、src/geo.ts、src/geo-data.json、tests/admin-geo.spec.ts。props locations 上述对象、rangeLabel string。地图内部样式 scoped，不改全局。
- root: Vite/入口/package脚本、Python服务部署/systemd/nginx/文档、整合与验证。
