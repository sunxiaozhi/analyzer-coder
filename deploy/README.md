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

## 组件离线安装包

在联网的构建机器上执行：

```bash
bash scripts/build-offline-package.sh --version 1.0.0
```

Windows PowerShell 7 也可以执行：

```powershell
pwsh -File scripts/build-offline-package.ps1 -Version 1.0.0
```

生成的 `release/analyzer-coder-components-offline-1.0.0.tar.gz` 只包含 PostgreSQL/pgvector 和 Nginx 两个组件镜像，以及 Compose、Linux/Windows 安装脚本和 SHA-256 校验文件。业务代码和构建产物不进入镜像；目标机器使用 `pull_policy: never`，不会联网拉取镜像。

构建机器需要能够访问 Docker Hub，以获取 Nginx 和 pgvector 镜像；只有生成后的目标安装机器可以完全离线。

详细的目标机安装步骤包含在离线包内的 `README.md` 中。

完整的 Linux 组件启动、宿主机代码部署、systemd 配置和验收步骤见 `../docs/07-linux-component-quickstart.md`；离线包内同时包含同内容的 `STARTUP-GUIDE.md`。
