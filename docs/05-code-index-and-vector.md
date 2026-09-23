# 代码索引与向量索引
> 本文档由当前实现反推生成（2026-09-19）。描述已实现的需求，不是新设计。

本文覆盖两条互相衔接但彼此独立的链路：一是把仓库内容版本切分为可检索的**代码片段**（chunk，含文件片段与符号片段）并抽取符号；二是为片段与知识卡生成、维护**字符向量 / 语义向量**，并提供覆盖统计。代码位置仅以当前实现为准。

## 1 功能范围与角色

- 范围：仓库文件扫描与分类、片段切分、符号抽取、索引任务生命周期、增量执行计划、片段与**内容版本**（contentVersion）的一致性、向量写入与覆盖统计、向量模型配置。
- 角色与权限级别：`READ`、`MAINTAIN`、`MANAGE` 三级，另有仓库所有者（owner）关系与超级管理员角色。启动索引/图谱任务、取消与重试需要 `MAINTAIN`；读取索引状态、片段列表、向量覆盖统计需要 `READ`；跨仓库索引任务分页、向量模型配置与启用仅超级管理员可操作。
- 后台执行者：索引任务由定时 Worker 领取执行，不依赖 HTTP 请求线程；检索请求本身不构建索引。
- 非范围：调用关系解析、跨语言语义分析、向量近邻索引（HNSW 等）不在本域实现。

## 2 需求条目

### IDX-001 文件扫描的纳入范围

- 需求：只把明确支持的文件类型纳入索引，跳过不支持的扩展名、忽略目录与超限文件。
- 规则：
  - 递归遍历仓库内容版本根目录，只处理普通文件；路径中任一层目录名命中忽略集合即整棵子树跳过：`.git`、`.codegraph`、`.idea`、`.vscode`、`node_modules`、`dist`、`build`、`target`。
  - 仅当扩展名或文件名出现在语言映射表中才纳入；文件以 UTF-8 读取，内容全为空白则跳过。
  - 读取失败或运行期异常的文件被静默跳过，只返回空列表，不使整次扫描失败；相对路径统一以 `/` 分隔。
- 证据：`backend/src/main/java/com/analyzercoder/infrastructure/indexing/FileSystemRepositoryScanner.java:23`、`backend/src/main/java/com/analyzercoder/infrastructure/indexing/FileSystemRepositoryScanner.java:94`、`backend/src/main/java/com/analyzercoder/infrastructure/indexing/FileSystemRepositoryScanner.java:107`、`backend/src/main/java/com/analyzercoder/infrastructure/indexing/FileSystemRepositoryScanner.java:116`、`backend/src/main/java/com/analyzercoder/infrastructure/indexing/FileSystemRepositoryScanner.java:129`、`backend/src/main/java/com/analyzercoder/infrastructure/indexing/FileSystemRepositoryScanner.java:144`

### IDX-002 单文件大小上限

- 需求：超过 `app.indexing.max-file-bytes` 的文件不进入索引，避免超大文件拖垮扫描与切片。
- 规则：
  - 判定依据是文件系统字节数 `Files.size(path) <= maxFileBytes`。
  - 默认值 524288 字节（512 KiB），配置键为 `app.indexing.max-file-bytes`，无环境变量占位符。
  - 取不到文件大小时视为超限并跳过。
- 证据：`backend/src/main/resources/application.yml:84`、`backend/src/main/java/com/analyzercoder/infrastructure/indexing/FileSystemRepositoryScanner.java:84`、`backend/src/main/java/com/analyzercoder/infrastructure/indexing/FileSystemRepositoryScanner.java:121`

### IDX-003 语言识别

- 需求：为每个文件确定语言标识，供符号抽取与资产分类使用。
- 规则：
  - 优先按文件名精确匹配：`dockerfile`、`makefile`、`gradlew`、`mvnw`、`.cursorrules`、`.env`。
  - 否则按小写扩展名匹配，覆盖 java、kt、ts、tsx、js、jsx、py、go、rs、cs、c、h、cpp、hpp、php、rb、sh、bat、cmd、md、mdx、rst、txt、yml、yaml、json、xml、sql、properties、toml、ini、conf、env、html、css、scss、vue。
  - 未命中映射表的扩展名既不会被扫描，也不会出现语言推断。
- 证据：`backend/src/main/java/com/analyzercoder/infrastructure/indexing/FileSystemRepositoryScanner.java:33`、`backend/src/main/java/com/analyzercoder/infrastructure/indexing/FileSystemRepositoryScanner.java:41`、`backend/src/main/java/com/analyzercoder/infrastructure/indexing/FileSystemRepositoryScanner.java:149`

### IDX-004 资产类型分类（asset_type）

- 需求：把每个文件归入 `CODE`、`DOCUMENT`、`RULE`、`TASK`、`CONFIG` 五类之一，并写入片段的 `asset_type`。
- 规则：
  - 判定顺序固定：规则文件 → 任务文件 → 配置 → 文档 → 兜底 `CODE`。
  - 规则文件：文件名属于 `agents.md`、`claude.md`、`codex.md`、`.cursorrules`、`copilot-instructions.md`、`contributing.md`，或路径包含 `/.github/instructions/`、`rules/`。
  - 任务文件：文件名属于 `tasks.md`、`task.md`、`todo.md`、`roadmap.md`、`checklist.md`、`gate.md`，或后缀 `.task.md`、`.gate.md`，或路径包含 `tasks/`、`gates/`。
  - 配置：扩展名属于 `yml`、`yaml`、`json`、`xml`、`properties`、`toml`、`ini`、`conf`、`env`，或文件名是 `dockerfile`、`makefile`、`gradlew`、`mvnw`，或语言是 yaml/json/properties。
  - 文档：语言为 `markdown` 或扩展名属于 `md`、`mdx`、`rst`、`txt`、`adoc`。
  - 该枚举受数据库约束限制为这五个取值。
- 证据：`backend/src/main/java/com/analyzercoder/domain/indexing/RepositoryAssetClassifier.java:23`、`backend/src/main/java/com/analyzercoder/domain/indexing/RepositoryAssetClassifier.java:49`、`backend/src/main/java/com/analyzercoder/domain/indexing/RepositoryAssetClassifier.java:57`、`backend/src/main/java/com/analyzercoder/domain/indexing/RepositoryAssetClassifier.java:67`、`backend/src/main/resources/db/migration/V1__init_schema.sql`

### IDX-005 默认索引链路的文件片段窗口与重叠

- 需求：按固定行窗口把文件切成文件片段，相邻窗口保留重叠行，避免符号与语义被窗口边界切断。
- 规则：
  - 窗口上限 120 行，重叠 20 行，步长 100 行（`120 - 20`）；结束行取 `min(起始行 + 120, 总行数)`，窗口内容以 `\n` 重新拼接。
  - 内容全为空白时不生成片段；末窗口覆盖到文件末尾即终止，不产生空尾窗。
  - 行号从 1 开始记录：`startLine = 起始下标 + 1`，`endLine = 结束行`。
