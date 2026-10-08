# 单镜像部署

部署模板统一位于 [all-in-one](all-in-one/README.md)。镜像包含全部应用与运行依赖，data 和 config 通过宿主目录外挂，.env 管理密码、密钥、端口和启动选项。

打包入口为 scripts/build-docker-image.sh / .ps1；安装、启停、备份和单镜像升级统一使用发布包中的 analyzer.sh / analyzer.ps1。

模板目录不保存业务数据和真实配置；启动时生成的 .env、data、config、backups 已加入 Git 和构建上下文排除规则。
