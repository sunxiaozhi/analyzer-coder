# 安装、运行与发布

版本：v1.1
日期：2026-07-31
适用版本：Analyzer Coder 1.0.0

## 1. 支持结论

系统支持 Java 17 可运行的 Windows 和 Linux。Linux 发布通过两条路径交付：

1. 推荐：`compose.prod.yaml` 一次启动 PostgreSQL/pgvector、后端和前端/Nginx。
2. 传统主机：后端 JAR + systemd，前端静态文件 + 宿主机 Nginx。

`.github/workflows/linux-ci.yml` 在 Ubuntu runner 执行后端测试、前端构建和发布产物组装。正式上线仍应在目标 Linux、文件系统、域名和网络策略下完成一次验收。

## 2. 版本构建

要求：Java 17、Maven 3.9+、Node.js 20+、npm、Git 2.30+。

```bash
mvn -pl backend -am clean package
cd frontend
npm ci
npm run build
```

产物：

- `backend/target/codebase-knowledge-backend-1.0.0.jar`
- `frontend/dist/`

当前基线执行 44 个后端测试；前端构建包含 `vue-tsc --noEmit`。

## 一键启动（推荐）

首次启动会自动：

- 检查 Docker 与 Compose v2；
- 创建 `.env.production` 和 `runtime/` 数据目录；
- 生成随机 PostgreSQL 密码、初始管理员密码和 LLM 主密钥；
- 校验 Compose、构建镜像并启动全部服务；
- 等待后端健康检查并显示访问地址。

Windows PowerShell：

```powershell
pwsh -File scripts/start.ps1
```

Linux：

```bash
bash scripts/start.sh
```

首次执行会在终端显示初始管理员密码，请立即保存；`.env.production` 已被 Git 忽略，脚本不会覆盖已有文件。默认只监听 `127.0.0.1:8088` 并使用 HTTP bootstrap 模式。

配置好 `deploy/nginx-compose-edge.conf`、域名和证书后，以 HTTPS 模式启动：

```powershell
pwsh -File scripts/start.ps1 -Https
```

```bash
bash scripts/start.sh --https
```

已有镜像且不需要重新构建时可使用 `-NoBuild` 或 `--no-build`。脚本结束时会输出查看日志和停止服务的命令。Secure Cookie 开启后必须通过 HTTPS 域名访问，不能继续使用普通 HTTP。
## 3. 本地开发

### 3.1 PostgreSQL

复制开发环境模板，必须修改数据库密码：

```powershell
Copy-Item .env.example .env
```

Linux/macOS：

```bash
cp .env.example .env
```

启动：

```bash
docker compose up -d postgres
docker compose ps
```

开发 Compose 只把 PostgreSQL 绑定到 `127.0.0.1`。Flyway 默认启用，当前执行 `V1__baseline.sql`。

### 3.2 后端

PowerShell 示例：

```powershell
$env:APP_INITIAL_ADMIN_USERNAME = "admin"
$env:APP_INITIAL_ADMIN_PASSWORD = "请替换为强临时密码"
$env:APP_REPOSITORY_ALLOWED_ROOTS = "C:\workspace"
$env:APP_MANAGED_DATA_ROOT = "C:\analyzer-coder-data"
$env:APP_LLM_MASTER_KEY = "请替换为至少24字符的随机密钥"
$env:APP_DATASOURCE_PASSWORD = "与 .env 中 POSTGRES_PASSWORD 一致"
mvn -pl backend spring-boot:run
```

Linux 示例：

```bash
export APP_INITIAL_ADMIN_USERNAME=admin
export APP_INITIAL_ADMIN_PASSWORD='replace-with-a-strong-password'
export APP_REPOSITORY_ALLOWED_ROOTS=/srv/analyzer-repositories
export APP_MANAGED_DATA_ROOT=/var/lib/analyzer-coder
export APP_LLM_MASTER_KEY='replace-with-at-least-24-random-characters'
export APP_DATASOURCE_PASSWORD='same-as-postgres-password'
mvn -pl backend spring-boot:run
```