- 证据：`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobProcessor.java:30`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobProcessor.java:31`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobProcessor.java:286`

### IDX-006 片段类型推导（chunk_type）

- 需求：片段的 `chunk_type` 由“是否命中符号”与 `asset_type` 共同推导，枚举取值受数据库约束。
- 规则：
  - 文件片段：`asset_type = CONFIG` → `CONFIG`；否则 → `FILE`。
  - 符号片段：`asset_type = CODE` → `SYMBOL`；`CONFIG` → `CONFIG`；其余（文档、规则、任务）→ `DOC_SECTION`。
  - `ChunkType` 枚举还声明了 `TEST_CASE`、`KNOWLEDGE_CARD`，但当前实现的两条切分链路都不会产出这两个取值。
  - 符号片段的 `symbol_id` 形如 `filePath#symbolName:startLine`。
- 证据：`backend/src/main/java/com/analyzercoder/domain/chunk/CodeChunk.java:31`、`backend/src/main/java/com/analyzercoder/domain/chunk/CodeChunk.java:57`、`backend/src/main/java/com/analyzercoder/domain/chunk/CodeChunk.java:69`、`backend/src/main/java/com/analyzercoder/domain/chunk/ChunkType.java:4`

### IDX-007 符号片段归属

- 需求：窗口片段如果落在某个符号体内，应被标记为该符号的片段，而不是无归属的文件片段。
- 规则：
  - 先找“起始行不晚于窗口起始行、且结束行不早于窗口起始行”的符号，取行跨度最小者（最内层符号）。
  - 若没有，则退化取“起始行落在窗口行范围内”的符号中起始行最小者。
  - 两者都没有时才生成文件片段。
- 证据：`backend/src/main/java/com/analyzercoder/application/code/CodeSymbolExtractor.java:117`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobProcessor.java:298`

### IDX-008 分支内容索引的切分规则（与默认链路并列的独立实现）

- 需求：分支内容版本的内容索引使用一套独立的切分实现，同时产出文件片段与符号片段。
- 规则：
  - 文件片段：起点步长 100 行，结束行 `min(总行数, 起始 + 120)`，即与默认链路相同的 120/100 窗口。
  - 符号片段：对每个抽取到的符号，按其声明起止行切片，但从符号起始行起**最多 120 行**，超出部分被截断（完整内容仍可由文件片段获得）。
  - 起始行不小于结束行的符号直接跳过。
  - 片段总数超过 100000 时抛错要求缩小索引范围。
  - 一个文件都没有可索引文本时抛错，不发布空索引。
  - 写入前对分支内容版本行加 `FOR UPDATE` 锁并二次检查是否已索引，避免重复或恢复的任务重复写入。
  - 批量写入大小为 250。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchContentIndexService.java:52`、`backend/src/main/java/com/analyzercoder/application/branch/BranchContentIndexService.java:69`、`backend/src/main/java/com/analyzercoder/application/branch/BranchContentIndexService.java:76`、`backend/src/main/java/com/analyzercoder/application/branch/BranchContentIndexService.java:91`、`backend/src/main/java/com/analyzercoder/application/branch/BranchContentIndexService.java:100`、`backend/src/main/java/com/analyzercoder/application/branch/BranchContentIndexService.java:108`、`backend/src/main/java/com/analyzercoder/application/branch/BranchContentIndexService.java:154`

### IDX-009 符号抽取的语言覆盖

- 需求：用轻量源码声明规则抽取真实出现的符号，覆盖主要语言族。
- 规则：
  - Java / C#：`class|interface|record|enum` 记为对应类型；有修饰符的方法声明记为 `METHOD`。
  - Kotlin：`class|interface|object|enum class`；`fun` 记为 `FUNCTION`。
  - JavaScript / TypeScript：`class|interface|enum|type`；`function`、箭头函数赋值记为 `FUNCTION`；方法声明记为 `METHOD`。
  - Python：`class` → `CLASS`，`def` → `FUNCTION`。
  - Go：`func` → `FUNCTION`；`type X struct|interface` → `STRUCT` / `INTERFACE`。
  - Rust / PHP / Swift / Ruby：`fn|struct|enum|trait|mod`、`class|interface|trait|function`、`class|struct|protocol|enum|func`、`class|module|def`。
  - Shell / SQL / GraphQL：`function name` 或 `name()` → `FUNCTION`；`CREATE TABLE|VIEW|FUNCTION|PROCEDURE|TRIGGER`；`type|input|interface|enum|scalar|union`。
  - Markdown：`#` 至 `######` 标题 → `DOC_SECTION`；TOML 段名 → `CONFIG_SECTION`；json 键名与其它配置文件的 `key=` / `key:` → `CONFIG_KEY`。
  - 语言不在上述集合时，仅当路径后缀是 `.conf`、`.config`、`.ini`、`.cfg` 才按配置键抽取。
- 证据：`backend/src/main/java/com/analyzercoder/application/code/CodeSymbolExtractor.java:152`、`backend/src/main/java/com/analyzercoder/application/code/CodeSymbolExtractor.java:196`、`backend/src/main/java/com/analyzercoder/application/code/CodeSymbolExtractor.java:220`、`backend/src/main/java/com/analyzercoder/application/code/CodeSymbolExtractor.java:236`、`backend/src/main/java/com/analyzercoder/application/code/CodeSymbolExtractor.java:259`、`backend/src/main/java/com/analyzercoder/application/code/CodeSymbolExtractor.java:267`、`backend/src/main/java/com/analyzercoder/application/code/CodeSymbolExtractor.java:283`、`backend/src/main/java/com/analyzercoder/application/code/CodeSymbolExtractor.java:312`、`backend/src/main/java/com/analyzercoder/application/code/CodeSymbolExtractor.java:325`、`backend/src/main/java/com/analyzercoder/application/code/CodeSymbolExtractor.java:488`

### IDX-010 符号抽取的真实边界

- 需求：符号抽取是行级正则推断，必须明确它不产生的结论。
- 规则：
  - 不解析调用关系、引用、继承、类型或依赖；本实现只产出“符号名 + 种类 + 行范围”。
  - 控制关键字 `if/for/while/switch/catch/return/new/throw` 不会被误记为方法。
  - 符号范围按形态推断：花括号形态做括号配平，若起始行后 6 行内未出现左花括号则退化取下一个同级声明前一行；缩进形态按缩进回退；Markdown 形态按标题层级。
  - 单文件符号数上限 500，触发时结果标记 `truncated` 并给出 `SYMBOL_COUNT_LIMIT_EXCEEDED`；去重键是 `kind + name + startLine`，同名不同行的符号各自保留。
  - 已提供 `generatedCode` 判定（`generated` 目录、`.g.dart`、`.designer.cs`、文件头 2000 字符内的 `@generated` 等），但当前切分链路未调用它，生成代码不会被排除。
