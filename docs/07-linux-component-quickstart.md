# Linux 组件模式启动指南

适用场景：PostgreSQL/pgvector 和 Nginx 使用 Docker 镜像运行，后端 JAR 与前端构建产物由宿主机管理，不进入组件镜像。

## 1. 部署结构

```text
浏览器或 HTTPS 边缘代理
  -> 127.0.0.1:8088
  -> Nginx 容器
       |-- /        -> 宿主机 frontend/dist
       `-- /api/*   -> 宿主机后端 :8080

宿主机后端 :8080
  -> PostgreSQL/pgvector 容器 127.0.0.1:5432
```

组件离线包只包含以下镜像：

- `analyzer-coder/postgres:offline`
- `analyzer-coder/nginx:offline`

## 2. 准备条件

目标 Linux 主机需要：

- x86_64 架构；使用其他架构时必须重新生成对应平台的离线包；
- Docker Engine；
- Docker Compose v2，命令形式必须是 `docker compose`；
- 当前部署用户有权访问 Docker daemon；
- 后端运行需要 Java 17、Git；
- 使用 CodeGraph 时需要 Node.js 20+ 和 CodeGraph 1.5.0；
- 至少 2 GiB 可用内存，推荐 4 GiB 以上；
- 至少 5 GiB 可用磁盘，并为数据库、仓库快照预留额外空间。

Docker 的发行版安装方法以官方文档为准：

- <https://docs.docker.com/engine/install/>
- <https://docs.docker.com/engine/install/linux-postinstall/>

检查环境：

```bash
uname -m
docker version
docker compose version
docker info >/dev/null
java -version
git --version
```

如果 `docker info` 提示权限不足，应先按 Docker 官方文档配置非 root 用户访问，重新登录后再继续。不建议长期使用 `sudo bash install.sh`，否则生成的配置和前端目录会归 root 所有。

## 3. 在构建机器准备应用产物

### 3.1 现场使用完整源码一键构建并启动

如果目标机已经具备 Maven、Node.js/npm、Java、Git、CodeGraph、Docker 和 Compose v2，并且现场保留完整源码，可直接在源码根目录执行：

完全离线时，先在组件离线包目录只导入镜像，不启动另一套 Compose：

```bash
cd /opt/analyzer-coder-components
bash install.sh --load-only
```

然后进入完整源码根目录：

```bash
cd /path/to/analyzer-coder
bash scripts/start-on-site.sh
```

脚本严格依次执行：

1. `npm ci` 和 `npm run build`；
2. `mvn -pl backend -am clean package -DskipTests`；
3. 启动 PostgreSQL/pgvector 与 Nginx；
4. 生成 `.env.application`、管理员初始密码和加密主密钥；
5. 使用宿主机 Java 后台启动后端；
6. 等待后端健康并输出地址、PID 和日志路径。

如果检测到已经导入的 `analyzer-coder/postgres:offline` 和 `analyzer-coder/nginx:offline`，脚本会自动使用 `compose.offline.yaml`，不会访问 Docker Hub；否则使用普通组件 Compose。

前端依赖已经安装且希望跳过 `npm ci` 时：

```bash
bash scripts/start-on-site.sh --skip-npm-ci
```

配置 HTTPS 边缘代理后：

```bash
bash scripts/start-on-site.sh --skip-npm-ci --https
```

现场脚本将后端日志写入 `runtime/logs/backend.log`，PID 写入 `runtime/backend.pid`。它适合快速交付和验收；需要开机自启、故障自动恢复时，继续使用本指南的 systemd 部署方式。

### 3.2 在构建机器生成产物后上传

业务代码不会进入组件镜像。可以在联网的构建机器执行：

```bash
mvn -pl backend -am clean package -DskipTests

cd frontend
npm ci
npm run build
cd ..
```

需要传到目标机的应用产物：

```text
backend/target/codebase-knowledge-backend-1.0.0.jar
frontend/dist/
```

如需在目标机直接构建，则目标机还需要 Maven 3.9+、Node.js 20+ 和 npm，并且构建期间能够访问 Maven/npm 软件仓库。

## 4. 上传并解压组件包

将离线包上传到服务器，例如：

```bash
scp analyzer-coder-components-offline-2026.08.16.tar.gz user@server:/tmp/
```

登录服务器并安装到 `/opt`：

```bash
ssh user@server
cd /tmp
tar -xzf analyzer-coder-components-offline-2026.08.16.tar.gz
sudo mv analyzer-coder-components-offline-2026.08.16 /opt/analyzer-coder-components
sudo chown -R "$USER":"$USER" /opt/analyzer-coder-components
cd /opt/analyzer-coder-components
```

## 5. 导入并启动 PostgreSQL 与 Nginx

执行：

```bash
bash install.sh
```

安装程序依次执行：

1. 校验 `images.tar` 的 SHA-256；
2. 使用 `docker load` 导入两个组件镜像；
3. 生成 `.env.components` 和随机 PostgreSQL 密码；
4. 创建 `frontend/dist`；
5. 启动 PostgreSQL 与 Nginx；
6. 等待两个组件健康。

首次输出的数据库密码必须立即保存。密码也保存在：

```bash
/opt/analyzer-coder-components/.env.components
```

检查状态：

```bash
docker compose \
  --env-file .env.components \
  -f compose.components.yaml \
  ps
```

预期 `postgres` 和 `nginx` 均为 `healthy`。

## 6. 安装前端产物

把构建机器生成的 `frontend/dist` 复制到组件安装目录：

```bash
mkdir -p /opt/analyzer-coder-components/frontend/dist
rsync -a --delete frontend/dist/ \
  user@server:/opt/analyzer-coder-components/frontend/dist/
```

如果已经在服务器上，则执行：

```bash
cp -a /path/to/frontend/dist/. \
  /opt/analyzer-coder-components/frontend/dist/
```

Nginx 使用只读绑定挂载读取该目录。替换静态文件后通常无需重建镜像；如浏览器仍显示旧内容，刷新浏览器缓存或重启 Nginx：

```bash
docker compose \
  --env-file .env.components \
  -f compose.components.yaml \
  restart nginx
```

## 7. 配置宿主机后端

创建运行账号和目录：

```bash
sudo useradd --system --home /opt/analyzer-coder --shell /usr/sbin/nologin analyzer || true
sudo mkdir -p \
  /opt/analyzer-coder/app \
  /etc/analyzer-coder \
  /var/lib/analyzer-coder \
  /srv/analyzer-repositories
sudo chown -R analyzer:analyzer \
  /opt/analyzer-coder \
  /var/lib/analyzer-coder \
  /srv/analyzer-repositories
```

复制后端 JAR：

```bash
sudo cp /path/to/codebase-knowledge-backend-1.0.0.jar \
  /opt/analyzer-coder/app/analyzer-coder.jar
sudo chown analyzer:analyzer \
  /opt/analyzer-coder/app/analyzer-coder.jar
```

复制组件模式环境模板：

```bash
sudo cp deploy/analyzer-coder-components.env.example \
  /etc/analyzer-coder/analyzer-coder.env
sudo chmod 600 /etc/analyzer-coder/analyzer-coder.env
sudo editor /etc/analyzer-coder/analyzer-coder.env
```

必须替换：

- `APP_DATASOURCE_PASSWORD`：使用 `.env.components` 中的 `POSTGRES_PASSWORD`；
- `APP_INITIAL_ADMIN_PASSWORD`：强随机初始管理员密码；
- `APP_LLM_MASTER_KEY`：至少 24 个字符，保存模型密钥后不能随意更换；
- `APP_CREDENTIAL_MASTER_KEY`：与 LLM 主密钥不同的独立随机密钥。

可以生成随机值：

```bash
openssl rand -hex 32
```

组件 Nginx 从 Docker 网桥访问宿主机后端，因此模板使用：

```text
SERVER_ADDRESS=0.0.0.0
APP_SERVER_PORT=8080
```

应通过主机防火墙限制 `8080`，只允许本机和 Docker 网桥访问，不要直接暴露公网。如果主机策略不允许后端监听网桥地址，应改用宿主机 Nginx 部署模式。

## 8. 使用 systemd 启动后端

确认 Java 路径：

```bash
command -v java
```

当前 systemd 模板使用 `/usr/bin/java`。如实际路径不同，需要修改 `ExecStart`。

安装并启动服务：

```bash
sudo cp deploy/analyzer-coder.service \
  /etc/systemd/system/analyzer-coder.service
sudo systemctl daemon-reload
sudo systemctl enable --now analyzer-coder
sudo systemctl status analyzer-coder --no-pager
```

查看后端日志：

```bash
sudo journalctl -u analyzer-coder -f
```

## 9. 启动验收

检查数据库：

```bash
docker compose \
  --env-file .env.components \
  -f compose.components.yaml \
  exec -T postgres sh -c \
  'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

检查后端：

```bash
curl -fsS http://127.0.0.1:8080/actuator/health
```

检查 Nginx：

```bash
curl -fsS http://127.0.0.1:8088/component-health
curl -I http://127.0.0.1:8088/
```

从 Nginx 容器检查后端连通性：

```bash
docker compose \
  --env-file .env.components \
  -f compose.components.yaml \
  exec -T nginx \
  wget -qO- http://host.docker.internal:8080/actuator/health
```

默认 Nginx 只监听服务器的 `127.0.0.1:8088`。从个人电脑访问时，推荐使用 SSH 隧道：

```bash
ssh -L 8088:127.0.0.1:8088 user@server
```

然后在个人电脑访问：

```text
http://127.0.0.1:8088
```

正式外部访问应在前面配置 HTTPS 边缘代理，并将后端环境中的 `APP_SESSION_COOKIE_SECURE` 改为 `true`。

## 10. 日常操作

重新启动组件：

```bash
cd /opt/analyzer-coder-components
bash scripts/start.sh --components
```

查看组件日志：

```bash
docker compose \
  --env-file .env.components \
  -f compose.components.yaml \
  logs -f
```

停止组件：

```bash
docker compose \
  --env-file .env.components \
  -f compose.components.yaml \
  down
```

重启后端：

```bash
sudo systemctl restart analyzer-coder
```

`docker compose down` 不会删除数据库卷。不要使用 `down -v`，除非明确需要永久删除数据库数据。

## 11. 常见问题

### Nginx 返回 502

确认后端正在监听 `8080`：

```bash
sudo ss -lntp | grep ':8080'
curl http://127.0.0.1:8080/actuator/health
```

如果只监听 `127.0.0.1`，Nginx 容器无法访问；检查 `SERVER_ADDRESS=0.0.0.0` 后重启后端。

### 首页是 404 或空白

确认前端产物存在：

```bash
ls -la /opt/analyzer-coder-components/frontend/dist/index.html
```

并确认 `.env.components` 中的 `APP_FRONTEND_DIST_HOST_ROOT` 指向该目录。

### 后端连接数据库失败

确认后端环境文件中的数据库密码与组件配置一致：

```bash
grep '^POSTGRES_PASSWORD=' \
  /opt/analyzer-coder-components/.env.components
sudo grep '^APP_DATASOURCE_' \
  /etc/analyzer-coder/analyzer-coder.env
```

不要把包含密码的输出粘贴到工单或聊天中。

### 远程访问不了 8088

默认只绑定 `127.0.0.1`，这是预期安全行为。使用 SSH 隧道或配置 HTTPS 边缘代理，不建议直接改成公网监听。
