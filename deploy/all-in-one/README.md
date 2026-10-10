# Analyzer Coder 完整镜像离线部署手册

发布版本：{{RELEASE_VERSION}}

镜像：`analyzer-coder:1.0.0`

目标平台：Linux x86_64 / amd64

## 1. 全新安装与数据库初始化

首次安装按本节从空目录初始化；已有实例请执行第 10 节升级步骤。

首次启动时，系统自动建立数据库表、索引、约束及初始配置。无需手动建表或导入 SQL。

## 2. 镜像与交付文件

完整单镜像包含前端、Java 17 后端、PostgreSQL 17 / pgvector、Nginx、Git / SSH、Node.js / npm、CodeGraph 1.6.0 及 amd64 原生组件、Python、MCP 适配器。一台服务容器即可启动。

外部模型服务、模型权重及 API Key 在平台内配置。Docker 引擎与 Compose 插件需事先安装，不包含在发布包中。服务器导入本地镜像后无需访问镜像仓库；连接外部模型或远程 Git 时仍需相应网络。

| 文件 | 用途 |
| --- | --- |
| `analyzer-coder-docker-{{RELEASE_VERSION}}.tar.gz` | 完整离线发布压缩包 |
| 同名 `.tar.gz.sha256` | 发布压缩包校验文件 |
| 包内 `image.tar` | Docker 完整镜像，可单独 docker load |
| 包内 `analyzer.sh` / `analyzer.ps1` | Linux / Windows 安装与维护入口 |
| 包内 `compose.yaml` / `image.env` | 编排与镜像版本 |
| 包内 `MANIFEST.json` | 架构、镜像 ID 和源码信息 |
| 包内 `SHA256SUMS` / `README.md` | 文件校验及操作说明 |

包内没有生产 `.env`、账号数据、数据库或知识附件。首次初始化会生成密码、主密钥、配置和空数据目录。

## 3. 服务器准备

服务器需为 x86_64 / amd64，已安装 Docker 和支持 `--wait` 的 Docker Compose v2。部署账号须能运行 Docker；Linux 还需 Bash、tar、sha256sum。

```bash
uname -m
docker info --format '{{.OSType}}/{{.Architecture}}'
docker compose version
docker compose up --help | grep -- --wait
```

确认 Docker 使用 Linux 容器。选定固定部署目录（下文 `/opt/analyzer-coder`），保证磁盘可同时容纳镜像、解压包、业务数据与备份；容量随仓库规模增长。为需要访问的平台用户放行所配置端口，默认 TCP 18081。

## 4. Linux 首次安装

将压缩包和对应校验文件上传至服务器 `/tmp`，执行：

```bash
cd /tmp
sha256sum -c analyzer-coder-docker-{{RELEASE_VERSION}}.tar.gz.sha256
mkdir -p /opt
# 首次安装要求目标目录尚不存在，避免覆盖已有实例。
test ! -e /opt/analyzer-coder || { echo '安装目录已存在，请先确认是否已有实例'; exit 1; }
tar -xzf analyzer-coder-docker-{{RELEASE_VERSION}}.tar.gz -C /opt
mv /opt/analyzer-coder-docker-{{RELEASE_VERSION}} /opt/analyzer-coder
cd /opt/analyzer-coder
sha256sum -c SHA256SUMS
bash analyzer.sh start
```

校验应全部为 `OK`。脚本自动加载镜像、生成四个独立随机密码/密钥、初始化配置、启动容器并等待健康。正常结果显示服务 `healthy`。

若首次启动前需修改端口，先执行以下命令，再启动：

```bash
bash analyzer.sh init
# 编辑 .env 中的 APP_HTTP_PORT、APP_HTTP_BIND_ADDRESS 等配置。
vi .env
bash analyzer.sh start
```

脚本不会把密码直接打印到终端。由部署管理员在服务器查看初始账号：

```bash
grep '^APP_INITIAL_ADMIN_' .env
```

打开 `http://服务器IP:18081`，使用初始账号登录并按提示改密。默认账号为 `admin`。密码和两个主密钥保存在 `.env`，必须与数据一起备份并妥善保管。

