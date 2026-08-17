# Analyzer Coder 组件离线包

本安装包只包含两个基础组件镜像：

- PostgreSQL 17 + pgvector
- Nginx 1.27

后端 JAR、前端源码和前端构建产物不在镜像包内。目标机器只需要 Docker 和 Docker Compose v2 即可导入并启动组件；应用代码由宿主机单独构建和运行。

完整的宿主机后端、前端、systemd、验收和故障排查步骤见同目录的 `STARTUP-GUIDE.md`。

## 安装组件

Linux：

```bash
tar -xzf analyzer-coder-components-offline-<version>.tar.gz
cd analyzer-coder-components-offline-<version>
bash install.sh
```

Windows PowerShell 7：

```powershell
pwsh -File install.ps1
```

安装程序会校验 `images.tar`、导入两个镜像、生成 `.env.components`，然后启动 PostgreSQL 和 Nginx。首次运行会显示随机数据库密码，请保存该密码供宿主机后端使用。

组件默认地址：

- PostgreSQL：`127.0.0.1:5432`
- Nginx：`http://127.0.0.1:8088`
- Nginx `/api/` 上游：宿主机后端 `127.0.0.1:8080`
- Nginx 静态目录：`frontend/dist`

## 在宿主机运行代码

先构建前端静态文件：

```bash
cd frontend
npm ci
npm run build
```

Nginx 会直接读取 `frontend/dist`，无需重建或重启镜像。

启动后端前，读取 `.env.components` 中的 `POSTGRES_PASSWORD`，并设置至少以下环境变量：

```text
APP_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/codebase_kb
APP_DATASOURCE_USERNAME=codebase_kb
APP_DATASOURCE_PASSWORD=<.env.components 中的密码>
APP_FORWARD_HEADERS_STRATEGY=framework
```

然后在宿主机启动后端，使其监听 `8080`。Linux 上需要确保后端监听容器可访问的地址，而不只是 `127.0.0.1`。

## 日常操作

启动：

```bash
bash scripts/start.sh --components
```

日志：

```bash
docker compose --env-file .env.components -f compose.components.yaml logs -f
```

停止：

```bash
docker compose --env-file .env.components -f compose.components.yaml down
```

停止命令不会删除数据库卷。不要使用 `down -v`，除非明确需要永久删除数据库数据。

组件 Compose 设置了 `pull_policy: never`，目标机器启动时不会访问镜像仓库。如果镜像尚未导入，启动会直接失败。