健康检查：

```bash
curl http://127.0.0.1:8080/actuator/health
```

### 3.3 前端

```bash
cd frontend
npm ci
npm run dev
```

访问 `http://127.0.0.1:5173`。开发环境允许 `APP_SESSION_COOKIE_SECURE=false`；正式 HTTPS 环境必须设为 `true`。

## 4. Linux 生产部署：Compose

### 4.1 准备目录

```bash
sudo mkdir -p /srv/analyzer-repositories
sudo mkdir -p /var/lib/analyzer-coder
sudo chown -R "$USER":"$USER" /srv/analyzer-repositories
sudo chmod -R a+rX /srv/analyzer-repositories
sudo chown -R 10001:10001 /var/lib/analyzer-coder
```

受管目录应使用支持原子移动的本地 ext4/XFS 等文件系统，不建议放在 NFS/SMB。导入的仓库不能包含符号链接。

### 4.2 配置

```bash
cp deploy/.env.production.example .env.production
chmod 600 .env.production
```

必须替换：

- `POSTGRES_PASSWORD`
- `APP_INITIAL_ADMIN_PASSWORD`
- `APP_LLM_MASTER_KEY`
- `APP_CREDENTIAL_MASTER_KEY`
- `APP_REPOSITORY_HOST_ROOT`
- `APP_MANAGED_DATA_HOST_ROOT`

`APP_LLM_MASTER_KEY` 保存模型密钥后必须稳定保管，变更会导致已有密钥无法解密。CodeGraph 容器版本固定为 1.5.0。
`APP_CREDENTIAL_MASTER_KEY` 独立加密 Git/GitLab Token，变更会导致已有仓库凭据无法解密。

### 4.3 构建和启动

```bash
docker compose --env-file .env.production -f compose.prod.yaml build
docker compose --env-file .env.production -f compose.prod.yaml up -d
docker compose --env-file .env.production -f compose.prod.yaml ps
```

生产 Compose 的行为：

- PostgreSQL 不映射到宿主机端口；
- 前端默认只绑定 `127.0.0.1:8088`；
- 仓库根以只读卷挂载；
- 受管数据写入指定宿主机目录；
- 后端启用 Forwarded 头处理与 Secure Cookie；
- 前端容器不对外暴露 Actuator。

### 4.4 HTTPS 边缘代理

复制 `deploy/nginx-compose-edge.conf`，修改域名和证书路径。该模板把 HTTPS 请求代理到 `127.0.0.1:8088`。

```bash
sudo nginx -t
sudo systemctl reload nginx
```

Secure Cookie 开启时不能通过普通 HTTP 登录。正式环境必须使用 HTTPS。

### 4.5 验证

```bash
curl -fsS http://127.0.0.1:8088/
docker compose --env-file .env.production -f compose.prod.yaml logs --tail=100 backend
```

再通过 HTTPS 域名验证：登录、强制首次改密、仓库接入、索引、源码预览、问答、模型连接和 CodeGraph。

## 5. Linux 生产部署：systemd

### 5.1 目录和账号

```bash
sudo useradd --system --home /opt/analyzer-coder --shell /usr/sbin/nologin analyzer
sudo mkdir -p /opt/analyzer-coder/app /opt/analyzer-coder/web
sudo mkdir -p /var/lib/analyzer-coder /srv/analyzer-repositories /etc/analyzer-coder
sudo chown -R analyzer:analyzer /opt/analyzer-coder /var/lib/analyzer-coder /srv/analyzer-repositories
```

### 5.2 安装产物

```bash
sudo cp backend/target/codebase-knowledge-backend-1.0.0.jar /opt/analyzer-coder/app/analyzer-coder.jar
sudo cp -R frontend/dist/. /opt/analyzer-coder/web/
sudo chown -R analyzer:analyzer /opt/analyzer-coder
```

