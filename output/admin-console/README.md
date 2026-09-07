# ELMA 数据分析工作台

这套管理台从 `c4b883f` 的工作区快照开始实现，复用现有 Vue 3 和 PostgreSQL 数据。前端与 Python 数据服务单独构建、发布；不修改 Java 推荐后端，也不新增业务表。

## 页面与口径

| 主题 | 可以分析的内容 |
| --- | --- |
| 运营总览 | 推荐会话、活跃匿名标识、新标识、接受与反馈比例、前一等长周期对比 |
| 使用与留存 | 每日趋势、请求频次、星期与小时热力、首访 cohort 的第 1/7/14/30 日留存 |
| 推荐效果 | 接受/导航/反馈覆盖、反馈倾向、风险分布、品类和算法分布 |
| 用户地图 | 城市与省级聚合、指标切换、搜索筛选、缩放、城市排名与详情、当前筛选导出 |
| 证据质量 | 匹配有评分、匹配无评分、未匹配、深挖来源状态、补数队列与缓存 |
| 数据管理 | 手动刷新、历史快照、聚合数据导出、表行数、数据口径与旧版导览 |

匿名标识不等同于自然人。跨日活跃标识在 SQL 中去重，不把每日去重人数相加。首访以数据库当前保留记录中的最早请求为准。留存表示首访后的精确第 N 天再次请求推荐；观察日尚未完整结束时显示待观察。环比前期为零时不计算增长百分比。接受/导航/反馈是以推荐会话为分母的独立覆盖率，并非严格按顺序发生的漏斗。

全局日期按 Asia/Shanghai 解释，最多 90 天。地图统计区间与当前快照一致，每个标识按主要活动城市归属，点的位置是行政区中心。表行数、证据缓存、补数队列是刷新时的存量状态，不能当作历史期间指标或人工验证的匹配准确率。

## 本地运行

使用 Node 22、pnpm 11 和 Python 3.11 以上。

```powershell
pnpm dev:console
pnpm typecheck:console
pnpm build:console
```

界面本地地址为 `http://127.0.0.1:5175/console/`。开发服务器将数据请求转发到 8092 端口。独立服务接收标准 PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD 环境变量，不读取前端环境文件，不在响应或日志输出凭据。

```powershell
python -m pip install -r output/admin-console/deploy/requirements.txt
python output/admin-console/server/app.py --host 127.0.0.1 --port 8092 --snapshot-dir output/admin-console/.snapshots --origin http://127.0.0.1:5175
```

离线调试可使用新版控制台导出的聚合 JSON：

```powershell
python output/admin-console/server/app.py --host 127.0.0.1 --port 8092 --snapshot-dir output/admin-console/.snapshots --fixture <新版聚合快照.json> --origin http://127.0.0.1:5175
```

界面须选择该 JSON 中 `meta.periodStart` 至 `meta.periodEnd` 的原始区间；fixture 无法重算其他日期。旧 database-guide fixture 是旧字段和累计地图口径，不用于新版完整页面验收。测试无需访问平台搜索接口，也不会写业务数据。

## 发布

构建产物为 `output/admin-console/dist/`，不入 Git。`deploy/package_release.py --output <archive.tar.gz>` 只打包明确的前端产物、服务代码、聚合查询和离线地理索引；不会带入生产快照、原始用户数据或密钥。

Windows 发布入口（已配置 `elma-gohan` SSH 别名）：

```powershell
powershell -File output/admin-console/deploy/deploy.ps1
# 临时 Node 22 可通过 -NodePath 指定；已构建且验证的产物可加 -SkipBuild。
```

服务器部署入口：`bash output/admin-console/deploy/install.sh /tmp/elma-admin-<release>`。上传目录是已解包的发布归档。

- 站点仍为 `https://elma-gohan.xyz/console/`，沿用现有 Nginx Basic Auth。
- 数据路由为 `/console/data/v1/`，所有路由经过相同认证；写快照操作要求同源与 CSRF 令牌。
- 独立服务 `elma-console` 仅监听 `127.0.0.1:8092`，以专用系统账户运行。
- 服务代码为 `/opt/elma-console/current`，每个发布版本携带独立 venv；数据库环境从现有配置私下映射为 PG*，存于 root 可读的 `/etc/elma-console/database.env`。
- 快照目录为 `/var/lib/elma-console/snapshots`，由服务账户独享。最多保留 12 份，刷新失败保留已有快照。
- 旧合页归档到 `/console/legacy/`；`/console/app/` 和 `/console/api/v1/` 的既有验收功能保留。
- 安装器先备份旧站点、配置、服务版本，在只读首份快照成功后才发布页面。失败恢复站点和服务；不会替换 `/opt/elma-gohan/app.jar`。

需要退回某次发布前的状态时，以 root 执行该发布输出的 `BACKUP` 目录中的 `rollback.sh`；它恢复站点、Nginx 配置和旧服务版本，同时保留分析快照与失败版本供检查。

部署后可运行 `python output/admin-console/deploy/verify_release.py --refresh`，它私下读取既有本地部署凭据，核对公网构建资源、认证边界，并实际刷新 7 天和 30 天快照。发布与验收记录见 [RELEASE.md](RELEASE.md)。

手动刷新是新服务的受认证聚合读取能力。旧版 `serve_dashboard.py` 的本机 PowerShell 刷新桥接没有部署到公网。

## 验证与人工验收

```powershell
pnpm test:run tests/admin-console.spec.ts tests/admin-geo.spec.ts
python -m unittest discover -s tests/admin_console -p "test_*.py"
pnpm typecheck:console
pnpm build:console
```

登录后检查：切换六个主题；选择不同日期并应用；手动刷新后核对快照时间；查看历史快照并导出；地图搜索、切换省份/指标、放大和复位；小屏侧栏与深浅色。图表可用键盘读取日期值。外观由用户人工审查，自动检查聚焦行为与数据正确性。

地图继续使用已有离线地理数据，来源与许可证见 [第三方声明](public/THIRD_PARTY_NOTICES.md)。
