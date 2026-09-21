# 部署与启动操作手册

本项目只维护一种部署方式：PostgreSQL/pgvector 和 Nginx 使用 Docker，后端在宿主机运行 JAR，前端 dist 只读挂载给 Nginx。先在构建机打包，再把整个发布目录或压缩包上传服务器。服务器不需要下载项目源码。

## 1. 环境准备

| 环境 | 必需工具 |
| --- | --- |
| 构建机 | Node.js 20+、npm、JDK 17、Maven、tar；完整镜像包还需要运行中的 Docker 和 Compose v2 |
| Linux 部署机 | Docker Engine + Docker Compose V1.29.2 或 V2、Java 17、Git、Bash 4.4+、curl、常用系统工具 |
| Windows 部署机 | Windows 10/11、Docker Desktop（Linux 容器模式）、Java 17、Git、PowerShell 7 |

- 构建机和部署机可以不同。默认组件镜像为 linux/amd64；ARM64 服务器使用 linux/arm64，不能混用架构。
- Windows Server 不在 Docker Desktop 的支持范围；应先确定 Linux 虚拟机等容器环境，再按实际网络配置部署。
- CodeGraph CLI 是可选增强；缺失时图谱任务会失败，代码与知识检索仍可使用。需要图谱时单独安装并设置 app.codegraph.executable。
- 外部模型可选。MCP stdio 适配器是客户端工具，不是基础服务；它需要在使用它的客户端单独安装 Node.js。
- 服务器若不能联网，应提前安装上述运行环境。components.tar 只携带 PG/pgvector 和 Nginx 镜像。

## 2. 在构建机打包

以下命令在源码根目录执行，Docker 应已启动。

Windows：

```powershell
pwsh -File scripts/build-release.ps1 -Version 1.0.0
```

Linux：

```bash
bash scripts/build-release.sh --version 1.0.0
```

脚本执行 npm ci、npm run build、mvn -pl backend -am clean package -DskipTests；随后拉取组件镜像、检查镜像架构、按发布版本标记并 docker save，最后生成目录、校验清单和 tar.gz。

打包不执行测试，发布前应完成项目验证。参数如下：

| Bash 参数 | PowerShell 参数 | 作用 |
| --- | --- | --- |
| --version 1.0.0 | -Version 1.0.0 | 发布版本，默认时间戳 |
| --output release | -OutputDirectory release | 输出目录，相对路径以源码根目录为基准 |
| --platform linux/arm64 | -Platform linux/arm64 | 镜像架构，默认 linux/amd64 |
| --without-images | -WithoutImages | 省略镜像，构建机不需要 Docker |
| --skip-build | -SkipBuild | 复用已有 dist 和唯一应用 JAR，不执行 npm/Maven；调用者负责产物与源码一致 |

首次交付使用完整包。日常只升级应用时：

```bash
bash scripts/build-release.sh --version 1.0.1 --without-images
```

```powershell
pwsh -File scripts/build-release.ps1 -Version 1.0.1 -WithoutImages
```

输出为 release/analyzer-coder-1.0.0/ 和 release/analyzer-coder-1.0.0.tar.gz。同名输出已存在时拒绝覆盖。失败目录中的 .incomplete 表示不能交付，修复问题后使用新的版本或输出目录重新打包。

## 3. 发布目录

```text
analyzer-coder-1.0.0/
├─ README.md
├─ MANIFEST.txt                       # JSON 格式，版本、源码状态、镜像 ID/摘要及架构
├─ SHA256SUMS                         # 发布文件校验清单，不含清单自身
├─ components/
│  ├─ compose.yaml
│  ├─ components.env.example          # 首次部署复制为 .env
│  ├─ nginx/nginx.conf
│  └─ images/components.tar           # --without-images 时省略
├─ backend/
│  ├─ app.jar
│  ├─ config/application.yml          # 外部 Spring Boot 配置，需填写
│  ├─ backend.sh
│  └─ backend.ps1
└─ frontend/dist/
   ├─ index.html
   └─ assets/
```