- 证据：`backend/src/main/java/com/analyzercoder/application/code/CodeSymbolExtractor.java:15`、`backend/src/main/java/com/analyzercoder/application/code/CodeSymbolExtractor.java:18`、`backend/src/main/java/com/analyzercoder/application/code/CodeSymbolExtractor.java:19`、`backend/src/main/java/com/analyzercoder/application/code/CodeSymbolExtractor.java:98`、`backend/src/main/java/com/analyzercoder/application/code/CodeSymbolExtractor.java:105`、`backend/src/main/java/com/analyzercoder/application/code/CodeSymbolExtractor.java:137`、`backend/src/main/java/com/analyzercoder/application/code/CodeSymbolExtractor.java:384`、`backend/src/main/java/com/analyzercoder/application/code/CodeSymbolExtractor.java:409`

### IDX-011 索引任务类型

- 需求：索引任务有明确的类型枚举，本域只处理内容索引两类。
- 规则：
  - 类型枚举为 `FULL`、`INCREMENTAL`、`CODEGRAPH`、`KNOWLEDGE_DRIFT`。
  - 通用启动接口只接受 `FULL` 与 `INCREMENTAL`，其它类型经该接口传入即被拒绝。
  - Worker 只领取 `FULL` 与 `INCREMENTAL`；`CODEGRAPH`、`KNOWLEDGE_DRIFT` 由各自的按类型领取语句与专用 Worker 处理。
  - 分支准备任务用另一套 kind：`SYNC`、`CONTENT`、`GRAPH`、`PREPARE`、`VECTORS`。
- 证据：`backend/src/main/java/com/analyzercoder/domain/indexing/IndexJobType.java:4`、`backend/src/main/java/com/analyzercoder/interfaces/rest/IndexController.java:50`、`backend/src/main/resources/mappers/IndexJobMapper.xml:62`、`backend/src/main/resources/mappers/IndexJobMapper.xml:70`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:103`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:232`

### IDX-012 索引任务状态机

- 需求：任务状态迁移受聚合不变量约束，终态不可被改写。
- 规则：
  - 状态枚举为 `QUEUED`、`RUNNING`、`CANCEL_REQUESTED`、`SUCCEEDED`、`FAILED`、`CANCELED`；新建任务固定为 `QUEUED`，`current_step = 'queued'`。
  - `start(step)` 只允许从 `QUEUED` 或 `RUNNING` 进入 `RUNNING`，并写入起始时间与心跳时间。
  - `requestCancel`：`QUEUED` 直接变 `CANCELED`（步骤 `canceled`，立即写结束时间）；`RUNNING` 变 `CANCEL_REQUESTED`（步骤 `cancel_requested`）；其它状态抛错。
  - `cancel()` 只允许从 `CANCEL_REQUESTED` 或 `QUEUED` 进入 `CANCELED`。
  - `succeed(step)` 只允许从 `RUNNING` 或 `CANCEL_REQUESTED` 进入 `SUCCEEDED`。
  - `fail(step, failureCode, message)` 只允许从 `RUNNING` 或 `CANCEL_REQUESTED` 进入 `FAILED`；无代码重载默认 `TASK_FAILED`。
  - `withExecutionPlan` 只允许在 `RUNNING` 时写入且模式只能是 `FULL` 或 `INCREMENTAL`；`withTimeout` 只允许在 `RUNNING` 时设置截止时间。
- 证据：`backend/src/main/java/com/analyzercoder/domain/indexing/IndexJobStatus.java:4`、`backend/src/main/java/com/analyzercoder/domain/indexing/IndexJob.java:24`、`backend/src/main/java/com/analyzercoder/domain/indexing/IndexJob.java:49`、`backend/src/main/java/com/analyzercoder/domain/indexing/IndexJob.java:113`、`backend/src/main/java/com/analyzercoder/domain/indexing/IndexJob.java:137`、`backend/src/main/java/com/analyzercoder/domain/indexing/IndexJob.java:175`、`backend/src/main/java/com/analyzercoder/domain/indexing/IndexJob.java:196`、`backend/src/main/java/com/analyzercoder/domain/indexing/IndexJob.java:221`

### IDX-013 执行阶段名

- 需求：任务在 `current_step` 中暴露稳定的阶段标识，便于前端展示与排障。
- 规则：
  - 领取排队任务时写入 `scan_repository`；写入片段前写入 `write_chunks`；生成向量前写入 `build_embeddings`。
  - 成功时的完成串为 `<模式小写>:completed:<片段数>[:fallback-<回退原因小写>][:vectors-ready|:vectors-degraded]`。
  - 取消相关步骤为 `canceled`、`cancel_requested`；超时回收写入 `timed_out`，并按任务类型给出 `CODEGRAPH_TIMEOUT` 或 `KNOWLEDGE_DRIFT_TIMEOUT`。
  - 分支准备任务的阶段为 `RESOLVING` → 执行中的具体阶段 → `EMBEDDING`（向量任务）→ `COMPLETED`。
