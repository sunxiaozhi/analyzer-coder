# Analyzer Coder 组件离线包

本安装包只包含：

- PostgreSQL 17 + pgvector 镜像；
- Nginx 1.27 镜像；
- `images.tar` SHA-256 校验文件；
- Linux/Windows 镜像导入脚本；
- Git 部署指南。

它不包含业务源码、前端构建产物或后端 JAR，也不会直接启动 Compose。

## 导入镜像

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

安装脚本只执行校验和 `docker load`。完成后应看到：

```text
analyzer-coder/postgres:offline
analyzer-coder/nginx:offline
```

## 拉取代码并启动

```bash
git clone \
  --branch v2 \
  --single-branch \
  https://github.com/sunxiaozhi/analyzer-coder.git \
  /opt/analyzer-coder

cd /opt/analyzer-coder
bash scripts/start.sh
```

完整步骤见包内 `STARTUP-GUIDE.md`。