## 5. 验证与数据位置

```bash
cd /opt/analyzer-coder
bash analyzer.sh status
export ANALYZER_INSTALLATION_ROOT="$PWD"
docker compose exec -T analyzer /usr/local/bin/analyzer-healthcheck
# 内部健康接口不对公网开放。
docker compose exec -T analyzer curl -fsS http://127.0.0.1:8081/actuator/health
# 检查实际执行历史，应仅有 version=1、success=t。
docker compose exec -T -u postgres analyzer \
  psql -U codebase_kb -d codebase_kb -c \
  'SELECT version,description,success FROM public.flyway_schema_history ORDER BY installed_rank;'
```

健康接口预期返回 `{"status":"UP"}`。若修改了数据库名或用户名，相应调整 psql 参数。浏览器确认可登录，并导入一个测试项目验证代码读取和图谱。

**本版使用安装目录内的 bind 挂载，不使用旧版隐藏命名卷。**

| 宿主机位置（安装目录为 /opt/analyzer-coder） | 容器路径 / 用途 |
| --- | --- |
| `/opt/analyzer-coder/.env` | 环境配置、数据库密码、初始化账号、两个主密钥 |
| `/opt/analyzer-coder/config/application.yml` | `/config/application.yml`，Spring Boot 外挂配置 |
| `/opt/analyzer-coder/config/nginx.conf` | `/config/nginx.conf`，代理与上传设置 |
| `/opt/analyzer-coder/config/postgresql.conf` | `/config/postgresql.conf`，数据库调优 |
| `/opt/analyzer-coder/data/postgres` | `/data/postgres`，完整 PostgreSQL 数据 |
| `/opt/analyzer-coder/data/managed/repositories` | `/data/managed/repositories`，项目、分支代码和 CodeGraph 图谱 |
| `data/managed/repositories/项目ID/knowledge/objects` | 知识图片与附件内容，按哈希存储 |
| `/opt/analyzer-coder/data/managed/staging` | `/data/managed/staging`，导入和上传暂存 |
| `/opt/analyzer-coder/data/repositories` | `/data/repositories`，可接入的本地源仓库 |
| `/opt/analyzer-coder/data/logs` | `/data/logs`，后端日志 |
| `/opt/analyzer-coder/backups` | 完整停机备份，包含 data、config、.env |

Nginx 与服务控制台日志通过 `bash analyzer.sh logs` 查看。不要在数据库运行时复制原始 PGDATA 当作一致性备份，也不要手工改名或删附件哈希文件。镜像归档不是业务数据备份。

## 6. 配置与维护

| 命令 | 行为 |
| --- | --- |
| `bash analyzer.sh start` | 启动并等待健康，重复执行保留未变化容器 |
| `bash analyzer.sh stop` | 优雅停止，保留数据和配置 |
| `bash analyzer.sh restart` | 替换容器并载入修改后的配置 |
| `bash analyzer.sh status` | 查看容器及健康状态 |
| `bash analyzer.sh logs` | 查看日志，Ctrl+C 退出查看 |
| `bash analyzer.sh backup` | 停机备份；原来运行时备份完成后恢复服务 |

`.env` 可修改端口、绑定地址、JVM 参数和内网 Git 主机；`config/` 放置后端、Nginx、数据库配置。编辑后运行 restart。数据库密码在首次建库时生效，修改 `.env` 不会自动修改已有数据库密码；初始管理员密码也不会覆盖已存在账号。

不要删除 `data/`、`.env` 或 `config/`。数据库目录由容器服务用户管理，不要递归修改成普通宿主用户。多个实例应设置不同的 `COMPOSE_PROJECT_NAME` 与端口。固定目录不要在运行时搬迁。

## 7. 常见问题