- 证据：`backend/src/main/resources/mappers/IndexJobMapper.xml:67`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobProcessor.java:160`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobProcessor.java:191`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobProcessor.java:202`、`backend/src/main/resources/mappers/IndexJobMapper.xml:87`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:223`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:257`

### IDX-014 Worker 轮询、租约与超时回收

- 需求：后台任务按固定间隔被领取，同一排队任务不会被两个 Worker 同时执行；运行超时按类型回收。
- 规则：
  - 轮询间隔取 `app.indexing.poll-interval-ms`，默认 5000 毫秒。
  - 领取语句使用 `FOR UPDATE SKIP LOCKED` 原子地把 `QUEUED` 任务置为 `RUNNING`。
  - 心跳只在 `RUNNING` / `CANCEL_REQUESTED` 状态下更新，只表示进程仍在处理；固定超时截止时间不会因心跳而延长。
  - 超时回收按任务类型扫描并把 `RUNNING` / `CANCEL_REQUESTED` 置为 `FAILED`。
- 证据：`backend/src/main/java/com/analyzercoder/worker/IndexJobWorker.java:17`、`backend/src/main/resources/application.yml:82`、`backend/src/main/resources/mappers/IndexJobMapper.xml:64`、`backend/src/main/resources/mappers/IndexJobMapper.xml:82`、`backend/src/main/resources/mappers/IndexJobMapper.xml:86`、`backend/src/main/resources/db/migration/V1__init_schema.sql`

### IDX-015 取消与重试

- 需求：用户可以取消排队或运行中的任务；失败任务可以重试，重试不会复用失败记录。
- 规则：
  - 取消是“请求 + 协作式停止”：运行中的任务转为 `CANCEL_REQUESTED`，处理器在三个检查点（开始后、切片后、写向量后）检测并落为 `CANCELED`。
  - 重试只允许 `FAILED` 任务；重试会新建一个同仓库同类型的新排队任务，原失败记录保持终态不变。
  - 若该仓库已有活跃任务（`QUEUED`/`RUNNING`/`CANCEL_REQUESTED`），启动或重试都直接返回该活跃任务，不新建。
  - 若该任务其实是分支准备任务，重试委托给分支准备任务通道。
  - 数据库以唯一索引保证每个仓库最多一个活跃任务。
- 证据：`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobProcessor.java:116`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobProcessor.java:155`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobProcessor.java:197`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobProcessor.java:270`、`backend/src/main/java/com/analyzercoder/domain/indexing/IndexJob.java:42`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobService.java:27`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobService.java:89`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobService.java:100`、`backend/src/main/resources/db/migration/V1__init_schema.sql`

### IDX-016 增量执行计划与全量回退判定

- 需求：`INCREMENTAL` 类型只是一个请求；实际是否真增量由运行期判定，并把判定结果与回退原因记录下来。
- 规则：
  - 非 `INCREMENTAL` 请求一律 `FULL`，无回退原因；找不到已索引基线提交号 → `FULL` + `BASELINE_MISSING`。
  - 工作区存在未提交变更 → `FULL` + `DIRTY_WORKTREE`（此时不做 Git diff）；Git diff 计算抛错 → `FULL` + `GIT_DIFF_FAILED`。
  - 变更文件数 / 当前文件数 > 0.35 → `FULL` + `CHANGE_RATIO_EXCEEDED`；阈值常量为 `MAX_INCREMENTAL_CHANGE_RATIO`。
  - 以上都不触发时才 `INCREMENTAL`，不写回退原因；判定结果写入任务的 `execution_mode` 与 `fallback_reason`，并在完成串中以 `fallback-*` 体现。
  - diff 结果标记为不完整时直接拒绝该次增量，不回退为全量。
- 证据：`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobProcessor.java:32`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobProcessor.java:245`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobProcessor.java:145`、`backend/src/main/java/com/analyzercoder/application/indexing/GitDiffService.java:38`、`backend/src/test/java/com/analyzercoder/application/indexing/IndexJobProcessorTest.java:39`、`backend/src/test/java/com/analyzercoder/application/indexing/IndexJobProcessorTest.java:122`

### IDX-017 重命名与删除的路径处理

- 需求：增量索引必须同时清掉旧路径、写入新路径，删除的文件不得残留片段。
- 规则：
  - Git 差异按 `git diff --name-status` 的 NUL 分隔输出解析，识别 ADDED / MODIFIED / DELETED / RENAMED / COPIED 与 `T`（按修改处理）。
  - 差异路径若为空、绝对路径、盘符路径或含 `..` 段则直接抛错，拒绝使用。
  - `affectedPaths` 收集旧路径与新路径（COPIED 的旧路径不收集）；`indexPaths` 只收集新路径。
  - 仅 `indexPaths` 中的文件会重新切分；`affectedPaths` 用于删除旧片段。
  - 增量写入顺序是：按路径删除 → 重贴未变更片段到新内容版本与提交号 → 批量插入新片段。
  - 片段始终携带 `branch_id`，删除、增量复制和重贴都限制在目标分支内。
- 证据：`backend/src/main/java/com/analyzercoder/application/indexing/GitDiffService.java:59`、`backend/src/main/java/com/analyzercoder/application/indexing/GitDiffService.java:94`、`backend/src/main/java/com/analyzercoder/application/indexing/GitDiffService.java:116`、`backend/src/main/java/com/analyzercoder/application/indexing/GitDiffService.java:127`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobProcessor.java:148`、`backend/src/main/java/com/analyzercoder/infrastructure/chunk/PostgresCodeChunkStore.java:39`、`backend/src/main/resources/mappers/CodeChunkMapper.xml:44`、`backend/src/main/resources/mappers/CodeChunkMapper.xml:48`

### IDX-018 片段写入必须绑定内容版本与提交号

- 需求：任何片段都必须绑定其生成的内容版本（contentVersion）与提交号（commitSha），新旧内容版本不得混用。
- 规则：
  - 领域对象强制要求 `contentVersion`、`commitSha`、`filePath`、`chunkType`、`content`、`contentHash`、`createdAt`、`assetType` 非空。
  - `commitSha` 为空或空白时被替换为字面量 `unknown`，不报错。
  - 默认链路在开始执行时就要求仓库已有 `currentContentVersion`，否则任务失败。
  - 片段使用仓库当前的内容版本与提交号构造；增量写入也显式传入这两个值。
  - 分支内容索引要求来源仓库内容版本与阅读上下文内容版本、内容路径三者一致，否则拒绝执行。
  - 数据库触发器禁止改写或删除属于分支内容版本的片段。
- 证据：`backend/src/main/java/com/analyzercoder/domain/chunk/CodeChunk.java:104`、`backend/src/main/java/com/analyzercoder/domain/chunk/CodeChunk.java:123`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobProcessor.java:127`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobProcessor.java:163`、`backend/src/main/java/com/analyzercoder/application/branch/BranchContentIndexService.java:39`、`backend/src/main/resources/db/migration/V1__init_schema.sql`

### IDX-019 片段内容摘要

- 需求：每个片段记录正文的 SHA-256 摘要，用于向量复用判定、知识绑定失效判定与来源版本比对。
- 规则：
  - 摘要在构造片段时按 UTF-8 计算 SHA-256 十六进制字符串。
  - 摘要与正文同源写入，不单独接口修改。
  - 数据库层面 `content_hash` 非空。
- 证据：`backend/src/main/java/com/analyzercoder/domain/chunk/CodeChunk.java:119`、`backend/src/main/java/com/analyzercoder/domain/chunk/CodeChunk.java:136`、`backend/src/main/resources/db/migration/V1__init_schema.sql`

### IDX-020 向量模型配置：模型、维度与检索能力

- 需求：向量模型是系统级单例配置，包含协议、模型标识、维度与检索能力；启用前必须通过连通性探测。
- 规则：
  - 协议只支持 `LOCAL_HASH` 与 `OPENAI_COMPATIBLE`（数据库 CHECK 约束）；模型标识全局唯一；维度范围 1–4096，其中 `LOCAL_HASH` 固定 64 维，违反即报 `VECTOR_DIMENSION_INCOMPATIBLE`。
  - 检索能力由协议推导：`LOCAL_HASH` → `CHARACTER_HASH`，其余 → `SEMANTIC_EMBEDDING`。这是“字符相似度”与“语义检索”的唯一判定依据。
  - 外部模型必须配置加密保存的 API Key 与基础地址；请求超时范围 3000–120000 毫秒，默认 30000。
  - 内置默认模型为 `local-hash-64`（LOCAL_HASH，64 维），并在迁移中写成初始启用项；无启用记录时代码回退同一组默认值（模型名 `local-hash-64`、维度 64、能力 `CHARACTER_HASH`）。
  - 启用前先探测：`LOCAL_HASH` 直接视为可用；外部模型发送一次 `connection probe` 嵌入请求，失败则拒绝启用并返回错误码。启用使用乐观并发，`expectedActivationVersion` 不匹配返回 `VECTOR_MODEL_ACTIVATION_CONFLICT`。
  - 当前已启用的模型不允许直接编辑，必须先切换。
