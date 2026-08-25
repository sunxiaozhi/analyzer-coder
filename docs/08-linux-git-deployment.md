# Linux Git 拉取与现场一键启动指南

版本：v1.0  
日期：2026-08-17  
适用分支：`v2`

## 1. 部署目标

本方案使用以下结构：

```text
PostgreSQL/pgvector  -> Docker 离线镜像
Nginx                -> Docker 离线镜像
前端                 -> Git 拉取源码后在 Linux 现场执行 npm 构建
后端                 -> Git 拉取源码后在 Linux 现场执行 Maven 构建并用 Java 启动
```

代码不进入 Docker 镜像，也不需要制作源码压缩包。Linux 服务器直接从以下仓库拉取：

```text
https://github.com/sunxiaozhi/analyzer-coder.git
```

## 2. 必要组件

Linux 服务器需要安装：

| 组件 | 建议版本 | 用途 |
|---|---:|---|
| Docker Engine | 当前稳定版 | 运行 PostgreSQL 和 Nginx |
| Docker Compose | v2 | 管理组件容器 |
| Java/JDK | 17 | 编译并运行后端 |
| Maven | 3.9+ | 构建后端 JAR |
| Node.js | 20+ | 构建前端、运行 CodeGraph |
| npm | 随 Node.js | 安装前端依赖和 CodeGraph |
| Git | 2.30+ | 拉取和更新代码 |
| CodeGraph | 1.5.0 | 构建代码图谱 |
| Bash、curl、tar、SHA-256 工具 | 系统自带即可 | 安装、校验和健康检查 |

检查命令：

```bash
docker version
docker compose version
docker info
java -version
mvn -version
node --version
npm --version
git --version
```

安装并检查 CodeGraph：

```bash
sudo npm install --global @colbymchenry/codegraph@1.5.0

codegraph --version
command -v codegraph
```

当前部署用户必须能直接执行 `docker info`。如果只能执行 `sudo docker`，应先按照 Docker 官方文档配置非 root 用户访问，并重新登录。

## 3. 上传组件离线包

在本地机器执行：

```bash
scp analyzer-coder-components-offline-2026.08.16-2.tar.gz \
  <用户名>@<Linux服务器>:/tmp/
```

示例：

```bash
scp analyzer-coder-components-offline-2026.08.16-2.tar.gz \
  deploy@192.168.1.100:/tmp/
```

## 4. 导入 PostgreSQL 和 Nginx 镜像

登录服务器：

```bash
ssh deploy@192.168.1.100
```

创建组件目录并解压：

```bash
sudo mkdir -p /opt/analyzer-coder-components

sudo tar \
  -xzf /tmp/analyzer-coder-components-offline-2026.08.16-2.tar.gz \
  -C /opt/analyzer-coder-components \
  --strip-components=1

sudo chown -R "$(id -un)":"$(id -gn)" \
  /opt/analyzer-coder-components
```

只导入镜像，不在组件包目录启动 Compose：

```bash
cd /opt/analyzer-coder-components
bash install.sh
```

检查镜像：

```bash
docker image ls | grep analyzer-coder
```

应看到：

```text
analyzer-coder/postgres   offline
analyzer-coder/nginx      offline
```

## 5. 使用 Git 拉取代码

首次部署：

```bash
sudo mkdir -p /opt/analyzer-coder

sudo chown "$(id -un)":"$(id -gn)" \
  /opt/analyzer-coder

git clone \
  --branch v2 \
  --single-branch \
  https://github.com/sunxiaozhi/analyzer-coder.git \
  /opt/analyzer-coder
```

进入源码目录：

```bash
cd /opt/analyzer-coder
```

确认必要文件存在：

```bash
ls pom.xml \
  backend/pom.xml \
  frontend/package.json \
  scripts/start.sh \
  compose.offline.yaml
```

## 6. 现场一键构建并启动

执行：

```bash
cd /opt/analyzer-coder
bash scripts/start.sh
```

脚本自动依次执行：

1. `npm ci`；
2. `npm run build`；
3. `mvn -pl backend -am clean package -DskipTests`；
4. 启动 PostgreSQL/pgvector；
5. 启动 Nginx；
6. 生成 `.env.components` 和 PostgreSQL 随机密码；
7. 生成 `.env.application`、管理员密码和加密主密钥；
8. 使用宿主机 Java 后台启动后端 JAR；
9. 等待后端健康检查并输出访问地址、PID 和日志路径。

检测到已经导入的离线镜像后，脚本会自动使用 `compose.offline.yaml`，不会访问 Docker Hub。