- 启动校验失败：重新上传发布包，不跳过校验。
- 端口被占用：编辑 `.env` 的 `APP_HTTP_PORT`，执行 restart。
- 提示 Compose 项目属于另一安装目录：确认当前目录与现有部署；多实例设置不同项目名。不要直接覆盖原实例。
- Flyway 初始化失败：查看 `data/logs/backend.log`，检查磁盘、数据库权限和初始化错误；不要在未查明原因时删除数据目录。
- 初始化超时：查看 `bash analyzer.sh logs` 和 `data/logs/backend.log`，保留数据库排查原因。
- 未配置外部模型：可先使用本地检索，随后在页面配置可访问的模型服务。

## 8. Windows Docker Desktop

Windows 需要 Docker Desktop 的 Linux 容器模式和 PowerShell 7，镜像仍是 Linux amd64。将发布包解压到固定目录，然后执行：

```powershell
pwsh -File analyzer.ps1 start
pwsh -File analyzer.ps1 status
pwsh -File analyzer.ps1 backup
```

数据同样位于该安装目录的 `data`，配置在 `config` 和 `.env`。Windows 应限制 `.env` 和备份文件的访问权限。

## 9. 内网问答与向量服务

在 `config/application.yml` 的已有 `app.llm` 下配置 `endpoint-exceptions`。问答和向量服务使用不同地址时分别列出，`base-url` 与页面填写的基础地址一致（包含协议、端口和路径），程序自动追加模型接口路径。示例域名需替换为实际服务地址：

```yaml
app:
  llm:
    endpoint-exceptions:
      - base-url: "https://chat.example.com/v1"
        allow-private-network: true
        skip-tls-verification: false
      - base-url: "https://embedding.example.com/v1"
        allow-private-network: true
        skip-tls-verification: false
```

保存服务器配置后执行 `bash analyzer.sh restart`。HTTPS 证书正常时保持 `skip-tls-verification: false`。

向量维度应与服务实际返回长度一致，例如标准 bge-m3 的稠密向量为 1024 维。客户端显式请求 `encoding_format: float`；服务明确拒绝 `dimensions` 参数时，会在同一超时预算内去掉该参数重试一次。返回向量仍须通过页面配置的维度校验，不截断或补齐向量。若仍返回 400，检查服务端错误正文、模型标识及基础地址。


## 10. 升级现有安装

适用于使用本项目 analyzer.sh / analyzer.ps1 管理、data 和 config 位于安装目录中的 1.0.2 / 1.0.3 / 1.0.4 / 1.0.5 实例。新版本为 {{RELEASE_VERSION}}，新增知识草稿删除及向量、Markdown 关联的事务清理；增加向量共享节流、HTTP 429 等待重试和已完成片段即时保存复用，将项目问答移到项目总览下方。保留此前的长文本分段聚合、超限批次缩小、问答真实调用修复、完整响应超时和关联日志。升级保留现有业务数据、模型设置、密码、主密钥、端口及已编辑的 config。

先将新压缩包和同名校验文件上传到服务器 /tmp。新包解压到独立目录，不能直接覆盖正在运行的安装目录。下文原安装目录为 /opt/analyzer-coder，请按实际路径调整。

```bash
# 校验与解压任何一步失败，均不继续升级。
(
  set -e
  cd /tmp
  sha256sum -c analyzer-coder-docker-{{RELEASE_VERSION}}.tar.gz.sha256
  test ! -e analyzer-coder-docker-{{RELEASE_VERSION}}
  tar -xzf analyzer-coder-docker-{{RELEASE_VERSION}}.tar.gz
  cd analyzer-coder-docker-{{RELEASE_VERSION}}
  sha256sum -c SHA256SUMS
)
```

确认上一步全部通过后，在维护窗口执行。先停机再备份，避免备份完成后产生尚未包含在备份中的新数据；任何一步失败即停止后续操作：

```bash
(
  set -e
  cd /opt/analyzer-coder
  bash analyzer.sh stop
  bash analyzer.sh backup
  # 确认终端输出 Backup 路径并保留该文件。
  bash analyzer.sh upgrade /tmp/analyzer-coder-docker-{{RELEASE_VERSION}}
  bash analyzer.sh status
  grep '^ANALYZER_IMAGE=' .env
)
```