目标机需要 Java 17、Git；使用图分析时还需要 Node.js 20+ 和 CodeGraph 1.5.0，并保证 `analyzer` 用户可执行 `codegraph`。

### 5.3 配置服务

```bash
sudo cp deploy/analyzer-coder.env.example /etc/analyzer-coder/analyzer-coder.env
sudo chown root:analyzer /etc/analyzer-coder/analyzer-coder.env
sudo chmod 640 /etc/analyzer-coder/analyzer-coder.env
sudo cp deploy/analyzer-coder.service /etc/systemd/system/
```

修改环境文件内所有占位密码、主密钥和路径，然后启动：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now analyzer-coder
sudo systemctl status analyzer-coder
sudo journalctl -u analyzer-coder -f
```

复制 `deploy/nginx-host.conf`，修改域名和证书路径后启用。模板通过 HTTPS 提供前端并代理 `/api/`，不会暴露 Actuator。

## 6. 账号和代理安全

- 初始管理员只在账号表为空时创建，并被要求首次改密。
- 管理员重置密码生成安全随机临时密码，只展示一次，24 小时过期，并撤销旧会话。
- Cookie 使用 HttpOnly、SameSite=Lax；生产配置增加 Secure。
- `APP_FORWARD_HEADERS_STRATEGY=framework` 只能在后端仅接受受信代理流量时启用。
- Nginx 传递 `X-Forwarded-For` 和 `X-Forwarded-Proto`，后端审计可记录代理恢复后的客户端地址。
- PostgreSQL、后端 8080 和 Actuator 不应直接暴露公网。

## 7. 升级与回退

升级前：

```bash
pg_dump -Fc -h 127.0.0.1 -U codebase_kb codebase_kb > codebase_kb.backup
```

同时备份 `APP_MANAGED_DATA_ROOT`。然后停止服务、替换 JAR/静态文件或拉取新镜像，再启动并检查 Flyway 和健康状态。

Compose：

```bash
docker compose --env-file .env.production -f compose.prod.yaml pull
docker compose --env-file .env.production -f compose.prod.yaml up -d --build
```

应用文件可以回退；数据库迁移未必可逆，因此任何升级都必须先备份。不要使用 `docker compose down -v`，除非明确要删除数据库卷。

## 8. 发布验收

1. Linux CI 成功，44 个后端测试和前端构建通过。
2. 两个 Compose 文件执行 `docker compose config` 成功。
3. HTTPS 登录的 `AC_SESSION` 响应 Cookie 包含 Secure、HttpOnly、SameSite=Lax。
4. 重置密码不是固定值，旧会话失效，临时密码过期和首次改密生效。
5. 审计记录出现真实客户端地址，而不是容器或代理地址。
6. PostgreSQL、8080 和 Actuator 不可从非受信网络访问。
7. 本地 Git、远程 Git/ZIP、索引、问答、附件和 CodeGraph 完成冒烟测试。
8. 数据库和受管目录备份、恢复流程在预发布环境实际演练。

## 9. 常见问题

- 问答仍为本地回答：检查模型启用、连接检测、外发策略和检索证据。
- CodeGraph 不可用：以服务账号执行 `codegraph --version`，检查 `APP_CODEGRAPH_EXECUTABLE`。
- 仓库路径不允许：配置的根目录必须提前存在，容器内路径固定为 `/repositories`。
- 无法发布快照：确认受管数据位于同一本地文件系统并支持原子移动。
- HTTPS 登录后立即掉线：确认 `APP_SESSION_COOKIE_SECURE=true`、浏览器确实通过 HTTPS、代理传递原始协议。
- `.bat`/`.cmd` 预览乱码：后端在 UTF-8 失败时自动回退 GB18030；二进制或超过 2 MiB 的文件仍会拒绝。
