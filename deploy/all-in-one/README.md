# 一体化 Docker 镜像

一个 Linux 镜像、一个容器包含前端静态文件、Java 17 后端、PostgreSQL 17 + pgvector、Nginx、Git、SSH 客户端、CA 证书、Node.js/npm、CodeGraph 1.6.0（含对应 Linux 架构的运行时与解析器）、Python 3 和 MCP stdio 适配器。构建时的 Maven、JDK 和前端编译工具由 Docker 多阶段构建提供。

目标机只需 Docker，使用下列编排时还需 Compose v2；无需安装 Java、Node、数据库或 CodeGraph。镜像不含外部大模型服务、模型权重、API Key、现有业务数据或已导入仓库。未配置模型时可使用本地检索；远程模型、Git 仓库仍需相应网络连接。

## 从源码构建与导出

在项目根目录执行（构建机安装 Docker 和 Node.js 20+）：

```powershell
pwsh -File scripts/build-docker-image.ps1 -Version 1.0.0
```

```sh
bash scripts/build-docker-image.sh --version 1.0.0
```

构建并自动通过容器验收后生成 `release/analyzer-coder-docker-1.0.0/`，包括 `image.tar`、`compose.yaml`、`runtime.env.example`、清单和 SHA256 校验文件。整个目录可复制到离线服务器。ARM64 使用 `--platform linux/arm64`（PowerShell：`-Platform linux/arm64`），跨架构构建及验收需要 Docker 提供相应模拟支持。构建过程中需要访问 Docker 镜像仓库、Maven、npm 和 Debian 软件仓库。

也可仅用 Docker 构建，不安装本地编译工具：

```sh
docker build --platform linux/amd64 -t analyzer-coder:1.0.0 .
```

## 离线服务器启动

进入交付目录，Linux 执行 `cp runtime.env.example .env`，PowerShell 执行 `Copy-Item runtime.env.example .env`。编辑 `.env` 中数据库密码、管理员密码和两个独立主密钥，替换所有 `replace-with-` 值；不要把真实配置放进镜像。

```sh
docker load -i image.tar
docker compose up -d --pull never --wait --wait-timeout 240
docker compose logs -f
```

浏览器访问 `http://服务器地址:18081`，用 `.env` 中的管理员账户登录。只发布 Nginx 的 HTTP 端口，数据库和后端监听容器内部回环地址。

在源码目录运行时可生成随机密钥：

```sh
node scripts/create-docker-env.mjs
docker compose -f deploy/all-in-one/compose.yaml up -d --pull never --wait --wait-timeout 240
```

生成脚本不覆盖已有 `.env`，管理员初始密码从文件读取。没有 Compose 时也可启动：

```sh
docker run -d --name analyzer-coder --restart unless-stopped --stop-timeout 60 --env-file .env -p 18081:8080 -v analyzer-coder-data:/data analyzer-coder:1.0.0
```

## 数据、升级与配置

- `/data/postgres`：数据库；`/data/managed`：仓库工作区、图谱与附件；`/data/repositories`：本地仓库接入目录；`/data/logs`：后端日志。全部位于持久化卷，`docker compose down` 保留数据；`down -v` 会删除卷中的全部数据。
- 升级时先备份数据和 `.env`，加载新镜像、修改 `ANALYZER_IMAGE`，再执行同一条 `docker compose up`。不要更换两个主密钥。数据库密码在首次建库时写入，修改 `.env` 不会自动修改已有数据库用户密码；管理员初始密码也只在空数据库首次创建账号时使用。
- 本地路径必须是容器路径。要导入宿主机目录，可增加只读挂载 `宿主机绝对路径:/data/repositories/import:ro`。受管工作区仍写入 `/data/managed`。
- 内网 Git 主机配置 `APP_REPOSITORY_TRUSTED_PRIVATE_HOSTS`。可用 `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=50` 限制 JVM；图谱构建和数据库也会占用内存。
- 可挂载额外配置文件，例如 `./application.yml:/opt/analyzer-coder/config/application.yml:ro`，用于配置现有后端支持的模型端点例外等选项。内部数据库、后端端口保持默认值。
- HTTPS 可由外层反向代理终止；正式 HTTPS 部署设置 `APP_SESSION_COOKIE_SECURE=true`。内置 Nginx 默认向后端传递 HTTP 协议；外层 HTTPS 部署需通过挂载 Nginx 配置将 `X-Forwarded-Proto` 固定为 `https`，并限制入口只接受受信任外层代理的流量。
- 必需服务退出时容器整体退出，由 Docker 重启策略重启；健康状态可通过 `docker compose ps` 查看。健康检查失败本身不会触发 Docker 自动重启。

## MCP

HTTP 客户端使用 `http(s)://平台地址/api/mcp` 与账户访问令牌。仅支持 stdio 的客户端可执行以下命令（不要分配 TTY）：

```sh
docker exec -i -u analyzer -e ANALYZER_API_BASE=http://127.0.0.1:8081 -e ANALYZER_ACCESS_TOKEN analyzer-coder node /opt/analyzer-coder/mcp-server/src/server.mjs
```

调用端环境中设置 `ANALYZER_ACCESS_TOKEN`；Compose 容器名可用 `docker compose ps -q analyzer` 查询，也可使用 `docker compose exec -T -u analyzer -e ANALYZER_API_BASE=http://127.0.0.1:8081 -e ANALYZER_ACCESS_TOKEN analyzer node /opt/analyzer-coder/mcp-server/src/server.mjs`。

## 构建验证

```sh
node scripts/smoke-docker-image.mjs analyzer-coder:1.0.0
```

验证使用独立临时卷和随机端口，检查首次建库、登录、Git/CodeGraph/MCP 依赖、图谱构建和容器重启后数据保留，最后删除测试容器和测试卷。现有部署和业务数据不参与测试。
