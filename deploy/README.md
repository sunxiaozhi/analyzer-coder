# 发布部署文件

一键启动：Windows 执行 `pwsh -File scripts/start.ps1`，Linux 执行 `bash scripts/start.sh`。首次运行会生成安全配置并启动生产 Compose。

- `../compose.prod.yaml`：PostgreSQL、后端和前端的生产编排。
- `.env.production.example`：生产 Compose 环境模板。
- `nginx-container.conf`：前端容器内部配置。
- `nginx-compose-edge.conf`：Compose 前的宿主机 HTTPS 代理。
- `analyzer-coder.service`：传统 Linux 主机 systemd 单元。
- `analyzer-coder.env.example`：systemd 环境模板。
- `nginx-host.conf`：传统主机 HTTPS 静态站点和 API 代理。

完整安装、升级和验收步骤见 `docs/06-local-run.md`。所有占位密码、域名和证书路径必须在使用前替换。