预期镜像为 analyzer-coder:{{RELEASE_VERSION}}、容器状态 healthy。升级命令会加载新镜像、更新启动文件和镜像版本并启动服务；不需要手动导入 SQL。不要删除原 .env、config、data 或 backups。升级失败时保留日志和备份，不反复初始化空库。

登录后重新检测问答模型和向量模型，再执行一次有证据的知识问答和一次向量任务。检查新日志中的实际 CHAT、EMBEDDING / EMBEDDING_BATCH 调用及失败原因；检测成功并不等同于大批量或长上下文调用必然成功。前序批次已有向量而任务失败时，应按第 11 节定位失败批次。 对此前因输入超限或 HTTP 429 失败的向量任务，升级后重新提交向量任务；已完成的完整片段保留并复用，无需删除业务数据或重新导入仓库。限流等待期间任务可能继续显示运行中，可查看“向量请求等待额度”“向量服务限流”和“向量请求等待结束”日志。默认每分钟 30 次实际请求，后台等待及重试参数见第 11 节。

Windows Docker Desktop 使用独立解压的新包目录：

```powershell
pwsh -File analyzer.ps1 stop
# 确认停止成功后备份，确认备份成功后升级。
pwsh -File analyzer.ps1 backup
pwsh -File analyzer.ps1 upgrade -PackageDirectory C:/packages/analyzer-coder-docker-{{RELEASE_VERSION}}
pwsh -File analyzer.ps1 status
```

## 11. 问答与向量调用排障

知识问答先调用当前启用的向量模型生成查询向量，再检索代码与知识证据，最后调用本轮选择的问答模型。两者分别读取自己的服务地址、模型标识、密钥和超时设置。

### 检测与实际调用

- 问答检测使用短提示词，但非流式生成参数与实际问答一致，包括配置的输出 Token 上限。实际问答输入还包含历史和证据，短文本检测通过不能保证长上下文、持续负载下的每次请求都成功。
- 问答接口当前使用非流式生成；流式首字检测失败会标记 DEGRADED，基础生成通过且熔断关闭时仍可选择该模型。重新检测基础生成通过可以恢复熔断。
- 外部向量检测同时验证单条、批量输入和维度，复用索引任务的单条降级路径。检测全过程共享配置的请求超时预算。短文本检测不会代替实际大批次负载验证。
- 问答、向量和检测都限制完整响应耗时。即使 HTTP 200 已返回，正文不结束或流式没有首字也会超时。
- 明确的上下文或输入长度超限单独报告 `LLM_INPUT_TOO_LONG`。批次超限时二分缩小批次，保留结果顺序和批量能力；其他不支持数组的 HTTP 400/413/422 或批量结果不匹配才降级为单条。鉴权失败和超时会报告原错误；429 限流仅在后台向量任务中按下面的等待策略重试。
- 长文本先按最多 6000 UTF-8 字节分段（字节预算不是 Token 计数），保持 Unicode 字符和全文内容。服务仍报告单条超限时继续二分；全部子段成功后按字节长度加权平均并做 L2 归一化，生成原片段的一条语义向量。这是分段向量的聚合，不等同于模型直接编码整段文本；普通短文本保留模型原始向量。索引、知识和查询向量复用此路径，不需要重建已完成的代码内容索引。单个长文本全部子请求共用原请求响应预算（后台任务的额度等待单独计时），最多处理 512 个子段，失败时不会将部分子段当作完整向量写入。

### 页面行为

选定问答模型后，真实调用失败会显示错误码、服务端可识别的错误说明、模型、输入字符数、输出上限、超时、实际耗时和调用 ID。本轮不保存为成功回答，保留输入并允许重试。模型健康记录使用独立事务，问答回滚不会清除失败次数。

向量检索失败时，关键词证据仍可用于回答。展开回答下方“检索”详情查看具体模型错误码与脱敏说明。出现无证据提示时不会继续调用问答模型。

