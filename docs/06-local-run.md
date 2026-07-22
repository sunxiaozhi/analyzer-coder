# 本地运行

## 必需组件

- Java 17
- Maven 3.9+
- Node.js 20+ 与 npm
- Git 2.30+
- Docker Desktop（运行 PostgreSQL 17 + pgvector）
- CodeGraph CLI 1.4+（只在使用真实代码图谱时需要）

安装并检查 CodeGraph：

~~~powershell
npm install -g @colbymchenry/codegraph
codegraph --version
~~~

## 1. 启动 PostgreSQL/pgvector

在项目根目录执行：

~~~powershell
docker compose up -d postgres
docker compose ps
~~~

本地默认数据库、用户名和密码均为 `codebase_kb`，端口为 `5432`。Flyway 默认开启，后端首次启动会自动创建 `vector` 扩展并执行 V1 至最新版本的迁移，不需要手工建表。

## 2. 首次配置

下面以 PowerShell 为例。允许根目录决定“本地 Git”可接入的范围；导入目录、冻结快照和 CodeGraph 产物必须放在源码工作区之外。

~~~powershell
$env:APP_INITIAL_ADMIN_USERNAME = "admin"
$env:APP_INITIAL_ADMIN_PASSWORD = "请替换为至少 12 位且包含大小写字母、数字和符号的临时密码"
$env:APP_REPOSITORY_ALLOWED_ROOTS = "C:\workspace"
$env:APP_MANAGED_DATA_ROOT = "C:\analyzer-coder-data"
$env:APP_CODEGRAPH_EXECUTABLE = (Get-Command codegraph).Source
~~~

初始管理员只会在账号表为空时创建。首次登录必须修改临时密码；以后重启无需继续保留初始密码环境变量。
升级已有仓库数据时，首次运行 V15 还必须设置 `APP_REPOSITORY_MIGRATION_OWNER` 为一个已启用的超级管理员用户名；全新数据库不需要该变量。


可选数据库配置：

~~~powershell
$env:APP_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/codebase_kb"
$env:APP_DATASOURCE_USERNAME = "codebase_kb"
$env:APP_DATASOURCE_PASSWORD = "codebase_kb"
$env:APP_FLYWAY_ENABLED = "true"
~~~

## 3. 启动后端

从项目根目录执行：

~~~powershell
mvn -pl backend spring-boot:run
~~~

健康检查：

~~~powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
~~~

返回 `status: UP` 表示数据库连接和应用启动成功。

## 4. 启动前端

另开一个 PowerShell：

~~~powershell
cd frontend
npm ci
npm run dev
~~~

访问 `http://127.0.0.1:5173`。Vite 会把 `/api` 和 `/actuator` 代理到 `127.0.0.1:8080`。

## 5. 验证构建

~~~powershell
mvn -pl backend test
cd frontend
npm run build
~~~

## 6. 数据目录与停止

- PostgreSQL 数据：Docker 卷 `postgres-data`
- 统一受管数据：`APP_MANAGED_DATA_ROOT`
- 仓库工作副本、冻结快照和 CodeGraph 产物：`APP_MANAGED_DATA_ROOT/repositories/{repositoryId}`
- 远程 Git/ZIP 临时导入：`APP_MANAGED_DATA_ROOT/staging/imports`

停止应用后可执行 `docker compose stop postgres`。只有明确不再需要数据库数据时才执行 `docker compose down -v`。

## 常见问题

`Repository path is outside configured allowed roots` 表示待接入本地仓库的规范化绝对路径不在 `APP_REPOSITORY_ALLOWED_ROOTS` 中。把仓库父目录加入白名单并重启后端；不要把磁盘根目录配置为白名单。

CodeGraph 提示未找到 CLI 时，先运行 `codegraph --version`，Windows 上也可把 `APP_CODEGRAPH_EXECUTABLE` 设置为 `codegraph.cmd` 的绝对路径。图谱构建使用独立分析副本，不会在冻结源码快照内写入 `.codegraph`。

连续三次登录失败会要求完成算术验证码；验证码和失败计数保存在 PostgreSQL 中，重启后不会绕过限制。