backend/logs、backend/run、backend/data、backend/repositories 由后端脚本在运行时创建，不复制开发机数据。PostgreSQL 数据通过 bind 写入 `.env` 中 `POSTGRES_DATA_SOURCE` 指定的宿主机目录。Nginx 的 `../frontend/dist` 和 `./nginx/nginx.conf` 都相对 `components/compose.yaml` 所在目录解析；若发布根目录为 `/opt/sunhuanshi/analyzer-coder`，实际读取 `/opt/sunhuanshi/analyzer-coder/frontend/dist` 和 `/opt/sunhuanshi/analyzer-coder/components/nginx/nginx.conf`。建议使用固定部署目录。

## 4. 首次部署

### 4.1 解压并校验

上传 tar.gz 后解压，进入解压后的发布根目录。后续命令均以该目录为起点；后端脚本可从任意目录调用。

```bash
tar -xzf analyzer-coder-1.0.0.tar.gz
cd analyzer-coder-1.0.0
sha256sum -c SHA256SUMS
```

Windows 使用 tar 解压，然后在修改任何配置之前验证：

```powershell
Get-Content SHA256SUMS | ForEach-Object {
    if ($_ -notmatch '^([0-9a-f]{64})  (.+)$') { throw '校验清单格式错误' }
    $expected = $Matches[1]
    $file = $Matches[2]
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash.ToLowerInvariant()
    if ($actual -ne $expected) { throw "校验失败：$file" }
}
```

SHA256SUMS 用于确认传输完整性；填写配置后，配置文件的校验值变化是正常的。

### 4.2 填写配置

Linux：cp components/components.env.example components/.env。
Windows：Copy-Item components/components.env.example components/.env。

修改两处：

1. components/.env：填写数据库密码、PostgreSQL 宿主机数据目录和入口端口；镜像名称保留包中生成的值。
2. backend/config/application.yml：填写数据库连接、管理员初始密码、两个独立加密主密钥；替换全部 replace-with 值。

- 数据库名、账号、密码、宿主映射端口在两处必须一致。后端数据库地址使用 127.0.0.1，不是 Compose 服务名 postgres。
- PostgreSQL 固定使用 bind：`POSTGRES_DATA_SOURCE` 是部署服务器上的绝对目录，即挂载 `source`；Linux 默认 `/opt/sunhuanshi/analyzer-coder/postgres`，Windows 可改为 `D:/apps/analyzer-coder/postgres`。启动组件前必须先创建该目录。
- 容器内 `target` 固定为 `/var/lib/postgresql/data`，已写在 `compose.yaml` 中，不要在 `.env` 配置或修改。PostgreSQL 对 target 的写入实际落到 source。
- 已有数据库更换 `POSTGRES_DATA_SOURCE` 不会自动迁移数据；必须先停止写入、备份并按 PostgreSQL 流程迁移和验证。
- YAML 中按 spring.datasource、server、app 等标准属性配置；无需 APP_ 环境变量或 dotenv 加载器。
- 两个主密钥分别保存模型 Key 和 Git 凭据的加密能力，使用至少 32 字符的独立随机值，升级时必须保留。
- 内网 GitLab 需在 `app.repository.trusted-private-hosts` 填写精确主机名，多个主机用逗号或分号分隔；不要配置通配符或 `localhost`。公网仓库无需加入白名单。
- 内部 CA 暂时无法安装时，可在 `app.repository.insecure-tls-hosts` 填写同一个精确内网主机；该主机必须已位于 `trusted-private-hosts`，系统只为命中主机的单次 Git 命令关闭证书校验。
- 后端 Git CLI 兼容基线为 1.8.3.1；代码已避开该版本不支持的 `git -C`、`--porcelain=v1`、`--end-of-options`、`--no-write-fetch-head` 和空 `credential.helper` 参数。生产环境仍建议升级到受安全维护的新版本。
- 默认数据路径相对 backend；自定义仓库白名单目录必须预先存在且可读。Windows 的 YAML 路径建议使用 D:/data/repositories 形式。
- HTTP 入口默认端口 18081，后端固定使用 18082，PG 宿主端口默认 18080。若再次更改后端端口，必须同步修改 YAML 的 `server.port`、Nginx 的 `proxy_pass` 和后端脚本的 `--port` / `-Port` 默认值。
- 初始管理员密码必须同时包含大写字母、小写字母、数字和特殊字符。管理员仅在数据库没有账号时创建，第一次登录要求改密；修改配置里的初始密码不会重置已有账号。
- Linux 建议 chmod 600 components/.env backend/config/application.yml；Windows 将这些文件访问权限限制为部署账号。

