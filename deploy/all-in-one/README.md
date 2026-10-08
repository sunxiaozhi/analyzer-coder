# 单镜像安装、启动与升级

唯一部署方式是一个完整 Linux 镜像、一个容器。镜像内包含前端、Java 17 后端、PostgreSQL 17/pgvector、Nginx、Git/SSH、Node.js/npm、CodeGraph 1.6.0 与对应架构原生运行时、Python 和 MCP 适配器。外部模型服务和模型权重、API Key、业务数据不会装进镜像；模型在页面配置，未配置时仍可使用本地检索。

目标机只安装 Docker 和支持 `--wait` 的 Docker Compose。Windows 使用 Docker Desktop 的 Linux 容器模式以及 PowerShell 7；Linux 使用 Bash。镜像架构必须与机器一致，默认 linux/amd64，ARM64 构建时指定 linux/arm64。所有命令在部署服务器执行，Docker 应连接该服务器本机引擎。

## 最快安装

把完整发布包解压到固定部署目录，进入包含 `image.tar`、`analyzer.sh` 的目录。

Linux：

```bash
bash analyzer.sh start
```

Windows：

```powershell
pwsh -File analyzer.ps1 start
```

脚本自动加载本地镜像（首次导入校验 SHA256SUMS）、生成四个独立随机密码/密钥、建立 data/config 目录、启动服务并等待健康。无需单独安装数据库、Java、Node 或 CodeGraph。已有配置和数据不会被初始化覆盖。

默认访问 `http://服务器IP:18081`；放行服务器防火墙/安全组中的相应 TCP 端口。账号为 `.env` 中的 `APP_INITIAL_ADMIN_USERNAME`（默认 admin），初始密码查看 `APP_INITIAL_ADMIN_PASSWORD`，首次登录按提示改密。脚本不会向终端输出密码。

需要在首次启动前调整端口、内网 Git 或其他配置时，先运行 `bash analyzer.sh init` / `pwsh -File analyzer.ps1 init`，编辑下面的文件，再运行 start。

## 固定目录

```text
安装目录/
├─ analyzer.sh / analyzer.ps1   # 唯一日常操作入口
├─ compose.yaml / image.env     # 发布版本自带的启动编排和镜像标记
├─ image.tar / SHA256SUMS       # 离线镜像与交付文件校验
├─ MANIFEST.json / README.md
├─ .env                        # 首次启动生成；端口、密码、密钥、JVM 等
├─ config/                     # 首次启动生成；整目录只读挂载到 /config
│  ├─ application.yml          # 后端附加配置
│  ├─ nginx.conf               # HTTP 代理、上传限制、HTTPS 代理头等
│  └─ postgresql.conf          # PostgreSQL 性能参数
├─ data/                       # 宿主机目录，挂载到 /data
│  ├─ postgres/                # 数据库
│  ├─ managed/                 # 项目工作区、图谱和附件
│  ├─ repositories/            # 本地源仓库接入目录
│  └─ logs/                    # 后端日志
└─ backups/                    # 备份命令输出；不参与镜像打包
```

数据和配置使用目录 bind 挂载，没有隐藏的命名数据卷。可直接备份、查看宿主机目录。选好固定安装目录后不要直接搬迁正在运行的实例；多个实例在各自 `.env` 中设置不同的 `COMPOSE_PROJECT_NAME` 和 `APP_HTTP_PORT`。脚本会拒绝操作属于另一安装目录的同名 Compose 项目。

## 配置位置

| 文件 | 修改内容 |
| --- | --- |
| `.env` | ANALYZER_IMAGE、APP_HTTP_PORT、APP_HTTP_BIND_ADDRESS、数据库及管理员初始化配置、两个独立主密钥、JAVA_TOOL_OPTIONS、内网 Git 主机 |
| `config/application.yml` | Spring Boot 标准属性；模型端点例外、会话时限、索引/图谱任务参数等，覆盖镜像默认配置 |
| `config/nginx.conf` | Nginx 标准配置；上传大小、代理超时、转发协议等 |
| `config/postgresql.conf` | PostgreSQL 标准配置；shared_buffers、max_connections 等 |