- 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql`、`backend/src/main/resources/db/migration/V1__init_schema.sql`、`backend/src/main/resources/db/migration/V1__init_schema.sql`、`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:671`、`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:223`、`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:250`、`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:294`、`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:764`、`backend/src/main/resources/mappers/LlmSettingsMapper.xml:177`

### IDX-021 向量记录表与维度约束

- 需求：字符向量与语义向量统一存放在向量表中，并记录生成时的模型、维度与检索能力。
- 规则：
  - 实际表名是 `chunk_embeddings`（片段向量）与 `knowledge_card_embeddings`（知识卡向量），不是 `code_chunk_embeddings`。
  - `chunk_embeddings` 以 `chunk_id` 为主键，一个片段最多一条向量；`knowledge_card_embeddings` 以 `card_id` 为主键，并记录其对应的知识修订号。
  - 两表都约束维度在 1–4096 且 `vector_dims(embedding) = dimension`；都有 `retrieval_capability` 列，非空，取值只能是 `CHARACTER_HASH` 或 `SEMANTIC_EMBEDDING`。
  - 写入为 upsert：命中主键则覆盖模型、维度、能力、向量与摘要并刷新 `created_at`。
  - 未为向量列建立 HNSW/IVFFlat 近邻索引；迁移注释说明原因是矢量维度可变，因此使用精确余弦扫描。
- 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql`、`backend/src/main/resources/db/migration/V1__init_schema.sql`、`backend/src/main/resources/db/migration/V1__init_schema.sql`、`backend/src/main/resources/db/migration/V1__init_schema.sql`、`backend/src/main/resources/db/migration/V1__init_schema.sql`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:445`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:419`、`backend/src/main/resources/db/migration/V1__init_schema.sql`

### IDX-022 缺失向量的识别与补建

- 需求：系统能识别“缺失/过期”的向量记录，并在索引任务或分支向量任务中补齐。
- 规则：
  - 片段侧“需要补建”的条件：无记录，或 `content_hash` 与片段不一致，或模型、维度、检索能力任一与当前启用模型不一致；知识卡侧条件类似，但以修订号代替 `content_hash`。
  - 默认链路补建入口：`prepareRepositoryEmbeddings`，内部顺序为重建启发式调用关系 → 补片段向量 → 补知识向量；任一环节抛异常则整体返回 `false`（不向上抛），索引任务据此把完成串标为 `:vectors-degraded`。
  - 分支链路补建入口：`prepareBranchEmbeddings(repositoryId, contentVersion, checkpoint)`，内容版本为空直接拒绝；只处理该内容版本自己的片段。
  - 分支向量任务要求先完成该内容版本的内容索引，否则拒绝入队；同一分支同一内容版本只允许一个向量任务，目标内容版本不同则返回冲突。
  - 内置字符向量在索引任务内直接计算；外部语义向量按最多 16 条片段组成一批调用 `/embeddings`，并在一次补建中复用模型配置、密钥和 HTTP 客户端。兼容服务拒绝批量输入时，当前补建自动改为逐条调用。
  - 知识卡向量只在“转为 PUBLISHED”这一步顺带补齐；该补齐失败被吞掉，已发布知识仍可通过关键词通道检索。
- 证据：`backend/src/main/resources/mappers/IntelligenceMapper.xml:428`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:412`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:446`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:905`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:115`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:181`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1265`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobProcessor.java:209`

### IDX-023 同内容摘要复用向量（避免重复调用模型）

- 需求：同一仓库内正文摘要相同、且模型/维度/能力一致的片段向量可以被复用，不重复调用嵌入模型。
- 规则：
  - 复用查询条件：`repo_id` + `content_hash` + `model` + `dimension` + `retrieval_capability` 全部一致，取任意一条。
  - 复用只发生在分支向量补建路径（有明确 contentVersion 时）；默认链路不查复用表。
  - 复用命中时直接写 upsert，不调用模型。
  - 数据库为该复用查询建立了 `(repo_id, content_hash, model, dimension, retrieval_capability)` 索引。
- 证据：`backend/src/main/resources/mappers/IntelligenceMapper.xml:452`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:920`、`backend/src/main/resources/db/migration/V1__init_schema.sql`、`backend/src/test/java/com/analyzercoder/application/intelligence/IntelligenceServiceMultiTurnTest.java:71`

### IDX-024 模型切换后旧向量不得混用

- 需求：切换向量模型后，检索与覆盖统计只能看到当前模型/维度/能力下的向量，旧向量不得参与命中。
- 规则：
  - 检索 SQL 强制 `e.model = 当前启用模型` 且 `e.dimension = 当前启用维度`。
  - 覆盖统计的“已向量化”判定额外要求 `retrieval_capability = 当前启用能力`，并加入 `content_hash` 一致条件。
  - 因此切换模型后，旧向量在统计上直接表现为“缺失”，由补建流程逐步替换。
  - 当前启用模型不允许就地编辑，必须先切换，避免模型标识与已写入向量不一致。
- 证据：`backend/src/main/resources/mappers/IntelligenceMapper.xml:110`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:518`、`backend/src/main/resources/mappers/VectorIndexQueryMapper.xml:17`、`backend/src/main/resources/mappers/VectorIndexQueryMapper.xml:4`、`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:204`

### IDX-025 向量覆盖统计接口

- 需求：按仓库给出当前默认版本的向量覆盖情况，并能下钻到每个片段、每张知识卡的向量状态。
- 规则：
  - 统计范围固定为 `repositories.current_content_version` 指向的片段，以及“有效知识”集合内的知识卡。
  - 汇总字段：内容版本标识、提交号、片段总数、已向量化片段数、缺失片段数、知识卡总数、已向量化知识卡数、当前模型、维度、检索能力与中文能力标签、最近向量时间。
  - 片段列表支持按关键词（文件路径/符号名/正文，忽略大小写）、按状态 `EMBEDDED`/`MISSING`、按片段类型过滤；其它状态值直接报错。
  - 列表按“缺失优先”排序，便于定位待补建记录；分页由 PageHelper 提供。
  - 列表只返回正文前 260 个字符的摘要（空白压缩为单空格），不返回完整正文。
- 证据：`backend/src/main/resources/mappers/VectorIndexQueryMapper.xml:55`、`backend/src/main/resources/mappers/VectorIndexQueryMapper.xml:78`、`backend/src/main/resources/mappers/VectorIndexQueryMapper.xml:84`、`backend/src/main/resources/mappers/VectorIndexQueryMapper.xml:88`、`backend/src/main/resources/mappers/VectorIndexQueryMapper.xml:106`、`backend/src/main/java/com/analyzercoder/application/indexing/VectorIndexQueryService.java:22`、`backend/src/main/java/com/analyzercoder/application/indexing/VectorIndexQueryService.java:51`、`backend/src/main/java/com/analyzercoder/application/indexing/VectorIndexQueryService.java:114`、`backend/src/main/java/com/analyzercoder/application/indexing/VectorIndexQueryService.java:124`

### IDX-026 搜索不得隐式补建向量

- 需求：检索请求只读，不得在请求线程上重建语料向量。
- 规则：
  - 检索路径只调用一次“把查询文本向量化”，不查询缺失向量、不写向量表。
  - 代码中有明确注释：索引构建属于后台任务，查询不得重建语料。
  - 该约束由单元测试固定：检索后不得调用 `missingEmbeddings`、`missingKnowledgeEmbeddings`、`upsertEmbedding`。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:558`、`backend/src/test/java/com/analyzercoder/application/intelligence/IntelligenceServiceMultiTurnTest.java:55`