### 4.3 加载镜像并启动组件

先创建 `.env` 中作为 source 的宿主机目录。Linux 默认配置：

```bash
mkdir -p /opt/sunhuanshi/analyzer-coder/postgres
```

Windows 若配置为 `D:/apps/analyzer-coder/postgres`：

```powershell
New-Item -ItemType Directory -Force D:/apps/analyzer-coder/postgres
```

先执行 `docker-compose version` 或 `docker compose version` 确认安装方式。服务器使用 Compose V1.29.2 时执行：

```text
docker load -i components/images/components.tar
docker-compose --env-file components/.env -f components/compose.yaml config -q
docker-compose --env-file components/.env -f components/compose.yaml up -d
docker-compose --env-file components/.env -f components/compose.yaml ps
```

Compose V2 执行：

```text
docker load -i components/images/components.tar
docker compose --env-file components/.env -f components/compose.yaml config --quiet
docker compose --env-file components/.env -f components/compose.yaml up -d --pull never --wait
```

V1 不支持 `--pull never` 和 `--wait`；镜像已由 `docker load` 导入时，`up -d` 直接使用本地镜像。V2 的 `--pull never` 会在镜像缺失时直接失败。在线首次部署可以使用不含镜像的包并允许 Compose 拉取缺少的镜像。

Nginx 启动后、JAR 就绪前，API 暂时返回 502 是预期现象。组件健康不代表整个应用已就绪。

### 4.4 启动后端

Linux：

```bash
bash backend/backend.sh start
```

Windows：

```powershell
pwsh -File backend/backend.ps1 start
```

脚本固定以 backend 为工作目录，通过 java -jar <绝对 JAR 路径> 启动。Spring Boot 按默认规则加载 ./config/application.yml，覆盖 JAR 内同名属性；不设置 spring.config.location 或 additional-location。通常无需设置环境变量，已有 SPRING_*、SERVER_* 等高优先级环境变量仍可能覆盖 YAML，部署时应清理冲突配置。

手动前台运行也可以：

```text
cd backend
java -jar app.jar
```

手动运行前需自行创建 config 中指定的仓库白名单目录（默认 repositories）；手动进程不受脚本 PID 文件管理，不要与脚本混用。

后端默认 Xms256m / Xmx768m，等待 /actuator/health 返回 UP，超时或进程退出则报错。自定义内存：Linux 使用 APP_JAVA_XMS=512m APP_JAVA_XMX=1g bash backend/backend.sh start；Windows 使用 -JavaXms 512m -JavaXmx 1g。

启动脚本不会启动 Docker、编译项目或要求 CodeGraph。Flyway 在后端启动时初始化表和 vector 扩展。

### 4.5 验收

按层验证，便于区分静态文件、Nginx、后端和数据库问题：

```bash
# Nginx 自身，不验证前端文件或后端
curl -fsS http://127.0.0.1:18081/component-health
# 前端静态入口及 SPA 回退
curl -I http://127.0.0.1:18081/index.html
curl -I http://127.0.0.1:18081/
# 后端直接健康和完整代理链路
curl -fsS http://127.0.0.1:18082/actuator/health
curl -fsS http://127.0.0.1:18081/api/health
```

随后执行：

- `backend.sh status` / `backend.ps1 status`：确认后端存活且数据库健康。
- 从浏览器访问 `http://服务器地址:18081/`，确认页面和刷新后的前端路由正常。
- 登录并修改初始密码，导入一个小 Git/ZIP 仓库，构建索引并执行检索。
- 重启后确认账号、项目和索引仍在。

## 5. 日常操作

```text
bash backend/backend.sh status
bash backend/backend.sh stop
bash backend/backend.sh restart
```

Windows 将 bash backend/backend.sh 换成 pwsh -File backend/backend.ps1。脚本只操作 PID 与启动身份均匹配当前 app.jar 的进程，遇到其他进程占用端口或 PID 被复用会拒绝操作。Linux stop 发送 SIGTERM，30 秒内未退出则保留 PID 报错；Windows stop 使用 Stop-Process，应在任务空闲时执行，不能等同于 Linux 的优雅停机。