项目及分支向量任务先补齐项目中已发布且审核通过的知识向量，再处理目标版本的代码片段；草稿和归档知识不参与。已匹配当前模型、维度、检索能力和知识修订号的向量会复用。知识阶段失败时停止任务，重试先补齐缺失知识，再继续代码，已完成的知识和代码向量均保留。日志以 `KNOWLEDGE_VECTOR_*` 标识知识阶段，以 `VECTOR_*` 标识代码阶段，并分别记录缺失量、批次写入量和完成量。

分支向量任务失败还显示失败批次、该批次输入数量及之前已处理的片段数；如果批量请求降级为单条，会显示具体失败位置及原批量错误。单条降级或批次二分时，每个完整片段生成后立即写入；即使本批后续片段失败，先前完整结果仍保留。错误显示本批已写入数量，重新提交任务只处理缺失片段。一个长片段的部分子段不会写入。页面存在部分向量并不代表本次任务完成。

### 日志级别与关联标识

默认 INFO 级别即可观察主流程，无需开启 HTTP 请求正文或全局 DEBUG 日志：

- INFO：问答开始与结束、问答阶段、模型配置选择、模型请求开始与成功、检索通道汇总及各通道耗时、向量索引计划和批次写入进度、向量请求等待额度及等待结束、检测阶段、分支任务开始与结束。
- WARN：检索通道不可用、引用校验降级、批量向量降级为单条、dimensions 参数兼容重试、429 限流及是否重试和冷却时间、检测不可用或流式降级。
- ERROR：实际模型调用失败、问答失败、向量生成或写入失败、分支任务失败及未预期的检测异常。

每条模型日志带 traceId（整轮流程）、callId（单次模型请求）和当前 stage，并在适用时带 repoId、branchId、taskId、checkId。问答 traceId 使用本轮 clientRequestId；后台任务 traceId 使用 taskId；异步问答检测使用 checkId。批量失败与兼容重试沿用所在流程的 traceId。线程复用及嵌套调用结束后会恢复上下文，避免标识串到其他请求。

请求开始记录目标主机 endpointHost、模型、输入数量/字符数、维度或输出上限、请求超时与本次剩余预算。结束记录 HTTP 状态及实际耗时。输入和回答仅记录长度，密钥及可识别的错误说明做脱敏；异常堆栈保留类型和代码位置，不复制原始异常消息、SQL 或响应正文。

向量任务按批次记录，不逐片段记录进度：计划显示缺失片段数和批次大小；批次记录生成、写入、已写入数量及之前完成数量。异常字段 batchWritten 用于区分该批次尚未写入和写入过程中失败。

### 向量服务限流与等待

例如服务规定“每分钟最多 40 次，包括失败请求”，长文本分段、批次拆分和参数兼容重试都可能增加实际请求数。应用默认按同一主机、端口和凭据共享额度，每分钟最多 30 次、间隔至少 2 秒，每一次真实 HTTP 请求都经过节流。此限额作用于单个后端进程；其他应用或多个后端实例共享同一服务额度时，应继续降低各实例额度，或使用服务端统一限流。

后台项目向量任务（知识和代码阶段）遇到 HTTP 429 时，优先遵循 Retry-After（秒数或 HTTP 日期）；无有效响应头时默认冷却 60 秒，最多重试 3 次。每次请求允许等待额度最多 120 秒，超过预算或重试次数后显示 LLM_RATE_LIMITED。等待每秒检查任务是否已被接管或取消，仍保留已完成的完整片段。等待额度不占网络响应预算；真实网络响应和单个长文本的全部子请求仍受响应超时约束。检测和交互式查询保持原请求总预算，收到 429 不自动长时间重试，并将冷却状态共享给后台任务。

可在部署 application.yml 已有的 app.llm 下设置，修改后重启服务：

```yaml
app:
  llm:
    embedding-requests-per-minute: 30
    embedding-max-wait-ms: 120000
    embedding-rate-limit-retries: 3
    embedding-rate-limit-cooldown-ms: 60000
```

仅在确认服务额度后提高请求速率。若同一凭据还有其他流量，30 次/分钟仍可能触发服务限制，等待恢复后重试。