### IDX-027 字符哈希向量与语义向量必须区分

- 需求：不得把字符相似度包装成“语义理解”；两者在数据、标签与降级语义上必须可分辨。
- 规则：
  - `LOCAL_HASH` 模型不返回真实向量，检索时改用内置的 64 维字符三元组哈希投影（对文本按 3 字符窗口取 `hashCode`，按符号投影到 64 维后做 L2 归一化）。
  - 该降级向量的 `retrieval_capability` 记为 `CHARACTER_HASH`，绝不记为 `SEMANTIC_EMBEDDING`。
  - 通道命名随之区分：字符能力走 `*_CHARACTER_SIMILARITY`，语义能力走 `*_SEMANTIC`。
  - 结果中的 `similarityKind` 由通道名推导：含 `_SEMANTIC` → `SEMANTIC_EMBEDDING`；含 `_CHARACTER_SIMILARITY` → `CHARACTER_HASH`；否则 `NONE`。
  - 配置界面给出的能力限制文案：字符相似度“基于字符哈希投影与余弦距离”“不理解同义词、业务含义或代码语义”；语义能力“取决于外部 embedding 模型及其训练覆盖”。
  - 迁移注释与列注释明确：只有 `SEMANTIC_EMBEDDING` 才表示外部模型语义向量。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1526`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:567`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1562`、`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:772`、`backend/src/main/resources/db/migration/V1__init_schema.sql`、`backend/src/main/resources/db/migration/V1__init_schema.sql`、`backend/src/test/java/com/analyzercoder/application/intelligence/IntelligenceServiceMultiTurnTest.java:292`

### IDX-028 索引阶段构建启发式调用关系

- 需求：索引阶段额外生成“符号名 + 左括号”字符串匹配的调用候选，供检索的图谱相关通道使用；它不是静态分析结果。
- 规则：
  - 构建时机是在向量补建链路中（`prepareRepositoryEmbeddings` 的第一步），检索时绝不重建。
  - 先删除该仓库当前内容版本的旧边，再遍历当前内容版本内有符号名的片段。
  - 对每个片段内容，若包含“其它符号名 + `(`”，则写入一条 `CALLS` 边。
  - 自指边不写入；同名符号只保留首次出现的片段作为目标。
  - 该关系的能力与限制在接口层显式声明：算法标识 `SYMBOL_TOKEN_FOLLOWED_BY_PARENTHESIS`，并声明“不是 CodeGraph CLI 结果”“无法可靠识别重载、动态分派、反射、别名和跨语言调用”。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1273`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:446`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:287`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:306`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1016`

### IDX-029 分支内容索引与 Markdown 知识源同步

- 需求：分支内容索引完成后同步该内容版本的 Markdown 知识源清单，但只有“该内容版本确实是分支当前发布内容版本”时才做，避免过期任务覆盖新分支来源。
- 规则：
  - 同步条件：该分支的 `content_version` 等于本次索引的内部内容令牌。
  - 内容索引完成时间写入 `repository_branches.content_indexed_at`；该字段非空即视为“已索引”，重复任务直接短路返回。
  - 固定分支工作区提供当前提交的变化路径清单；存在上一个已索引版本时，数据库直接复制未变化路径的片段，只读取并解析新增或修改文件，删除路径不再复制。
  - Markdown 来源仍扫描完整 Markdown 清单，但不会读取其它未变化源码文件，防止删除或改名的文档残留。
  - 默认链路完成写入后也会顺带把仓库 `current_content_version` 对应分支内容版本的 `content_indexed_at` 置为当前时间（兼容旧默认版本索引）。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchContentIndexService.java:136`、`backend/src/main/java/com/analyzercoder/application/branch/BranchContentIndexService.java:146`、`backend/src/main/java/com/analyzercoder/application/branch/BranchContentIndexService.java:154`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobProcessor.java:177`

### IDX-030 相关配置项与默认值

- 需求：索引与向量相关配置集中在 `application.yml` 的 `app.indexing` 与 `app.llm` 两段；片段窗口、重叠、符号上限等目前是代码常量而非配置项。
- 规则：
  - `app.indexing.poll-interval-ms` 默认 5000（毫秒）、`app.indexing.max-file-bytes` 默认 524288（字节），两者均无环境变量占位符；代码侧 `@Value` 默认值同为 524288。
  - `app.llm.master-key` 无默认值、必填，用于加密模型 API Key；`app.llm.allow-insecure-local` 默认 false，`app.llm.endpoint-exceptions` 默认空列表。
  - `app.llm.connectivity-timeout-seconds` 默认 15（秒）；`app.llm.breaker-failure-threshold` 默认 3（连续失败熔断阈值）。
  - 向量模型标识、维度、检索能力**不在** `application.yml` 中，全部来自数据库表。
  - 相邻但不同域的上限（分支工作区与源码预览）：`app.repository.workspace-max-files` 默认 50000、`workspace-max-total-bytes` 默认 2147483648、`browser-max-file-bytes` 默认 2097152。
- 证据：`backend/src/main/resources/application.yml:80`、`backend/src/main/resources/application.yml:95`、`backend/src/main/resources/application.yml:75`、`backend/src/main/resources/application.yml:79`、`backend/src/main/java/com/analyzercoder/infrastructure/indexing/FileSystemRepositoryScanner.java:84`

### IDX-031 前端：索引任务与向量索引界面

- 需求：管理端可查看索引任务并执行取消/重试，可查看当前向量索引覆盖并按类型/状态筛选。
- 规则：
  - 索引任务页分三个页签：“分支任务”“其他任务”“当前向量索引”；后两个仅超级管理员可见，非管理员默认落在“分支任务”。
  - 任务表支持选中查看详情，详情显示请求类型、实际模式、回退原因、阶段、创建/开始/心跳/超时/结束时间。
  - 详情对可取消状态（`QUEUED`、`RUNNING`）显示取消按钮，对 `FAILED` 显示重试按钮；`CANCEL_REQUESTED` 显示“将在下一个安全检查点停止”的提示；终态任务提示不会被改写。
  - 回退原因在前端有中文映射：`BASELINE_MISSING`、`GIT_DIFF_FAILED`、`DIRTY_WORKTREE`、`CHANGE_RATIO_EXCEEDED`，未知原因原样展示。
  - 向量索引面板只有在选中仓库后才渲染表格；提供“代码分块 / 知识卡片”两个数据源页签并显示覆盖率（已向量化/总数）。
  - 向量筛选条件：关键词、状态（已向量化/缺失）、片段类型（文件/符号/文档/测试/配置，后者当前后端不会产出，见 IDX-006）。
  - 汇总卡片展示片段总数、已生成向量、缺失向量、能力标签与模型/维度，并在能力为 `CHARACTER_HASH` 时明确提示“字符哈希不理解同义词或代码语义”。
  - 表格展示文件与符号、行范围、片段类型、送入向量的内容摘要、检索能力与向量状态；点击代码行跳转到源码检索页并携带 `path`/`startLine`/`endLine`。
- 证据：`frontend/src/views/UnifiedIndexJobsView.vue:17`、`frontend/src/views/UnifiedIndexJobsView.vue:77`、`frontend/src/features/indexing/UnifiedIndexJobDetail.vue:10`、`frontend/src/features/indexing/UnifiedIndexJobDetail.vue:40`、`frontend/src/features/indexing/CurrentVectorIndexPanel.vue:14`、`frontend/src/features/indexing/CurrentVectorIndexPanel.vue:53`、`frontend/src/features/indexing/CurrentVectorIndexPanel.vue:74`、`frontend/src/features/indexing/CurrentVectorIndexPanel.vue:88`、`frontend/src/features/indexing/CurrentVectorIndexPanel.vue:33`、`frontend/src/features/indexing/VectorIndexSummary.vue:24`、`frontend/src/features/indexing/VectorIndexSummary.vue:39`、`frontend/src/features/indexing/VectorIndexTable.vue:34`

## 3 数据与状态

### 3.1 主要数据表

- `code_chunks`：片段表。字段含 `repo_id`、`content_version`、`commit_sha`、`file_path`、`symbol_id/name/kind`、`language`、`chunk_type`、`asset_type`、`start_line`、`end_line`、`content`、`content_hash`、`created_at`；`content_version` 与 `commit_sha` 非空，`asset_type` 受 CHECK 约束。证据：`backend/src/main/resources/db/migration/V1__init_schema.sql`
- `chunk_embeddings` / `knowledge_card_embeddings`：片段向量与知识卡向量，主键分别为 `chunk_id`、`card_id`，均含 `model`、`dimension`、`embedding`、`content_hash`、`retrieval_capability`、`created_at`（知识卡另含 `revision`）。证据：`backend/src/main/resources/db/migration/V1__init_schema.sql`、`backend/src/main/resources/db/migration/V1__init_schema.sql`
- `vector_model_configs` / `vector_model_activation`：向量模型备案（协议、模型标识、维度、超时、密钥版本）与单例启用记录（含 `activation_version`）。证据：`backend/src/main/resources/db/migration/V1__init_schema.sql`、`backend/src/main/resources/db/migration/V1__init_schema.sql`
- `index_jobs`：任务表，含 `job_type`、`status`、`current_step`、`execution_mode`、`fallback_reason`、`failure_code`、`error_message`、`started_at`、`heartbeat_at`、`timeout_at`、`finished_at`。证据：`backend/src/main/resources/db/migration/V1__init_schema.sql`、`backend/src/main/resources/db/migration/V1__init_schema.sql`
- `heuristic_call_edges`：索引阶段生成的启发式调用候选。证据：`backend/src/main/resources/mappers/IntelligenceMapper.xml:306`
- `repository_branches` / `branch_read_contexts`：分支当前工作区与阅读上下文。证据：`backend/src/main/resources/db/migration/V1__init_schema.sql`

### 3.2 状态与枚举

- 任务状态：`QUEUED`、`RUNNING`、`CANCEL_REQUESTED`、`SUCCEEDED`、`FAILED`、`CANCELED`；任务类型：`FULL`、`INCREMENTAL`、`CODEGRAPH`、`KNOWLEDGE_DRIFT`。证据：`backend/src/main/java/com/analyzercoder/domain/indexing/IndexJobStatus.java:4`、`backend/src/main/java/com/analyzercoder/domain/indexing/IndexJobType.java:4`
- 向量状态（统计接口视角）：`EMBEDDED`、`MISSING`；检索能力：`CHARACTER_HASH`、`SEMANTIC_EMBEDDING`。证据：`backend/src/main/java/com/analyzercoder/application/indexing/VectorIndexQueryService.java:114`、`backend/src/main/resources/db/migration/V1__init_schema.sql`
- 片段类型：`FILE`、`SYMBOL`、`DOC_SECTION`、`TEST_CASE`、`CONFIG`、`KNOWLEDGE_CARD`（后两者中 `TEST_CASE`、`KNOWLEDGE_CARD` 当前不会被写入）；资产类型：`CODE`、`DOCUMENT`、`RULE`、`TASK`、`CONFIG`。证据：`backend/src/main/java/com/analyzercoder/domain/chunk/ChunkType.java:4`、`backend/src/main/java/com/analyzercoder/domain/indexing/RepositoryAssetType.java:4`

### 3.3 内容版本语义

- `repositories.current_content_version` 是仓库的**默认版本令牌**，不是当前阅读分支的内容版本；在多分支下二者不相等。
- 分支模式使用阅读上下文携带的 `contentVersion` 与 `commitSha`（`branch_read_contexts` 解析结果）。证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchReadContext.java:9`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:347`
- 因此“默认模式”的片段范围由 `repositories.current_content_version` 决定，“分支模式”的片段范围由阅读上下文内容版本决定，两者互不混用。

## 4 接口清单

索引任务：

| 方法 | 路径 | 用途 | 所需权限 |
| --- | --- | --- | --- |
| POST | `/api/repositories/{repositoryId}/index` | 启动内容索引任务，body 可指定 `FULL` 或 `INCREMENTAL`（默认 `FULL`） | `MAINTAIN` |
| GET | `/api/repositories/{repositoryId}/index/status` | 查询该仓库最近一次索引任务 | `READ` |
| GET | `/api/index-jobs/page` | 分页查询当前账号可见的索引任务 | 超级管理员（`SecurityContext.requireAdmin`） |
| GET | `/api/index-jobs` | 列出当前账号可见的索引任务 | 超级管理员 |
| GET | `/api/index-jobs/{jobId}` | 查询单个任务 | 该仓库 `READ` |
| GET | `/api/repositories/{repositoryId}/index-jobs` | 列出该仓库的索引任务 | `READ` |
| POST | `/api/index-jobs/{jobId}/cancel` | 请求取消任务 | 该仓库 `MAINTAIN` |
| POST | `/api/index-jobs/{jobId}/retries` | 重试失败任务（新建任务） | 该仓库 `MAINTAIN` |
| POST | `/api/repositories/{repositoryId}/branches/{branchId}/code-jobs` | 启动分支代码任务，`kind` 为 `SYNC`/`PREPARE`/`CONTENT`/`GRAPH` | `MAINTAIN` |
| GET | `/api/repositories/{repositoryId}/branch-index-statuses` | 列出各分支的内容/图谱/向量就绪状态 | `READ` |
| GET | `/api/repositories/{repositoryId}/branches/{branchId}/index-status` | 查询指定上下文的索引就绪状态，需 `contextId` | `READ` |
| POST | `/api/repositories/{repositoryId}/branch-vector-jobs` | 为指定阅读上下文内容版本启动向量补建，body 需 `contextId` | `MAINTAIN` |

证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/IndexController.java:43`、`backend/src/main/java/com/analyzercoder/interfaces/rest/IndexController.java:57`、`backend/src/main/java/com/analyzercoder/interfaces/rest/IndexController.java:64`、`backend/src/main/java/com/analyzercoder/interfaces/rest/IndexController.java:81`、`backend/src/main/java/com/analyzercoder/interfaces/rest/IndexController.java:93`、`backend/src/main/java/com/analyzercoder/interfaces/rest/IndexController.java:101`、`backend/src/main/java/com/analyzercoder/interfaces/rest/IndexController.java:111`、`backend/src/main/java/com/analyzercoder/interfaces/rest/BranchCodeOperationsController.java:36`、`backend/src/main/java/com/analyzercoder/interfaces/rest/BranchCodeOperationsController.java:42`、`backend/src/main/java/com/analyzercoder/interfaces/rest/BranchCodeOperationsController.java:52`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:96`

片段与向量：

| 方法 | 路径 | 用途 | 所需权限 |
| --- | --- | --- | --- |
| GET | `/api/repositories/{repositoryId}/chunks` | 列出/检索当前默认内容版本的片段，`limit` 1–200，默认 50 | `READ` |
| GET | `/api/repositories/{repositoryId}/vector-index/summary` | 向量覆盖汇总 | `READ` |
| GET | `/api/repositories/{repositoryId}/vector-index/chunks` | 片段向量覆盖分页列表，支持 `q`/`status`/`chunkType` | `READ` |
| GET | `/api/repositories/{repositoryId}/vector-index/knowledge` | 知识卡向量覆盖分页列表，支持 `q`/`status` | `READ` |
| GET | `/api/settings/llm/vector-models` | 列出向量模型配置 | 超级管理员 |
| POST | `/api/settings/llm/vector-models` | 新建向量模型 | 超级管理员 |
| PUT | `/api/settings/llm/vector-models/{id}` | 编辑未启用的向量模型 | 超级管理员 |
| POST | `/api/settings/llm/vector-models/{id}/activate` | 启用模型，需 `expectedActivationVersion` | 超级管理员 |
| POST | `/api/settings/llm/vector-models/{id}/check` | 探测模型可用性 | 超级管理员 |

证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/ChunkController.java:34`、`backend/src/main/java/com/analyzercoder/application/chunk/CodeChunkQueryService.java:14`、`backend/src/main/java/com/analyzercoder/interfaces/rest/VectorIndexController.java:22`、`backend/src/main/java/com/analyzercoder/interfaces/rest/LlmSettingsController.java:19`、`backend/src/main/java/com/analyzercoder/interfaces/rest/LlmSettingsController.java:61`、`backend/src/main/java/com/analyzercoder/interfaces/rest/LlmSettingsController.java:83`

权限级别与角色：`RepositoryPermission` 只有 `READ`、`MAINTAIN`、`MANAGE` 三级，`includes` 用序数比较；仓库所有者（owner）在 `canAccess` 中直接放行，另有仅所有者动作 `requireOwner`；角色 `AccountRole` 为 `SUPER_ADMIN` 与普通用户。证据：`backend/src/main/java/com/analyzercoder/security/RepositoryPermission.java:4`、`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:19`、`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:46`

## 5 边界与非目标

- 切分窗口 120 行 / 重叠 20 行 / 步长 100 行、符号上限 500、片段总数上限 100000、增量比例阈值 0.35 均为代码常量，不可通过配置调整；`app.indexing` 只有轮询间隔与单文件大小两个键。
- 符号抽取是行级正则推断：不解析 AST、不解析调用关系与继承、不区分重载；跨语言调用无法识别。
- 语言覆盖以扫描映射表为准；未在映射表中的扩展名不会被索引，也不会被符号抽取处理。
- 字符哈希向量只是字符三元组哈希投影的余弦相似度，不具备同义词、业务含义与代码语义理解能力；接口层必须以 `CHARACTER_HASH` 呈现，不得表述为语义检索。
- 向量近邻检索为精确余弦扫描，未建立向量索引；数据量增长时检索延迟会线性上升。
- 片段不会被重命名保留身份：重命名按“旧路径删除 + 新路径插入”处理，片段标识会变化。
- 生成代码检测（`generatedCode`）已实现但未接入切分链路，因此生成代码会被正常索引。
- 本域不负责知识卡的生成与发布流程，只负责知识的向量化与覆盖统计；知识卡检索可见性由联合检索域定义。

## 6 已知缺口

- `ChunkType` 声明了 `TEST_CASE` 与 `KNOWLEDGE_CARD`，但没有任何代码路径写入这两个取值；前端片段类型下拉却提供 `TEST_CASE` 选项，属于界面与实现的偏差。证据：`backend/src/main/java/com/analyzercoder/domain/chunk/ChunkType.java:4`、`frontend/src/features/indexing/CurrentVectorIndexPanel.vue:74`
- `CodeSymbolExtractor.generatedCode` 已实现但无调用点，生成代码未被排除；并且单文件符号截断信息（`truncated` / `SYMBOL_COUNT_LIMIT_EXCEEDED`）未写入任何持久化字段或接口响应，截断在调用侧不可见。证据：`backend/src/main/java/com/analyzercoder/application/code/CodeSymbolExtractor.java:137`、`backend/src/main/java/com/analyzercoder/application/code/CodeSymbolExtractor.java:110`
- 缺失向量的补建**没有独立的按缺失重建接口**：默认链路只能通过重新启动 `FULL`/`INCREMENTAL` 索引任务触发，分支链路需先满足“该内容版本已内容索引”再提交分支向量任务；向量索引面板只读，没有“重建缺失向量”按钮。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/VectorIndexController.java:33`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:115`、`frontend/src/features/indexing/CurrentVectorIndexPanel.vue:20`
- 知识卡向量只在“转为 PUBLISHED”时顺带补齐，且失败被静默吞掉；后续若模型切换，知识卡向量同样只在下次索引任务的补建环节被替换，没有针对知识卡的单独补建入口。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1141`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1265`
- 默认链路（`prepareCodeEmbeddings` 无 contentVersion 分支）不做同内容摘要复用，只有分支链路复用；同一内容在默认链路下会重复调用嵌入模型。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:920`
- 索引任务失败后的自动重试策略：代码中只有超时回收（按任务类型）与人工重试，未见指数退避或自动重试；是否存在部署侧的补偿调度需人工确认。
- `index_jobs` 的 `execution_mode` / `fallback_reason` 只在 `RUNNING` 期间写入，排队阶段这两个字段为空；排队阶段前端显示“尚未决策”，符合实现，但若任务在抽样前被取消则不会留下任何执行计划信息。
- 分支内容索引与默认索引是两套切分实现（默认链路按窗口选符号，分支链路同时产出文件片段与截断符号片段），两者对同一文件的片段集合可能不同；是否有意为之需人工确认。