组件日志、停止（以下为 Compose V1；V2 将 `docker-compose` 换成 `docker compose`）：

```text
docker-compose --env-file components/.env -f components/compose.yaml logs --tail 100
docker-compose --env-file components/.env -f components/compose.yaml stop
```

backend/logs/backend.log 按 20MB 滚动，保留 14 天且总量限制为 1GB。console.log（Windows 另有 stderr.log）用于启动排错；每次启动保留上一份 previous.log，当前控制台日志不按大小滚动，长期运行需纳入主机日志管理。

首期提供后台启停，不自动注册系统服务。主机重启后需重新启动 JAR；组件 restart: unless-stopped 的恢复依赖 Docker 已启动，主动 stop 的组件需手动启动。开机自启可后续按主机环境接入 systemd/Windows 服务。

## 6. 网络与 HTTPS

- Nginx 通过 host.docker.internal 访问后端；Compose 的 host-gateway 映射兼容 Linux Engine 和 Windows Desktop。
- 后端监听 0.0.0.0 才能被容器网关访问；防火墙仅允许 Docker 来源访问后端端口，不直接向公网开放 18082。PG 端口仅绑定 127.0.0.1。
- Nginx 对外默认 0.0.0.0:18081。需要仅本机访问时，将 components/.env 中 APP_HTTP_BIND_ADDRESS 改为 127.0.0.1。
- /actuator 不经 Nginx 暴露；本机健康检查使用 /actuator/health，前端代理检查使用 /api/health。
- 默认模板提供 HTTP。公网发布时在同一 Nginx 配置中增加 TLS 监听、证书路径，并在 Compose 增加 443 映射及证书只读挂载；backend/config/application.yml 中 app.security.cookie-secure 设为 true。
- 模板覆盖 X-Forwarded-For/Proto，使用当前连接信息。若前面已有受信任的 HTTPS 网关，应按实际受信链配置转发头，不直接信任公网客户端自带的头。
- ZIP 上传上限由 Nginx 的 client_max_body_size 60m 约束，API 读取超时 300 秒；流式响应关闭代理缓冲。

## 7. 升级与备份

应用升级：在新目录解压升级包并校验，停止旧后端，将新的 app.jar 和完整 frontend/dist 替换到原来的固定部署目录，再启动后端。前端目录整体替换，避免混用新旧资源；后端配置、日志、data、repositories 和组件 .env 保留，其他配置变更人工对照合并。不要直接用新发布目录覆盖所有运行文件。

数据库固定使用 bind。升级时必须保留 `.env` 中原 `POSTGRES_DATA_SOURCE` 绝对路径和该目录下的全部数据，不要用新发布目录覆盖或删除它。一个主机部署多个实例时，每个实例必须使用不同的数据目录和端口。

- 镜像 TAR 是程序镜像，不是数据库备份。升级前联合备份数据库、backend/data、需要保留的仓库文件和两个配置文件。
- 可以用容器内 pg_dump 生成备份，完成后 docker cp 到主机；恢复需按 PostgreSQL 流程演练。不要直接复制正在写入的 PG 卷目录。
- 改 components/.env 中的 POSTGRES_PASSWORD 不会修改已有数据库卷内的用户密码；改密码需先在数据库执行相应操作，再同步后端配置。
- 不执行 `docker-compose down -v`、`docker compose down -v` 或 `docker volume rm`，除非明确要删除数据库。
- 数据库升级由 Flyway 执行。V9 遇到历史跨仓项目或非空跨仓知识范围会主动阻止迁移，必须先导出/迁移旧数据；不要删除校验记录绕过。
- 数据库已迁移后，不保证仅换回旧 JAR 可以回滚；需要对应版本的数据库和文件备份。
- 相对路径使首次部署可选择目录，但数据库中可能保存绝对内容版本路径。已有数据的实例不要直接搬迁部署目录，跨机器/跨系统迁移需要单独核验路径。

## 8. 常见故障

### 8.1 `docker compose` 提示 `unknown flag: --env-file`

这表示 Docker CLI 没有 Compose V2 插件。先检查：

```bash
docker compose version
docker-compose version
```

若服务器只有 `docker-compose 1.29.2`，统一使用带连字符的 V1 命令：

