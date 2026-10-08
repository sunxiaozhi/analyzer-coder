# Codebase Knowledge Backend

Java 17 + Spring Boot 3.5 后端，承载账号、仓库、索引、检索、问答、知识卡片、模型设置和后台任务。业务数据存储在 PostgreSQL/pgvector。

## 当前技术

- Spring MVC、Bean Validation、Actuator
- MyBatis Mapper/XML、PageHelper
- PostgreSQL 17、pgvector（内置 64 维字符哈希，外部模型维度可配置）
- Flyway（当前源码保留合并后的 `V1` 基线；旧库升级需单独核验，见 `docs/11-data-model.md`）
- Spring Scheduler + 数据库任务表
- Git CLI（兼容基线为 1.8.3.1；生产环境仍建议使用受安全维护的新版本）、CodeGraph CLI
- JDK `HttpClient` + OpenAI-compatible chat/embedding

## 发布运行

后端与前端、PostgreSQL/pgvector、Nginx、Git、CodeGraph 和 MCP 一起封装在唯一完整 Docker 镜像中。使用源码根目录 scripts/build-docker-image.sh / .ps1 打包，服务器解压后运行 analyzer.sh / analyzer.ps1 start。业务数据外挂至宿主 data 目录，.env 和 config 目录保存可修改的启动配置；升级只更换完整镜像。

配置、安装和维护统一见 [单镜像部署手册](../deploy/all-in-one/README.md)。Spring Boot 默认配置位于 src/main/resources/application.yml，外挂 config/application.yml 作为附加配置加载；数据库与后端内部地址/端口保持固定。

## 源码开发说明

源码开发与测试需要 Java 17、Maven，以及独立测试数据库；生产启动入口统一使用完整镜像。开发环境诊断入口为 scripts/check-runtime.mjs，验证脚本为 scripts/verify-core.mjs，不作为部署机必需工具。

Flyway 的 V1 是空库初始化基线，既有数据库的迁移须遵守 docs/11-data-model.md 的版本约束，不能清空历史校验表绕过迁移。

最小问答闭环不要求配置外部聊天模型。POST /api/repositories/{id}/ask 的 modelConfigId 可省略，此时返回带源码引用的本地证据回答；传入模型 ID 时仍校验模型有效性。搜索只读取已有索引，不会同步补建全仓向量，切换向量模型后执行项目准备或重试向量阶段。
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


## 安全约束

- 管理员重置密码为只展示一次的随机临时密码，24 小时过期并强制改密。
- 生产环境必须使用 HTTPS、`APP_SESSION_COOKIE_SECURE=true` 和受信反向代理。
- PostgreSQL、容器内部后端 8081 和 Actuator 不得直接暴露公网。
- `APP_LLM_MASTER_KEY` 必须稳定保管，不能在已有模型密钥后随意轮换。
