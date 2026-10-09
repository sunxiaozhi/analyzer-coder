# 发布规则与记录

## 固定输出位置

所有正式成果物放在仓库根目录 `release/`，与源码项目在一起。默认命令：

```powershell
pwsh -File scripts/build-docker-image.ps1
```

```bash
bash scripts/build-docker-image.sh
```

打包读取根目录 `VERSION`，不从当前日期、分支名、功能名或聊天目录推算版本。除非用户明确要求，不能改变输出位置。

## 版本规则

正式版本为 `MAJOR.MINOR.PATCH`，各段是无前导零的非负整数。版本管理由 VERSION 统一控制；命令行正式版本覆盖只接受与 VERSION 相同的值。

- 普通修订发布依次递增 PATCH，例：1.0.1 → 1.0.2。
- 新增一组功能递增 MINOR 并将 PATCH 归零，例：1.0.2 → 1.1.0。
- 不兼容重大变化递增 MAJOR，并将 MINOR、PATCH 归零。
- 重复执行构建不改变版本号；已有成果物不覆盖。发布失败不算新发布，不为解决路径冲突自动改号。
- 有新版本发布时，先更新 VERSION 和下方记录，再打包。已经发布的版本不复用。无新发布意图时直接复用已验证成果物。
- CI 使用 `VERSION-ci.提交ID`；正式交付不能用 CI 版本。

输出固定为：

```text
release/
├─ analyzer-coder-docker-版本/
│  ├─ image.tar
│  ├─ analyzer.sh / analyzer.ps1
│  ├─ compose.yaml / image.env
│  ├─ README.md
│  ├─ MANIFEST.json
│  └─ SHA256SUMS
├─ analyzer-coder-docker-版本.tar.gz
└─ analyzer-coder-docker-版本.tar.gz.sha256
```

README.md 即随包部署手册，MANIFEST.json 记录 releaseVersion、镜像、架构、源码信息与挂载位置。包内与包外使用同一个版本。

## 本次部署文档范围

按全新安装编写，首次启动自动初始化空数据库，默认 Linux amd64。文档不展开 SQL 合并过程、不提及旧迁移文件名，不安排旧库迁移或升级操作。包含一键启动、配置、登录、固定目录、数据存储、备份与排障即可。

## 发布记录

| 正式版本 | 日期 | 说明 |
| --- | --- | --- |
| 1.0.0 | 2026-10-08 | 已有正式完整镜像发布，作为发布编号起点。 |
| 1.0.1 | 2026-10-09 | 当前完整部署交付：全新安装初始化、统一项目 release 输出及版本约定，随包部署文档清理。 |

此前日期或功能名命名的包属于临时交付，不作为后续正式版本编号依据。

## 执行约定

此规则已获用户确认，记录于根目录 AGENTS.md，后续打包直接遵守。无需每次询问输出位置或重新确认命名。对构建、验收、导出任一失败返回错误，不交付带 .incomplete 标记的目录。保留历史发布包，不自动删除或覆盖。