```bash
docker-compose --env-file components/.env -f components/compose.yaml config -q
docker-compose --env-file components/.env -f components/compose.yaml up -d
docker-compose --env-file components/.env -f components/compose.yaml ps
```

V1 不支持 `--pull never` 和 `--wait`。当前 `compose.yaml` 同时通过 V1.29.2 和 V2 的 `config` 验证。

### 8.2 PostgreSQL 路径被当成卷名

若出现 `includes invalid characters for a local volume name`，说明服务器仍在使用旧 Compose 配置，或旧 `.env` 把绝对路径与 volume 模式混用。当前方案只支持 bind：

```env
POSTGRES_DATA_SOURCE=/opt/sunhuanshi/analyzer-coder/postgres
```

`source` 是宿主机目录；`target` 固定为容器内 `/var/lib/postgresql/data`。更新 `components/compose.yaml` 后清理旧变量并创建目录：

```bash
sed -i '/^POSTGRES_STORAGE_TYPE=/d' components/.env
sed -i '/^POSTGRES_VOLUME=/d' components/.env
sed -i 's|^POSTGRES_DATA_SOURCE=.*|POSTGRES_DATA_SOURCE=/opt/sunhuanshi/analyzer-coder/postgres|' components/.env
mkdir -p /opt/sunhuanshi/analyzer-coder/postgres
```

确认 V1 渲染为 bind：

```bash
docker-compose --env-file components/.env -f components/compose.yaml config \
  | grep -A3 -B1 '/opt/sunhuanshi/analyzer-coder/postgres'
```

应包含：

```yaml
source: /opt/sunhuanshi/analyzer-coder/postgres
target: /var/lib/postgresql/data
type: bind
```

### 8.3 首页返回 Nginx 500

日志出现 `rewrite or internal redirection cycle while internally redirecting to "/index.html"`，表示 Nginx 无法通过当前配置正常提供 SPA 入口。新版模板为 `/index.html` 提供精确 location；文件缺失或不可读时返回 404，不再递归产生 500。

先确认实际挂载：

```bash
docker inspect \
  "$(docker-compose --env-file components/.env -f components/compose.yaml ps -q nginx)" \
  --format '{{range .Mounts}}{{println .Source "->" .Destination}}{{end}}'
```

发布根目录为 `/opt/sunhuanshi/analyzer-coder` 时应包含：

```text
/opt/sunhuanshi/analyzer-coder/frontend/dist -> /usr/share/nginx/html
/opt/sunhuanshi/analyzer-coder/components/nginx/nginx.conf -> /etc/nginx/conf.d/default.conf
```

以 Nginx 运行用户检查文件，而不是只在宿主机确认存在：

```bash
docker-compose --env-file components/.env -f components/compose.yaml \
  exec -T --user nginx nginx sh -c \
  'ls -l /usr/share/nginx/html/index.html; test -r /usr/share/nginx/html/index.html && echo READ_OK || echo READ_FAIL'
```

`READ_FAIL` 时修正静态文件权限：

```bash
find frontend/dist -type d -exec chmod 755 {} \;
find frontend/dist -type f -exec chmod 644 {} \;
chmod 644 components/nginx/nginx.conf
```

若 `getenforce` 返回 `Enforcing`，还需要为 bind 文件设置容器可读标签：

```bash
chcon -Rt container_file_t /opt/sunhuanshi/analyzer-coder/frontend/dist
chcon -t container_file_t /opt/sunhuanshi/analyzer-coder/components/nginx/nginx.conf
```

确认容器加载了新版精确 location：

```bash
docker-compose --env-file components/.env -f components/compose.yaml \
  exec -T nginx nginx -T | grep -A8 'location = /index.html'
```

修改挂载或 Nginx 配置后必须重新创建容器：

```bash
docker-compose --env-file components/.env -f components/compose.yaml \
  up -d --force-recreate nginx
```

最后分别检查 `/index.html` 和 `/`：

```bash
curl -I http://127.0.0.1:18081/index.html
curl -I http://127.0.0.1:18081/
```

### 8.4 JS/CSS 提示 MIME type 为 `text/html`

