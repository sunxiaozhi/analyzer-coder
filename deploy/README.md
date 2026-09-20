# 部署模板

唯一部署流程见 [部署与启动操作手册](../docs/15-deployment-runbook.md)。

- backend/：复制到发布包 backend，包含 Spring Boot 外部 YAML 和两平台启停脚本。
- components/：复制到发布包 components，仅包含 PG/pgvector、Nginx 的编排及配置。
- scripts/build-release.sh / .ps1 调用共享的 build-release.mjs 构建 JAR、前端并导出镜像。

这里是模板目录，不是部署实例。不要将实际密码写回模板。组件的前端相对挂载路径按发布包结构设计；应先打包，再从发布目录启动。
