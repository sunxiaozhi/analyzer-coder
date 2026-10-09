# Analyzer Coder 完整镜像离线部署手册

发布版本：{{RELEASE_VERSION}}

镜像：`analyzer-coder:1.0.0`

目标平台：Linux x86_64 / amd64

## 1. 全新安装与数据库初始化

本手册按全新安装编写，部署目录与数据库均从空状态初始化。

首次启动时，系统自动建立数据库表、索引、约束及初始配置。无需手动建表或导入 SQL。

## 2. 镜像与交付文件

完整单镜像包含前端、Java 17 后端、PostgreSQL 17 / pgvector、Nginx、Git / SSH、Node.js / npm、CodeGraph 1.6.0 及 amd64 原生组件、Python、MCP 适配器。一台服务容器即可启动。

外部模型服务、模型权重及 API Key 在平台内配置。Docker 引擎与 Compose 插件需事先安装，不包含在发布包中。服务器导入本地镜像后无需访问镜像仓库；连接外部模型或远程 Git 时仍需相应网络。

| 文件 | 用途 |
| --- | --- |
| `analyzer-coder-docker-{{RELEASE_VERSION}}.tar.gz` | 完整离线发布压缩包 |
| 同名 `.tar.gz.sha256` | 发布压缩包校验文件 |
| 包内 `image.tar` | Docker 完整镜像，可单独 docker load |
| 包内 `analyzer.sh` / `analyzer.ps1` | Linux / Windows 安装与维护入口 |
| 包内 `compose.yaml` / `image.env` | 编排与镜像版本 |
| 包内 `MANIFEST.json` | 架构、镜像 ID 和源码信息 |
| 包内 `SHA256SUMS` / `README.md` | 文件校验及操作说明 |

包内没有生产 `.env`、账号数据、数据库或知识附件。首次初始化会生成密码、主密钥、配置和空数据目录。

## 3. 服务器准备

服务器需为 x86_64 / amd64，已安装 Docker 和支持 `--wait` 的 Docker Compose v2。部署账号须能运行 Docker；Linux 还需 Bash、tar、sha256sum。

```bash
uname -m
docker info --format '{{.OSType}}/{{.Architecture}}'
docker compose version
docker compose up --help | grep -- --wait
```

确认 Docker 使用 Linux 容器。选定固定部署目录（下文 `/opt/analyzer-coder`），保证磁盘可同时容纳镜像、解压包、业务数据与备份；容量随仓库规模增长。为需要访问的平台用户放行所配置端口，默认 TCP 18081。

## 4. Linux 首次安装

将压缩包和对应校验文件上传至服务器 `/tmp`，执行：

```bash
cd /tmp
sha256sum -c analyzer-coder-docker-{{RELEASE_VERSION}}.tar.gz.sha256
mkdir -p /opt
# 首次安装要求目标目录尚不存在，避免覆盖已有实例。
test ! -e /opt/analyzer-coder || { echo '安装目录已存在，请先确认是否已有实例'; exit 1; }
tar -xzf analyzer-coder-docker-{{RELEASE_VERSION}}.tar.gz -C /opt
mv /opt/analyzer-coder-docker-{{RELEASE_VERSION}} /opt/analyzer-coder
cd /opt/analyzer-coder
sha256sum -c SHA256SUMS
bash analyzer.sh start
```

校验应全部为 `OK`。脚本自动加载镜像、生成四个独立随机密码/密钥、初始化配置、启动容器并等待健康。正常结果显示服务 `healthy`。

若首次启动前需修改端口，先执行以下命令，再启动：

```bash
bash analyzer.sh init
# 编辑 .env 中的 APP_HTTP_PORT、APP_HTTP_BIND_ADDRESS 等配置。
vi .env
bash analyzer.sh start
```

脚本不会把密码直接打印到终端。由部署管理员在服务器查看初始账号：

```bash
grep '^APP_INITIAL_ADMIN_' .env
```

打开 `http://服务器IP:18081`，使用初始账号登录并按提示改密。默认账号为 `admin`。密码和两个主密钥保存在 `.env`，必须与数据一起备份并妥善保管。

## 5. 验证与数据位置

```bash
cd /opt/analyzer-coder
bash analyzer.sh status
export ANALYZER_INSTALLATION_ROOT="$PWD"
docker compose exec -T analyzer /usr/local/bin/analyzer-healthcheck
# 内部健康接口不对公网开放。
docker compose exec -T analyzer curl -fsS http://127.0.0.1:8081/actuator/health
# 检查实际执行历史，应仅有 version=1、success=t。
docker compose exec -T -u postgres analyzer \
  psql -U codebase_kb -d codebase_kb -c \
  'SELECT version,description,success FROM public.flyway_schema_history ORDER BY installed_rank;'
```