浏览器出现 `Expected a JavaScript-or-Wasm module script but the server responded with a MIME type of text/html`，或样式表因 `text/html` 被拒绝，表示 `index.html` 引用的哈希资源在 `frontend/dist/assets` 中不存在。Nginx 将缺失资源回退到 `index.html` 后，浏览器收到 HTML 而不是 JS/CSS。新版模板对 `/assets/` 使用 `try_files $uri =404`，缺失资源直接返回 404，便于定位。

先列出入口引用并逐个检查：

```bash
grep -oE 'assets/[^" ]+\.(js|css)' frontend/dist/index.html
for file in $(grep -oE 'assets/[^" ]+\.(js|css)' frontend/dist/index.html); do
  test -f "frontend/dist/$file" || echo "MISSING frontend/dist/$file"
done
```

出现 `MISSING` 时，说明上传不完整或把不同构建的 `index.html` 与 `assets` 混在了一起。必须停止 Nginx，将同一发布包中的完整 `frontend/dist` 整体替换，不能只覆盖 `index.html` 或增量合并 assets：

```bash
docker-compose --env-file components/.env -f components/compose.yaml stop nginx
mv frontend/dist frontend/dist.previous
# 将同一发布包中的完整 frontend/dist 上传或复制到这里
find frontend/dist -type d -exec chmod 755 {} \;
find frontend/dist -type f -exec chmod 644 {} \;
docker-compose --env-file components/.env -f components/compose.yaml up -d --force-recreate nginx
```

验证资源响应头；实际文件名以 `index.html` 中的引用为准：

```bash
asset=$(grep -oE 'assets/[^" ]+\.js' frontend/dist/index.html | head -n 1)
curl -I "http://127.0.0.1:18081/$asset"
```

正常应返回 200，JavaScript 的 `Content-Type` 应为 `application/javascript` 或兼容 JavaScript 类型，不能是 `text/html`。

### 8.5 页面正常但 API 失败

- `/component-health` 为 200、`/` 为 200、`/api/health` 为 502：后端未启动、未监听 `0.0.0.0:18082`，或 Docker 网关/防火墙无法访问后端。
- `/api/...` 为 500：请求已经到达后端，查看 `backend/logs/backend.log`。
- Nginx 启动后、JAR 就绪前，API 暂时返回 502 属于正常启动窗口。

```bash
bash backend/backend.sh status
curl -i http://127.0.0.1:18082/actuator/health
tail -n 100 backend/logs/backend.log
docker-compose --env-file components/.env -f components/compose.yaml logs --tail 100 nginx
```

### 8.6 其他故障

| 现象 | 排查 |
| --- | --- |
| 打包提示 Docker 不可用 | 启动 Docker；只更新应用则使用 `--without-images` / `-WithoutImages` |
| 存在 `.incomplete` | 上次打包未完成，不能交付；检查失败原因，用新输出重试 |
| 缺少镜像 | 核对 MANIFEST、`.env` 镜像名和架构；先执行 `docker load` |
| 后端提示 `replace-with` | 填写外部 YAML 中全部实际配置值 |
| 数据库认证失败 | `.env` 与后端 YAML 的账号密码需一致；修改 `.env` 不会修改已有数据库用户密码 |
| Flyway 失败 | 查看 `backend.log`；检查数据库权限、版本和历史迁移数据 |
| 登录 Cookie 无效 | HTTP 模式 `cookie-secure` 应为 false；HTTPS 模式应为 true |
| PID 身份不匹配 | 核实实际进程，不能按旧 PID 强制结束其他进程 |
| Linux `control.lock` 残留 | 确认没有其他控制命令运行后移除该空目录，再重试 |

源码仓库的 `scripts/check-runtime.mjs` 面向开发环境，需要 Node/Maven 和终端环境变量，不读取发布包 YAML；部署诊断使用后端 status、Compose 状态、分层 HTTP 检查和组件日志。
## 9. 最新分支工作区说明

升级到固定分支工作区实现后，每个分支第一次同步会完整建立
`<managed-data-root>/repositories/<repoId>/branches/<branchId>/content`；后续提交只更新 Git 变化文件，
并在同一路径复用 `.codegraph` 执行增量索引。旧式 `branch-<contentVersion>` 目录不会自动迁移，确认新工作区稳定后可按保留策略清理。