### 定位日志

在安装目录执行：

```bash
# 最近的问答、向量、流式检测与分支任务异常
grep -E '知识问答开始|知识问答失败|模型调用开始|模型调用失败|检索通道不可用|向量批次失败|分支任务失败' data/logs/backend.log | tail -n 100

# 先按页面上的“调用ID”查出 traceId，再串起整个流程
grep -F 'traceId=实际流程ID' data/logs/backend.log

# 将页面上的“调用ID”填入，匹配同一次请求
grep -F '页面显示的调用ID' data/logs/backend.log

# 查看分支任务的异常堆栈
grep -n -B 3 -A 45 '分支任务失败' data/logs/backend.log | tail -n 100
```

日志 operation 区分 CHAT（实际问答）、CHAT_PROBE（基础生成检测）、STREAM_PROBE（流式检测）、EMBEDDING（单条向量）和 EMBEDDING_BATCH（批量向量）。向量失败同时列出输入条数、总字符数、最长输入和配置维度。失败日志中的 callId 对应页面“调用ID”。

HTTP 客户端自带的 200 日志只表示响应状态；应用的“模型调用成功”还要求正文完成、协议校验通过。日志不记录请求正文、回答正文、完整服务地址或 API 密钥。

| 现象 / 错误码 | 检查方向 |
| --- | --- |
| LLM_INPUT_TOO_LONG | 输入超过模型上下文上限。例如服务说明输入 11193 tokens、上限 8192，属于长度超限，增加超时无效。查看分段、批次缩小及合并日志；无法自动分段时拆分源文件或调整模型上下文限制。 |
| LLM_TIMEOUT | 比较配置超时、实际耗时与本次剩余预算；核对网络、服务排队和负载。检测及单个长文本分段各有总预算。确认原因后调整超时。 |
| LLM_AUTH_FAILED | 核对失败调用所用的模型配置与密钥权限。问答和向量可能来自不同网关。 |
| LLM_RATE_LIMITED | HTTP 429 表示服务限流。查看向量请求等待额度、向量服务限流日志，确认重试次数和冷却时间；超出等待预算或重试上限时稍后重新提交任务，已完成片段会复用。 |
| HTTP 400/413/422 | 读取服务错误说明，核对上下文长度、输入大小、输出预算和不支持的参数。 |
| VECTOR_DIMENSION_INCOMPATIBLE | 核对配置维度与实际维度；不自动截断或填充向量。 |
| LLM_PROTOCOL_INVALID | 检查非空回答正文、JSON 结构、向量数值和 HTTP 状态。仅 reasoning_content 不构成回答。 |
| LLM_OUTPUT_TRUNCATED | 服务 finish_reason=length，回答未完成；提高模型允许范围内的输出上限或缩小问题。 |
| LLM_OUTPUT_FILTERED | 服务拦截了回答，调整问题后重试。 |
| CITATION_VALIDATION_FAILED | 模型已返回正文，但引用校验未通过；这是回答质量校验，与网络调用失败不同。 |

本地故障注入测试验证调用链行为，不能替代目标服务器、目标网关和实际长文本的验证。

## 12. 知识草稿删除与菜单调整

研发工作菜单顺序为：项目总览 → 项目问答 → 联合检索 → 代码图谱。

具备项目管理权限的用户可在知识草稿的“更多”菜单选择“删除草稿”。已发布或已归档知识须先“撤回为草稿”。确认框会列出知识共享范围；删除对该卡片的全部使用分支生效，并永久清理修订记录、知识向量及 Markdown 预备知识关联。服务端会再次检查管理权限、当前分支适用性、草稿状态及修订号；卡片被修改时拒绝删除，清理失败时整体回滚。

原 Markdown 文件和预备知识条目保留，无其他关联知识时恢复为“待生成”，可重新生成草稿；如果同一来源仍关联其他知识，则保留那些有效关联。页面会同步刷新知识和 Markdown 预备知识列表。历史问答保留当时的证据快照。