编辑后运行 restart，脚本重新创建容器以载入 `.env` 和所有外挂配置。`.env` 使用 Docker Compose 的环境文件语法，手工填写含 `$`、`#` 的值时使用单引号。生成的随机值没有这些解析问题。

容器内部端口和路径固定：Nginx 8080、后端 8081、数据库 5432；数据库和后端只监听容器回环。对外只映射 APP_HTTP_PORT。PostgreSQL 认证文件保存在 data/postgres，后端受管数据固定 /data/managed。配置这些内部连接路径/端口不能改变单镜像拓扑；入口强制保持内部连通。自定义仓库读取路径应位于 `/data/repositories`。

管理员密码 8–64 字符，建议同时包含大小写、数字和特殊字符。两个主密钥独立生成并稳定保管；升级和恢复不能更换。数据库密码在首次建库时写入，改 `.env` 不会自动修改已有数据库密码；管理员初始密码只在空库首次创建账号时生效。

内网 Git 示例：`.env` 中填写 `APP_REPOSITORY_TRUSTED_PRIVATE_HOSTS=gitlab.company.local`，多个精确主机用逗号分隔。内部 CA 无法安装时，可在 `APP_REPOSITORY_INSECURE_TLS_HOSTS` 填写同一受信主机。

HTTPS 可由外部反向代理终止；将 `.env` 的 APP_SESSION_COOKIE_SECURE 设为 true，并将外挂 nginx.conf 的 X-Forwarded-Proto 固定为 https，入口仅接受受信代理的流量。HTTP 部署保持该 Cookie 配置为 false。

## 日常维护

Linux 使用 `bash analyzer.sh 动作`，Windows 使用 `pwsh -File analyzer.ps1 动作`。

| 动作 | 行为 |
| --- | --- |
| init | 导入镜像并初始化缺失配置，不启动服务 |
| start | 启动服务，等待健康；已运行且未变更时重复执行保留容器 |
| stop | 优雅停止；保留全部数据和配置 |
| restart | 重新创建容器，加载外挂配置，等待健康 |
| status | 查看运行及健康状态 |
| logs | 跟踪最近 100 行日志，Ctrl+C 退出查看 |
| backup | 暂停服务并备份 data、config、.env；原来运行时备份后自动恢复 |
| upgrade 新包目录 | 校验、导入一张新镜像，更新启动文件，只修改现有 .env 的 ANALYZER_IMAGE，替换容器并等待健康 |

backup 输出带 UTC 时间戳的 backups/analyzer-*.tar.gz。备份包括密码和密钥，限制备份访问权限。数据目录不能在数据库运行时直接复制作为一致性备份。

数据库、Nginx 或后端主进程退出时，容器退出，由 unless-stopped 策略重启。Docker 健康检查同时验证数据库、后端和前端；健康检查失败本身不会触发重启策略。Linux 上应设置 Docker 服务开机启动。

## 一个镜像升级

将新版本完整发布包解压到另一个临时目录，不要覆盖 data、config 或 .env。在原固定部署目录执行：

```bash
bash analyzer.sh backup
bash analyzer.sh upgrade /tmp/analyzer-coder-docker-NEW_VERSION
```

```powershell
pwsh -File analyzer.ps1 backup
pwsh -File analyzer.ps1 upgrade -PackageDirectory C:/temp/analyzer-coder-docker-NEW_VERSION
```

升级仍然只有一张完整镜像。旧容器由 Compose 替换，继续挂载同一 data/config；密码、主密钥、管理员、项目、索引、图谱和附件保留。启动脚本、编排及发布说明同步更新，已编辑的配置不覆盖。失败时返回非零并显示日志，保留数据和配置供诊断，不擅自重建数据库或自动换回旧镜像。

数据库结构迁移后的回滚应使用升级前整套备份和匹配镜像，不能仅更换旧镜像。恢复时使用新的空部署目录：解压匹配发布包，将备份中的 data、config、.env 解压到该目录，再运行 start。PG 主版本变化或旧 Flyway 基线不兼容时，需要单独的数据迁移，不能绕过校验启动。

## 从旧数据卷迁移

