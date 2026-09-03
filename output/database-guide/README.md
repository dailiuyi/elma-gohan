# ELMA Gohan 离线运营数据看板生成器

这个目录同时保留两部分能力：

- `index.html`：可直接双击打开的单文件运营看板，内含原数据库关系图、表目录、用户地图、人工连接说明和常用查询。
- `generate_dashboard.py`：连接 PostgreSQL，只执行有行数上限的只读聚合 SQL，然后原子更新 HTML 中的 JSON 数据岛。
- `data/china-prefecture-grid.json`：固定版本的离线地级市索引，把 PostgreSQL 返回的 0.1° GCJ-02 网格归属并合并为地级市；浏览器不会收到网格坐标。

它不是实时后台，不需要修改 Spring Boot，也不会增加 `/admin` API。页面没有 CDN、外部脚本、接口请求或在线地图依赖。

## 首次安装

项目约定使用 Python 3.12；运行时只需要 Psycopg 3：

```powershell
py -m pip install -r output/database-guide/requirements.txt
```

## 连接数据库并生成

### 已配置的生产库一键刷新

本机已配置 `~/.ssh/elma_gohan_ed25519` 时，直接在仓库根目录运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File output/database-guide/generate_from_production.ps1
```

需要其他统计窗口时传入 `-Days`：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File output/database-guide/generate_from_production.ps1 -Days 90
```

脚本会读取远端 `elma-gohan.service` 的数据库环境、创建随机本地端口的临时 SSH 隧道、调用只读生成器并关闭隧道。生产数据默认写入被 Git 忽略的 `output/database-guide/dashboard.local.html`；仓库内的 `index.html` 只保留演示数据，避免把运营统计推送到公开仓库。SSH 私钥和数据库密码不会写入仓库或 HTML；不需要保持隧道常驻，也不使用聊天中发送过的服务器密码。

### 手动连接其他数据库

生成器读取 Psycopg/libpq 的标准环境变量：`PGHOST`、`PGPORT`、`PGDATABASE`、`PGUSER`、`PGSSLMODE`，也支持 `DATABASE_URL`。不要把密码写进仓库或命令行。

如果 PostgreSQL 只监听服务器本机，可先在一个 PowerShell 窗口建立 SSH 隧道：

```powershell
ssh -N -L 15432:127.0.0.1:5432 elma-gohan
```

再在另一个窗口生成看板：

```powershell
$env:PGHOST = '127.0.0.1'
$env:PGPORT = '15432'
$env:PGDATABASE = 'elma'
$env:PGUSER = 'elma'

py output/database-guide/generate_dashboard.py --days 30 --prompt-password
```

成功时会输出 `DASHBOARD_OK`，并原子替换 `output/database-guide/index.html`。数据库连接、查询或渲染任一步失败时，原文件保持不变。

如需保留每次快照，可指定另一个输出文件；底稿仍使用当前 `index.html`：

```powershell
py output/database-guide/generate_dashboard.py `
  --days 90 `
  --prompt-password `
  --output output/database-guide/dashboard-2026-09-03.html
```

## 只读和小结果集保证

生成器有四层保护：

1. 连接默认强制 `default_transaction_read_only=on`，并设置 15 秒语句超时与 3 秒锁超时。
2. Psycopg 连接再次设置 `read_only=True`，开始查询前检查 `transaction_read_only`。
3. 每条 SQL 必须通过注册表校验：拒绝写操作、多语句、`SELECT *` 和 `alias.*`。
4. 每条查询都有最大返回行数；游标多返回一行就中止生成。

会话数来自 `recommendation_log`；反馈来自 `user_feedback`；重选来自 `user_behavior.REROLL`。行为和反馈会先按会话聚合，避免一对多联表造成重复计数。Python 只接收日序列、分组、Top N 和 0.1° 地理网格等小结果集，不读取完整 UUID、精确位置或全表明细。

## 地图隐私选项

默认值 `--min-map-users 1` 是为了延续现有导览里保留低样本网格的决定，并用虚线标识少于 3 个匿名标识的气泡。如果 HTML 会发给别人，建议提高门槛：

```powershell
py output/database-guide/generate_dashboard.py --min-map-users 3 --prompt-password
```

生成器最多接收 120 个聚合网格，可用 `--max-map-points` 调整，上限为 200。网格会在 Python 内存中归属并合并到地级市，HTML 只保存城市全称、行政区锚点和汇总数字。被阈值、上限或行政区匹配隐藏的数据只保留合计，不输出网格位置。

地级市索引来自 `chinese-global-compliant-geodata` 的 `chn-level-2.json`，固定提交、原文件 SHA-256 和许可信息记录在 `THIRD_PARTY_NOTICES.md`。需要更新数据源时才运行一次：

```powershell
py output/database-guide/build_prefecture_grid.py <chn-level-2.json> `
  --source-commit <上游提交哈希>
```

## 数据库版本兼容

- V1：总览、用户趋势、反馈、候选品类、风险分布和地图可用。
- V6：增加行为转化、选择模式与风险校准。
- V9：增加 shadow 快照覆盖、首选分歧和选择原因。

生成器先探测实际表、视图和列，再选择查询；缺少 V6/V9 时相应模块显示“不可用”，不会把缺失误写成 0。V9 覆盖率只表示快照落盘覆盖，不代表 shadow 成功率。

用户主动删除匿名数据后，相关会话、行为和反馈会消失，因此看板展示的是“当前保留数据”，不是不可变的历史累计。

## 验证

不连接数据库的完整回归：

```powershell
py -m unittest discover -s tests/database_guide -p "test_*.py" -v
pnpm exec vitest run tests/database-guide.spec.ts
py output/database-guide/generate_dashboard.py --check
```

如果本机已安装 PostgreSQL 17，可再运行隔离实库验收。脚本会在系统临时目录初始化一次性数据库、应用 V1-V9 迁移、生成看板，然后停止并清理该实例：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File tests/database_guide/run_postgres_integration.ps1
```

用内置聚合 fixture 生成临时验收文件：

```powershell
py output/database-guide/generate_dashboard.py `
  --fixture tests/database_guide/fixtures/sample_snapshot.json `
  --output "$env:TEMP/elma-dashboard-sample.html"
```

fixture 模式只用于开发和页面验收，文件会明确标记为本地验收快照；日常查看请使用数据库模式。