健康接口预期返回 `{"status":"UP"}`。若修改了数据库名或用户名，相应调整 psql 参数。浏览器确认可登录，并导入一个测试项目验证代码读取和图谱。

**本版使用安装目录内的 bind 挂载，不使用旧版隐藏命名卷。**

| 宿主机位置（安装目录为 /opt/analyzer-coder） | 容器路径 / 用途 |
| --- | --- |
| `/opt/analyzer-coder/.env` | 环境配置、数据库密码、初始化账号、两个主密钥 |
| `/opt/analyzer-coder/config/application.yml` | `/config/application.yml`，Spring Boot 外挂配置 |
| `/opt/analyzer-coder/config/nginx.conf` | `/config/nginx.conf`，代理与上传设置 |
| `/opt/analyzer-coder/config/postgresql.conf` | `/config/postgresql.conf`，数据库调优 |
| `/opt/analyzer-coder/data/postgres` | `/data/postgres`，完整 PostgreSQL 数据 |
| `/opt/analyzer-coder/data/managed/repositories` | `/data/managed/repositories`，项目、分支代码和 CodeGraph 图谱 |
| `data/managed/repositories/项目ID/knowledge/objects` | 知识图片与附件内容，按哈希存储 |
| `/opt/analyzer-coder/data/managed/staging` | `/data/managed/staging`，导入和上传暂存 |
| `/opt/analyzer-coder/data/repositories` | `/data/repositories`，可接入的本地源仓库 |
| `/opt/analyzer-coder/data/logs` | `/data/logs`，后端日志 |
| `/opt/analyzer-coder/backups` | 完整停机备份，包含 data、config、.env |

Nginx 与服务控制台日志通过 `bash analyzer.sh logs` 查看。不要在数据库运行时复制原始 PGDATA 当作一致性备份，也不要手工改名或删附件哈希文件。镜像归档不是业务数据备份。

## 6. 配置与维护

| 命令 | 行为 |
| --- | --- |
| `bash analyzer.sh start` | 启动并等待健康，重复执行保留未变化容器 |
| `bash analyzer.sh stop` | 优雅停止，保留数据和配置 |
| `bash analyzer.sh restart` | 替换容器并载入修改后的配置 |
| `bash analyzer.sh status` | 查看容器及健康状态 |
| `bash analyzer.sh logs` | 查看日志，Ctrl+C 退出查看 |
| `bash analyzer.sh backup` | 停机备份；原来运行时备份完成后恢复服务 |

`.env` 可修改端口、绑定地址、JVM 参数和内网 Git 主机；`config/` 放置后端、Nginx、数据库配置。编辑后运行 restart。数据库密码在首次建库时生效，修改 `.env` 不会自动修改已有数据库密码；初始管理员密码也不会覆盖已存在账号。

不要删除 `data/`、`.env` 或 `config/`。数据库目录由容器服务用户管理，不要递归修改成普通宿主用户。多个实例应设置不同的 `COMPOSE_PROJECT_NAME` 与端口。固定目录不要在运行时搬迁。

## 7. 常见问题

- 启动校验失败：重新上传发布包，不跳过校验。
- 端口被占用：编辑 `.env` 的 `APP_HTTP_PORT`，执行 restart。
- 提示 Compose 项目属于另一安装目录：确认当前目录与现有部署；多实例设置不同项目名。不要直接覆盖原实例。
- Flyway 初始化失败：查看 `data/logs/backend.log`，检查磁盘、数据库权限和初始化错误；不要在未查明原因时删除数据目录。
- 初始化超时：查看 `bash analyzer.sh logs` 和 `data/logs/backend.log`，保留数据库排查原因。
- 未配置外部模型：可先使用本地检索，随后在页面配置可访问的模型服务。

## 8. Windows Docker Desktop

Windows 需要 Docker Desktop 的 Linux 容器模式和 PowerShell 7，镜像仍是 Linux amd64。将发布包解压到固定目录，然后执行：

```powershell
pwsh -File analyzer.ps1 start
pwsh -File analyzer.ps1 status
pwsh -File analyzer.ps1 backup
```

数据同样位于该安装目录的 `data`，配置在 `config` 和 `.env`。Windows 应限制 `.env` 和备份文件的访问权限。