如果 `frontend/node_modules` 已经存在，并且 `package-lock.json` 没有变化，可以执行：

```bash
bash scripts/start.sh --skip-npm-ci
```

配置好外部 HTTPS 代理后，可以启用 Secure Cookie：

```bash
bash scripts/start.sh --skip-npm-ci --https
```

首次启动会显示：

```text
Initial administrator: admin
Initial password: <随机密码>
```

必须立即保存初始管理员密码，首次登录后需要修改密码。

## 7. 启动验证

检查容器：

```bash
docker compose \
  --env-file .env.components \
  -f compose.offline.yaml \
  ps
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

检查 Nginx 容器到宿主机后端的连通性：

```bash
docker compose \
  --env-file .env.components \
  -f compose.offline.yaml \
  exec -T nginx \
  wget -qO- http://host.docker.internal:8080/actuator/health
```

### 7.1 配置并使用问答模型

使用管理员账号登录后：

1. 进入“系统设置 → 问答模型”。
2. 新增 OpenAI-compatible 模型，填写 Base URL、模型标识和 API Key。
3. 点击“检测”，等待状态变为“可用”。
4. 进入“知识问答”，在页面顶部选择该模型后发送问题。

问答模型不再需要“切换使用”“停用”或 `externalModelEnabled` 开关。每次问答直接使用页面当前选择的模型；检索证据中的密码、Token、API Key 和私钥会先脱敏再发送。

## 8. 日志和运行文件

后端日志：

```bash
tail -f /opt/analyzer-coder/runtime/logs/backend.log
```

组件日志：

```bash
cd /opt/analyzer-coder

docker compose \
  --env-file .env.components \
  -f compose.offline.yaml \
  logs -f
```

后端 PID：

```bash
cat /opt/analyzer-coder/runtime/backend.pid
```

关键文件位置：

| 内容 | 路径 |
|---|---|
| 组件配置 | `/opt/analyzer-coder/.env.components` |
| 应用配置和密钥 | `/opt/analyzer-coder/.env.application` |
| 前端产物 | `/opt/analyzer-coder/frontend/dist` |
| 后端 JAR | `/opt/analyzer-coder/backend/target/` |
| 后端日志 | `/opt/analyzer-coder/runtime/logs/backend.log` |
| 后端 PID | `/opt/analyzer-coder/runtime/backend.pid` |
| 平台数据 | `/opt/analyzer-coder/runtime/data` |
| 仓库目录 | `/opt/analyzer-coder/runtime/repositories` |

`.env.components` 和 `.env.application` 包含密码与密钥，不得提交到 Git 或发送到工单、聊天中。

## 9. 远程访问

### 9.1 SSH 隧道访问（推荐）

Nginx 默认只监听服务器的 `127.0.0.1:8088`。推荐从个人电脑建立 SSH 隧道：

```bash
ssh \
  -L 8088:127.0.0.1:8088 \
  deploy@192.168.1.100
```

然后在个人电脑浏览器访问：

```text
http://127.0.0.1:8088
```

### 9.2 使用公网 IP 直接访问

需要直接通过以下地址访问时：

```text
http://<服务器公网IP>:8088
```

修改组件监听地址：

```bash
cd /opt/analyzer-coder

sed -i \
  's/^APP_HTTP_BIND_ADDRESS=.*/APP_HTTP_BIND_ADDRESS=0.0.0.0/' \
  .env.components
```

确认配置：

```bash
grep '^APP_HTTP_' .env.components
```

应显示：

```text
APP_HTTP_BIND_ADDRESS=0.0.0.0
APP_HTTP_PORT=8088
```

因为公网 IP 直连使用普通 HTTP，应用不能启用 Secure Cookie：

```bash
sed -i \
  's/^APP_SESSION_COOKIE_SECURE=.*/APP_SESSION_COOKIE_SECURE=false/' \
  .env.application
```

重新创建 Nginx 端口映射：

```bash
docker compose \
  --env-file .env.components \
  -f compose.offline.yaml \
  up -d --force-recreate nginx
```

如果修改了 `APP_SESSION_COOKIE_SECURE`，重新启动宿主机后端：

```bash
bash scripts/start.sh --skip-npm-ci
```

检查端口映射：

```bash
docker compose \
  --env-file .env.components \
  -f compose.offline.yaml \
  ps
