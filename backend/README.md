# Codebase Knowledge Backend

Java 17 + Spring Boot 3.5 后端，承载账号、仓库、索引、检索、问答、知识卡片、模型设置和后台任务。业务数据存储在 PostgreSQL/pgvector。

## 当前技术

- Spring MVC、Bean Validation、Actuator
- MyBatis Mapper/XML、PageHelper
- PostgreSQL 17、pgvector（内置 64 维字符哈希，外部模型维度可配置）
- Flyway（当前源码保留合并后的 `V1` 基线；旧库升级需单独核验，见 `docs/12-data-flow-audit.md`）
- Spring Scheduler + 数据库任务表
- Git CLI、CodeGraph CLI
- JDK `HttpClient` + OpenAI-compatible chat/embedding

## 一键启动

Linux 在源码根目录执行 `bash scripts/start.sh`。脚本依次构建前端和后端、启动 PostgreSQL/pgvector 与 Nginx、生成安全配置、启动宿主机后端并等待健康检查。完整部署步骤见 `docs/08-linux-git-deployment.md`。

## 本地启动

敏感配置没有代码默认值。先复制并修改数据库环境文件：

```bash
cp .env.example .env
docker compose up -d postgres
```

至少设置以下后端变量：

```bash
export APP_DATASOURCE_PASSWORD='same-as-postgres-password'
export APP_INITIAL_ADMIN_USERNAME='admin'
export APP_INITIAL_ADMIN_PASSWORD='replace-with-a-strong-password'
export APP_REPOSITORY_ALLOWED_ROOTS='/srv/analyzer-repositories'
export APP_MANAGED_DATA_ROOT='/var/lib/analyzer-coder'
export APP_LLM_MASTER_KEY='replace-with-at-least-24-random-characters'
mvn -pl backend spring-boot:run
```

健康检查：`GET /actuator/health`。

运行诊断：在加载上述环境变量的终端执行 `node scripts/check-runtime.mjs`。

最小问答闭环不要求配置外部聊天模型。`POST /api/repositories/{id}/ask` 的 `modelConfigId` 可省略或传 `null`，此时返回带源码引用的本地证据回答，`fallbackReason=LOCAL_EVIDENCE_MODE`。传入具体模型 ID 时仍执行模型有效性校验。搜索请求只读已有索引，不会同步补建全仓库向量；切换向量模型后请执行项目准备或显式重试向量阶段。

## 验证

```bash
mvn -pl backend -am test
```

数据库集成测试需要可用的 PostgreSQL 17 + pgvector，并使用独立测试数据：

```bash
export APP_RUN_POSTGRES_IT=true
mvn -pl backend -Dtest='*IT' test
```

Linux CI 默认启动临时 pgvector 服务并执行这组集成测试；普通单元测试仍可在没有数据库时运行。前端执行 `npm test` 验证关键路由，`npm run build` 完成类型检查和生产构建。

## Linux 发布

- 生产 Compose：`compose.prod.yaml`
- 后端容器：`backend/Dockerfile`
- systemd/Nginx/环境模板：`deploy/`
- 完整 Git 部署与升级步骤：`docs/08-linux-git-deployment.md`

## 安全约束

- 管理员重置密码为只展示一次的随机临时密码，24 小时过期并强制改密。
- 生产环境必须使用 HTTPS、`APP_SESSION_COOKIE_SECURE=true` 和受信反向代理。
- PostgreSQL、后端 8080 和 Actuator 不得直接暴露公网。
- `APP_LLM_MASTER_KEY` 必须稳定保管，不能在已有模型密钥后随意轮换。
