# 部署与启动操作手册

项目只支持完整单镜像部署：前端、后端、PostgreSQL/pgvector、Nginx、Git、Node.js、CodeGraph 原生运行时和 MCP 都在同一镜像中。安装和升级都交付一张完整镜像，宿主机 data 目录保存业务数据，config 目录和 .env 保存外挂配置。

完整命令、配置项、备份、升级、旧命名卷迁移和故障排查统一维护在 [单镜像安装、启动与升级](../deploy/all-in-one/README.md)，这份文档也作为每个离线包的 README 发出。

## 安装与维护入口

解压完整发布包后，在固定部署目录执行：

```bash
bash analyzer.sh start
```

```powershell
pwsh -File analyzer.ps1 start
```

首次自动导入镜像、生成随机密码和密钥、创建 data/config 并等待健康。默认访问 http://服务器IP:18081，管理员账号和初始密码从 .env 读取。

修改配置后使用 restart；备份使用 backup。在原安装目录使用 upgrade 新包目录完成单镜像升级，保留 .env、数据及已有外挂配置。没有宿主机 JAR 启动、独立组件镜像或无镜像升级包。

## 发布构建入口

- Windows：`pwsh -File scripts/build-docker-image.ps1 -Version VERSION`
- Linux：`bash scripts/build-docker-image.sh --version VERSION`
- 输出：完整离线目录、同名 tar.gz、SHA256SUMS 与压缩包校验文件。
- 运行机：Docker、支持 --wait 的 Compose；Windows 需要 PowerShell 7，Linux 使用 Bash。
- 源码开发与测试仍使用各模块说明，生产部署统一使用上述入口。
