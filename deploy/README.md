# 发布部署文件

当前 Linux 部署只有一个应用启动入口：

```bash
bash scripts/start.sh
```

脚本在 Git 源码根目录依次完成前端构建、后端构建、PostgreSQL/Nginx 启动、应用配置生成、后端启动和健康检查。

完整首次部署、Git 更新、公网 IP 访问、日志、停止和 Volume 说明见 `../docs/08-linux-git-deployment.md`。

## 组件离线包

组件离线包只包含 PostgreSQL/pgvector 和 Nginx 镜像，不包含源码、前端产物或后端 JAR。

联网构建机执行：

```bash
bash scripts/build-offline-package.sh --version 1.0.0
```

Windows PowerShell 7：

```powershell
pwsh -File scripts/build-offline-package.ps1 -Version 1.0.0
```

目标机解压后只需导入镜像：

```bash
bash install.sh
```

随后通过 Git 拉取 `v2` 分支，并在源码根目录运行唯一启动脚本。

## 配置文件

- `nginx-components.conf`：组件 Nginx 配置，挂载前端 `dist` 并代理宿主机后端。
- `nginx-compose-edge.conf`：HTTPS 边缘代理模板。
- `nginx-host.conf`：传统宿主机 Nginx 模板。
- `analyzer-coder.service`：可选的后端 systemd 单元。
- `analyzer-coder.env.example`：传统 systemd 环境模板。
- `analyzer-coder-components.env.example`：组件模式 systemd 环境模板。

所有占位密码、域名和证书路径必须在使用前替换。
