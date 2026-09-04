# ELMA 产品演示与运营合页

给后续 Agent 的操作说明。这是把两份已经人工审过的静态页合在一起的展示页，不是第三套设计，也不是后端功能。

## 这是什么

- `output/product-demo/index.html`：产品与算法演示。公开展示口径，不连库。
- `output/database-guide/index.html`：仓库内演示数据的运营看板。
- `output/database-guide/dashboard.local.html`：本机从生产库刷新出的私有快照，已被 Git 忽略。

合页只做一件事：用顶栏在「产品演示」和「运营数据」之间切换。两边的 CSS、正文和脚本都从上面的源页原样拼进来，再用选择器前缀隔开，避免样式互相覆盖。

不要大修源页风格和正文。用户已经审过并表示喜欢。不要再用 `output/elma-merged-demo*.jpg` 或旧的压缩看板稿当设计依据，那是 skill 测试稿。

## 不要做的事

- 不要改 `contracts/openapi.yaml`，不要动推荐排序、Risk、Taste 或客户端契约。
- 不要把合页改成公开目录。页面含生产聚合快照和「人工连接」说明。
- 不要在服务器上打开「刷新生产数据」。那个按钮只属于本机 `serve_dashboard.py`，公网 Nginx 必须保持隐藏。
- 不要把 `dashboard.local.html`、`.deploy-credentials` 或服务器 htpasswd 提交进 Git。
- 不要把数据库密码、SSH 私钥或 `/etc/elma-gohan/elma-gohan.env` 写进 HTML。
- 不要为了合页去改博客根目录 `/var/www/blog`，也不要改 API 的 `/api/v1/`。

## 本地怎么用

源页改完后，在仓库根目录组装：

```powershell
python output/merged-console/assemble.py
```

默认优先用 `dashboard.local.html`（如果存在），这样运营侧数字和本机看板一致。只要仓库内演示数据时：

```powershell
python output/merged-console/assemble.py --from-public
```

指定输出路径：

```powershell
python output/merged-console/assemble.py --from-public --output $env:TEMP/elma-console.html
```

预览：双击 `output/merged-console/open-demo.cmd`。它会先调用 `assemble.py`，再用默认浏览器打开 `index.html`。

`output/merged-console/index.html` 是生成物，已被 `.gitignore` 忽略。不要手改这个文件；改源页后重新组装。

## 怎么部署

前提：本机 SSH 别名 `elma-gohan` 已可用（与刷新生产看板相同）。服务器是阿里云 Debian，Nginx 已托管 `https://elma-gohan.xyz`（博客）和 `https://api.elma-gohan.xyz`（API）。

在仓库根目录执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File output/merged-console/deploy.ps1
```

脚本会：

1. 调用 `assemble.py`（默认带本地生产快照）。
2. 把 HTML 和 Nginx snippet 传到服务器。
3. 安装到 `/var/www/elma-console/index.html`。
4. 写入 `/etc/nginx/snippets/elma-console.conf`，并把它 include 进 `/etc/nginx/conf.d/blog.conf` 的 HTTPS `elma-gohan.xyz` server。
5. `nginx -t` 后 reload。博客首页和 API 健康检查必须仍然可用。
6. 首次部署若还没有 htpasswd，会生成随机密码并打印 `CONSOLE_PASSWORD_CREATED`。之后再部署会打印 `CONSOLE_PASSWORD_KEPT`，不会覆盖现有密码。

线上地址：

```
https://elma-gohan.xyz/console/
```

只发布仓库演示数据、不带本机生产快照时加上 `-FromPublic`。

## 登录密码

这是 Nginx HTTP 基本认证，不是网页里的账号系统。

| 项 | 位置 |
| --- | --- |
| 用户名 | `elma` |
| 密码文件 | 服务器 `/etc/nginx/elma-console.htpasswd` |
| 本机备忘 | `output/merged-console/.deploy-credentials`（Git 忽略） |

改密码（SSH 到 `elma-gohan`，用 root）：

```bash
PASSWORD='新密码'
HASH=$(printf '%s' "$PASSWORD" | openssl passwd -apr1 -stdin)
printf 'elma:%s\n' "$HASH" > /etc/nginx/elma-console.htpasswd
chmod 0640 /etc/nginx/elma-console.htpasswd
chown root:www-data /etc/nginx/elma-console.htpasswd
```

不必 reload Nginx。**文件末尾必须有换行**，否则 Nginx 会忽略这一行，所有登录都 401。

改完后同步本机 `.deploy-credentials`，不要把明文密码写进 README 或提交到仓库。

验证：

```powershell
curl.exe -sI https://elma-gohan.xyz/console/
# 期望 401

curl.exe -sI -u elma:密码 https://elma-gohan.xyz/console/
# 期望 200
```

## 服务器上的文件

| 路径 | 作用 |
| --- | --- |
| `/var/www/elma-console/index.html` | 合页静态文件 |
| `/etc/nginx/snippets/elma-console.conf` | `/console/` location，仓库源是 `deploy/nginx/elma-console.conf` |
| `/etc/nginx/elma-console.htpasswd` | 基本认证，属主 `root:www-data`，权限 `0640` |
| `/etc/nginx/conf.d/blog.conf` | 博客 HTTPS server 里 include 了上面的 snippet |

不要把合页写进 `/var/www/blog`，博客发布会覆盖它。

## 相关文件

| 文件 | 作用 |
| --- | --- |
| `output/merged-console/assemble.py` | 从两份源页生成合页 |
| `output/merged-console/deploy.ps1` | 本机组装并发布 |
| `output/merged-console/install-remote.sh` | 服务器安装脚本，由 deploy.ps1 上传后执行 |
| `output/merged-console/open-demo.cmd` | 本机预览 |
| `deploy/nginx/elma-console.conf` | Nginx snippet 源稿 |
| `tests/merged-console.spec.ts` | 用 `--from-public` 组装到临时文件后检查切换和交互 |

自动检查：

```powershell
.\node_modules\.bin\vitest.cmd run tests/merged-console.spec.ts
```

视觉仍按仓库 `AGENTS.md`：做完代码和自动检查后，把打开方式和要点交给用户人工看，不要主动截图或开浏览器验收。

## 改动时怎么选入口

- 改产品叙事、流程、算法说明：只改 `output/product-demo/`，再组装。需要上线就部署。
- 改运营看板模块、图表、表关系图：只改 `output/database-guide/`。生产数字用 `output/database-guide/generate_from_production.ps1` 刷新 `dashboard.local.html`，再组装并部署。
- 只改顶栏切换或 CSS 隔离：改 `assemble.py`。
- 只改发布路径或认证方式：改 `deploy/nginx/elma-console.conf`、`install-remote.sh`、`deploy.ps1`。

合页不是第二套产品文案源。源页和合页出现差异时，以两份源页为准，重新组装。
