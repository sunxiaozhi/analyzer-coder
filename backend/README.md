# Codebase Knowledge Backend

Java 17 + Spring Boot 3.5 后端，承载账号、仓库、索引、检索、问答、知识卡片、模型设置和后台任务。业务数据存储在 PostgreSQL/pgvector。

## 当前技术

- Spring MVC、Bean Validation、Actuator
- MyBatis Mapper/XML、PageHelper
- PostgreSQL 17、pgvector 64 维向量
- Flyway `V1__baseline.sql`
- Spring Scheduler + 数据库任务表
- Git CLI、CodeGraph CLI
- JDK `HttpClient` + OpenAI-compatible chat/embedding

## 一键启动

Windows：`pwsh -File scripts/start.ps1`；Linux：`bash scripts/start.sh`。脚本自动生成安全配置、构建镜像并等待健康检查。

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

## 验证

```bash
mvn -pl backend -am test
```

当前基线执行 44 个测试。

## Linux 发布

- 生产 Compose：`compose.prod.yaml`
- 后端容器：`backend/Dockerfile`
- systemd/Nginx/环境模板：`deploy/`
- 完整安装与升级步骤：`docs/06-local-run.md`

## 安全约束

- 管理员重置密码为只展示一次的随机临时密码，24 小时过期并强制改密。
- 生产环境必须使用 HTTPS、`APP_SESSION_COOKIE_SECURE=true` 和受信反向代理。
- PostgreSQL、后端 8080 和 Actuator 不得直接暴露公网。
- `APP_LLM_MASTER_KEY` 必须稳定保管，不能在已有模型密钥后随意轮换。