旧版完整镜像的 Compose 使用命名卷。首次迁移先停止旧容器并保留原 `.env` 和备份，确认实际数据卷名（旧默认为 analyzer-coder_analyzer-data）。在新发布目录手动初始化 `.env` 后，用新镜像把停止状态的旧卷复制到新的空 data 目录：

```bash
mkdir -p data
docker run --rm --entrypoint sh \
  -v analyzer-coder_analyzer-data:/old:ro \
  -v "$PWD/data:/new" analyzer-coder:1.0.0 \
  -c 'cp -a /old/. /new/'
```

将旧 `.env` 的数据库密码和两个主密钥保留到新 `.env`，ANALYZER_IMAGE 使用新包的 image.env 值。停止并移除旧 Compose 容器（不删除数据卷）后再执行新脚本 start。检查数据和图谱正常后，按备份策略保留旧卷。原宿主机 JAR 部署的数据路径可能不同，须另外迁移数据库和受管目录，不由启动脚本自动猜测。

## 检查与排障

```bash
bash analyzer.sh status
bash analyzer.sh logs
```

- 启动提示占位符：检查 .env，替换所有 replace-with- 值。
- 端口占用：改 APP_HTTP_PORT，再运行 restart。
- 镜像缺失：核对 .env 的 ANALYZER_IMAGE 与 image.env，确保保留对应 image.tar。
- 首次启动超时：查看日志及 data/logs/backend.log，不清空数据库重试。
- 内部健康接口不对公网开放；可运行 `docker compose exec -T analyzer /usr/local/bin/analyzer-healthcheck` 检查。使用 Compose 命令前，Linux 设置 `export ANALYZER_INSTALLATION_ROOT="$PWD"`，PowerShell 设置 `$env:ANALYZER_INSTALLATION_ROOT=$PWD.Path`。
- Linux 以执行脚本的账号创建 .env，权限 600；业务目录内的数据库和后端子目录由容器设置其服务账号权限，不应递归改成宿主机普通账号。Windows 将 .env 和备份访问权限限制为部署账号。

## MCP

HTTP MCP 地址为 `http(s)://平台地址/api/mcp`，使用账户访问令牌。只支持 stdio 的客户端在调用端环境设置 ANALYZER_ACCESS_TOKEN，使用镜像里的适配器：

```bash
export ANALYZER_INSTALLATION_ROOT="$PWD"
docker compose exec -T -u analyzer \
  -e ANALYZER_API_BASE=http://127.0.0.1:8081 -e ANALYZER_ACCESS_TOKEN \
  analyzer node /opt/analyzer-coder/mcp-server/src/server.mjs
```

## 从源码打包

在源码根目录执行。默认多阶段 Docker 构建提供所有编译工具，构建机需要 Node.js 20+、Docker 和 tar，网络需要访问 Docker Hub、npm、Maven 和 Debian 软件仓库。

```powershell
pwsh -File scripts/build-docker-image.ps1 -Version 1.0.0
```

```bash
bash scripts/build-docker-image.sh --version 1.0.0
```

输出 release/analyzer-coder-docker-VERSION/ 以及同名 tar.gz、压缩包校验文件。构建自动验证宿主数据目录、三种外挂配置、登录、pgvector、Git、原生 CodeGraph 索引、MCP 和替换容器后的数据/会话/密钥保留；失败不交付完整包。输出已存在时拒绝覆盖。

需要验收发布包的安装、备份和升级脚本时，可运行 `node scripts/verify-deployment.mjs release/analyzer-coder-docker-VERSION`。测试使用独立目录和 Compose 项目，不操作现有实例。

支持 --platform linux/arm64（PowerShell：-Platform linux/arm64）。构建机已有完整镜像但无法访问 Docker Hub 时，可使用 --runtime-image analyzer-coder:OLD_VERSION（PowerShell：-RuntimeImage），验证并复用本机依赖环境，仍重新编译当前前后端；该模式额外需要本机 Java 17 和 Maven。MCP 依赖锁或架构不一致时拒绝复用，需按原 Dockerfile 重建全部依赖。它交付的仍是完整单镜像，不是差量补丁。
