# Codebase Knowledge Backend

后端采用 Java 17+、Spring Boot 3.x，按轻量 DDD + 六边形端口适配器组织。

## 架构约定

```text
interfaces      对外入口：REST Controller、请求响应 DTO
application     用例编排：Command、Query、Application Service
domain          领域模型、领域服务、仓储端口、外部能力端口
infrastructure 适配器实现：数据库、CodeGraph SQLite、向量库、LLM
worker          后台任务：索引任务、增量任务、批处理任务
config          Spring 配置
```

依赖方向：

```text
interfaces -> application -> domain
infrastructure -> domain
worker -> application
```

`domain` 不依赖 Spring、不依赖数据库、不依赖模型 SDK。CodeGraph、RAG、LLM、向量库都通过端口进入领域或应用层。

## 为什么不用完整 COLA

COLA 本质上也是 DDD 应用架构实践，但它带有较强的包结构和框架约定。当前项目处在 MVP 阶段，核心风险是检索链路、CodeGraph schema 适配和索引流程，使用轻量 DDD 可以保留边界，同时减少模板负担。

后续如果团队明确使用 COLA，可以将当前结构映射为：

```text
interfaces      -> adapter
application     -> app
domain          -> domain
infrastructure  -> infrastructure
```

## 本地启动

```bash
mvn -pl backend spring-boot:run
```

默认情况下 Flyway 迁移关闭，方便在没有 PostgreSQL 的环境里先启动骨架。接入数据库时设置：

```bash
APP_FLYWAY_ENABLED=true
```