```

Nginx 应显示类似：

```text
0.0.0.0:8088->8080/tcp
```

开放主机防火墙：

```bash
sudo ufw allow 8088/tcp
sudo ufw status
```

使用云服务器时，还需要在安全组中允许公网 TCP `8088`。安全组和主机防火墙不得向公网开放：

```text
5432  PostgreSQL
8080  宿主机后端
```

Nginx 容器需要通过 Docker 网桥访问宿主机后端 `8080`。如果 UFW 默认拒绝入站连接，先查看组件网络子网：

```bash
docker network inspect \
  analyzer-coder-components_default \
  --format '{{range .IPAM.Config}}{{.Subnet}}{{end}}'
```

假设输出 `172.20.0.0/16`，仅允许该子网访问后端：

```bash
sudo ufw allow \
  from 172.20.0.0/16 \
  to any port 8080 \
  proto tcp
```

从外部电脑验证：

```bash
curl -I http://<服务器公网IP>:8088/
```

浏览器访问：

```text
http://<服务器公网IP>:8088
```

公网 IP 直连采用明文 HTTP，登录密码和会话可能被网络中间方窃听，只适合临时验收或受控网络。正式外部访问应保留 `127.0.0.1:8088`，并在前面配置受信任的 HTTPS 边缘代理。

## 10. 后续 Git 更新

进入项目并检查工作区：

```bash
cd /opt/analyzer-coder
git status
```

如果存在现场修改，应先保存或提交，不能直接覆盖。工作区干净时更新 `v2`：

```bash
git pull --ff-only origin v2
```

重新构建并启动：

```bash
bash scripts/start.sh
```

当前仓库的 Flyway 历史已压缩为单一 V1 基线，只适用于空库安装。携带旧版 V3/V4/V5 历史的数据库不能直接用当前源码启动：开发环境可在确认数据无需保留后重建数据库；生产环境必须先备份，并使用保留旧迁移的发布版本完成受控升级，禁止直接清库。切换新的外部向量模型后，仍需要对仓库执行一次完整索引，才能生成新模型维度的向量。

只有确认 `frontend/package-lock.json` 没有变化时，才建议使用：

```bash
bash scripts/start.sh --skip-npm-ci
```

脚本会验证 `runtime/backend.pid`，只停止由当前源码目录上一次启动的后端进程，然后启动新的 JAR。

日常更新命令为：

```bash
cd /opt/analyzer-coder
git pull --ff-only origin v2
bash scripts/start.sh
```

## 11. 停止系统

停止后端：

```bash
cd /opt/analyzer-coder

kill "$(cat runtime/backend.pid)"
rm -f runtime/backend.pid
```

停止 PostgreSQL 和 Nginx：

```bash
docker compose \
  --env-file .env.components \
  -f compose.offline.yaml \
  down
```

普通 `down` 不会删除 PostgreSQL 数据。禁止随意执行：

```bash
docker compose \
  --env-file .env.components \
  -f compose.offline.yaml \
  down -v
```

`down -v` 会永久删除 PostgreSQL named volume。

## 12. Docker Volume 和目录挂载

当前挂载关系：

| 服务 | 宿主机或 Docker Volume | 容器路径 | 权限 |
|---|---|---|---|
| PostgreSQL | `analyzer-coder-components_postgres-data` | `/var/lib/postgresql/data` | 读写 |
| Nginx 前端文件 | `/opt/analyzer-coder/frontend/dist` | `/usr/share/nginx/html` | 只读 |
| Nginx 配置 | `/opt/analyzer-coder/deploy/nginx-components.conf` | `/etc/nginx/conf.d/default.conf` | 只读 |

查看 PostgreSQL volume：

```bash
docker volume inspect analyzer-coder-components_postgres-data
```

查看 Nginx 实际挂载：

```bash
docker inspect analyzer-coder-nginx
```

前端重新执行 `npm run build` 后，`frontend/dist` 的变化会直接反映到 Nginx 容器中，不需要重新制作镜像。

组件离线包中的 `images.tar` 只包含镜像层，不包含 PostgreSQL 数据、应用密钥、仓库文件和 `runtime/data`。迁移或备份时必须单独处理数据库和运行数据。

## 13. 完全离线环境说明

组件包可以离线提供 PostgreSQL 和 Nginx，但现场执行以下命令仍可能需要访问外部或内部制品仓库：

```text
git clone / git pull
npm ci
mvn clean package
npm install --global CodeGraph
```

如果服务器不能访问互联网，需要提前提供：

- 可访问的内部 Git 仓库；
- npm 镜像或完整 npm 缓存；
- Maven 镜像或完整 Maven 本地仓库；
- Node.js、Java、Maven、Git 和 CodeGraph 的离线安装包。

否则应在联网构建机器生成 `backend` JAR 和 `frontend/dist` 后再上传到服务器。
