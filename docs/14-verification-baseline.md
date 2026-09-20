# Analyzer Coder 验证基线与已知缺口
> 本文档由当前实现反推生成（2026-09-19）。描述已实现的需求，不是新设计。

## 1. 范围与统计口径

- 本文记录当前的测试资产、验证命令及其前置条件、本次审计的实测结果、评测门槛、
  已知缺口与未验收项，以及下一步验证优先级。
- 静态资产数量由文件与注解计数得出并给出命令口径；测试用例数一律以实际运行结果为准，
  因为参数化用例（`it.each`）在运行期才展开，静态正则计数会偏小。

## 2. 测试资产清单

### 2.1 后端

- 测试源文件共 80 个：**77 个 `*Test.java` + 3 个 `*IT.java`**
  （统计口径：`backend/src/test/java` 下递归匹配 `*Test.java` 与 `*IT.java`）。
- 集成测试类共 3 个，全部为 `*IT`：
  - `backend/src/test/java/com/analyzercoder/infrastructure/persistence/PostgresMyBatisContextIT.java`
  - `backend/src/test/java/com/analyzercoder/integration/RepositoryHttpWorkflowIT.java`
  - `backend/src/test/java/com/analyzercoder/integration/RepositoryImportToAskIT.java`
- 后端没有 `src/test/resources` 目录，也没有任何测试用 `application*.yml`/`.properties`；
  唯一的 `application*.yml` 是 `backend/src/main/resources/application.yml`。
  仓库中不含 H2 依赖（`backend/pom.xml` 无 `h2`），Postgres 驱动见 `backend/pom.xml:51-52`。
- `backend/pom.xml:93-100` 的 `<build><plugins>` 只配置了 `spring-boot-maven-plugin`，
  **没有 Surefire/Failsafe 定制**。因此默认 Surefire 包含规则不会拾取 `*IT.java`，
  普通 `mvn test` 不会运行它们，必须显式指定（见第 3 节）。

### 2.2 条件门控测试（全部为类级注解，共 7 处）

| 测试类 | 门控注解 | 环境变量 / 条件 | 来源 |
| --- | --- | --- | --- |
| `RepositoryImportToAskIT` | `@EnabledIfEnvironmentVariable` | `APP_RUN_POSTGRES_IT` = `true` | backend/src/test/java/com/analyzercoder/integration/RepositoryImportToAskIT.java:28 |
| `RepositoryHttpWorkflowIT` | `@EnabledIfEnvironmentVariable` | `APP_RUN_POSTGRES_IT` = `true` | .../integration/RepositoryHttpWorkflowIT.java:27 |
| `PostgresMyBatisContextIT` | `@EnabledIfEnvironmentVariable` | `APP_RUN_POSTGRES_IT` = `true` | .../infrastructure/persistence/PostgresMyBatisContextIT.java:39 |
| `BranchIsolationDatabaseTest` | `@EnabledIfEnvironmentVariable` | `APP_BRANCH_JDBC_URL` 匹配 `.+` | .../infrastructure/persistence/BranchIsolationDatabaseTest.java:26 |
| `BranchCodeOperationsDatabaseTest` | `@EnabledIfEnvironmentVariable` | `APP_BRANCH_OPERATIONS_TEST_URL` 匹配 `.+` | .../application/branch/BranchCodeOperationsDatabaseTest.java:49 |
| `BranchPreparationJobsTest` | `@EnabledIfEnvironmentVariable` | `APP_BRANCH_JOB_TEST_URL` 匹配 `.+` | .../application/branch/BranchPreparationJobsTest.java:29 |
| `ManagedCodeGraphCliContractTest` | `@EnabledOnOs` | `OS.LINUX` | .../application/intelligence/ManagedCodeGraphCliContractTest.java:24 |

- 三个分支数据库类还读取配套的 `_USER`/`_PASSWORD` 变量：
  `BranchIsolationDatabaseTest.java:32-34`、`BranchCodeOperationsDatabaseTest.java:55-57`、
  `BranchPreparationJobsTest.java:33-35`。

### 2.3 前端

- 规格文件共 **36 个**，全部形如 `frontend/src/**/*.spec.ts`；
  不存在 `*.test.ts` / `*.test.tsx`（统计口径：递归匹配 `*.spec.ts` 与 `*.test.ts`）。
- Vitest 配置在 `frontend/vite.config.ts`（不存在独立的 `vitest.config.*`）：
  `frontend/vite.config.ts:3` 从 `vitest/config` 导入 `defineConfig`，
  `:25-28` 声明 `test: { environment: 'jsdom', include: ['src/**/*.spec.ts'] }`。
  **没有 setup 文件**：`vite.config.ts:25-28` 未声明 `setupFiles`，仓库中也没有 `*setup*` 测试文件。
- 运行脚本 `frontend/package.json:8` 为 `"test": "vitest run"`；
  `frontend/package.json:9` 为 `"build": "vue-tsc --noEmit && vite build"`；
  vitest 版本 `^4.1.11`（frontend/package.json:31），vue-tsc `^2.2.0`（frontend/package.json:32）。
- 静态计数：36 个规格文件中 `it(` 调用点为 **120 处**，`describe(` 为 22 处，`test(` 为 0 处。
  其中 3 处是运行时才展开的参数化用例，静态正则不会计入：
  `frontend/src/features/graph/atlasFileType.spec.ts:7`（16 行表格）、
  `frontend/src/features/graph/atlasLayout.spec.ts:12`（`[1, 6, 40, 120]` 共 4 例）、
  `frontend/src/features/graph/atlasLayout3d.spec.ts:8`（`[1, 6, 120]` 共 3 例）。
  因此静态计数（120）小于 vitest 实际运行的用例数（143，见第 4 节）。
- **不存在浏览器端到端测试**：仓库自身源码与配置中没有任何 Playwright 或 Cypress 配置、
  也没有 `*.e2e.ts`。工作区中仅有的 `cypress.config.ts` 与 `cypress/support/e2e.ts` 位于
  `runtime/data/repositories/.../witboot-web/`，属于被索引的第三方样例仓库数据，不是本项目测试资产。
  `frontend/package-lock.json:3065,3085` 中的 `@vitest/browser-playwright` 只是 vitest 的
  可选 peer 记录，`frontend/package.json` 的依赖中没有 playwright/cypress。

### 2.4 mcp-server 与脚本

- `mcp-server/test/` 下 2 个测试文件：`api-client.test.mjs`（5 例）、`server.test.mjs`（5 例），共 **10 例**；
  运行方式 `mcp-server/package.json:8` `"test": "node --test test/*.test.mjs"`。
  其他脚本：`"start": "node src/server.mjs"`（:7）、
  `"export:catalog": "node scripts/export-tool-catalog.mjs"`（:9）。
- `scripts/` 下只有 1 个测试文件 `scripts/evaluate-quality.test.mjs`，共 **4 例**（:34、:42、:51、:62）。
- `scripts/check-runtime.mjs` **没有测试文件**：全仓库对文件名 `check-runtime` 的检索只命中
  脚本自身，以及使用说明 `scripts/check-runtime.mjs:6`、
  `.github/workflows/linux-ci.yml:61`、`backend/README.md:44`、`README.md:32`；
  该脚本也不导入 `node:assert`，是只读诊断而非断言测试（见第 6 节缺口 6）。

### 2.5 评测数据集

`evaluation/` 目录内容：

| 文件/目录 | 说明 | 来源 |
| --- | --- | --- |
| `evaluation/manifest.json` | 数据集清单 | evaluation/manifest.json:1-17 |
| `evaluation/retrieval.jsonl` | 检索金标准，47 条 | evaluation/manifest.json:7 |
| `evaluation/qa.jsonl` | 问答金标准，26 条 | evaluation/manifest.json:8 |
| `evaluation/thresholds.json` | 4 个阈值 | evaluation/thresholds.json:1-6 |
| `evaluation/README.md` | 使用说明 | - |
| `evaluation/task-review-results/` | 空目录（0 项） | - |

- `datasetVersion` 为 `3.0.1`，`curatedAt` 为 `2026-09-11`，`curationMethod` 为
  `HUMAN_CURATED_RETRIEVAL_AND_QA`，`repositoryTypes` 为
  `JAVA_SPRING`、`VUE_TYPESCRIPT`、`OPS_AUTOMATION`
  （evaluation/manifest.json:2-5）。
- 维护记录：`updatedAt` 为 `2026-09-15`，移除用例 `RET-029`，更新用例 `RET-044`
  （evaluation/manifest.json:10-16）。
- 条目数与 manifest 一致：`retrieval.jsonl` 47 行、`qa.jsonl` 26 行。
- **`evaluation/results/` 目录不存在**。

## 3. 验证命令清单与各自前置条件

| 命令 | 工作目录 | 前置条件 | 说明 | 来源 |
| --- | --- | --- | --- | --- |
| `mvn -pl backend -am test` | 仓库根 | JDK 17、Maven；无需数据库 | 运行 `*Test` 单测；不拾取 `*IT` | .github/workflows/linux-ci.yml:74-75；backend/pom.xml:93-100 |
| `APP_RUN_POSTGRES_IT=true mvn -pl backend -Dtest='*IT' test` | 仓库根 | 可达的 PostgreSQL 17 + pgvector；`APP_DATASOURCE_URL`/`_USERNAME`/`_PASSWORD`；注意此命令**不带** `-am` | 运行 3 个集成测试 | .github/workflows/linux-ci.yml:77-80；backend/README.md:57 |
| `node node_modules/vitest/vitest.mjs run` | `frontend` | 已安装前端依赖（`npm ci`） | 运行 36 个规格文件 | frontend/package.json:8 |
| `vue-tsc --noEmit` | `frontend` | 已安装前端依赖 | 类型检查，无诊断即通过 | frontend/package.json:9 |
| `vite build` | `frontend` | 已安装前端依赖；本机需能启动 esbuild | 生产构建 | frontend/package.json:9 |
| `npm --prefix mcp-server test` | 仓库根（或 `mcp-server` 内 `npm test`） | Node 20+；已安装 mcp-server 依赖 | 运行 10 例 | mcp-server/package.json:8；.github/workflows/linux-ci.yml:68-72 |
| `node --test scripts/evaluate-quality.test.mjs` | 仓库根 | Node 20+ | 运行 4 例 | .github/workflows/linux-ci.yml:66 |
| `node scripts/evaluate-quality.mjs --validate` | 仓库根 | Node 20+，无外部依赖 | 校验数据集；退出 0 | .github/workflows/linux-ci.yml:65 |
| `node scripts/verify-core.mjs` | 仓库根 | Docker 可用且可拉取 `pgvector/pgvector:pg17` | 一次性运行前端、后端、MCP、脚本全套验证 | scripts/verify-core.mjs:12-31,61-68 |

- `scripts/verify-core.mjs` 会自行启动一次性 `pgvector/pgvector:pg17` 容器
  （`--port` 默认 15439，校验 1024–65535），并随机生成数据库密码、管理员密码与主密钥，
  数据根为 `.runtime/verification/<uuid>`；每条命令超时 600 秒；
  任一失败即置退出码 1，且无论成败都在 `finally` 中删除容器
  （scripts/verify-core.mjs:12-31,35,70-80）。
- 该脚本未被 CI 调用；CI 走的是第 3 节表中的分步命令。

## 4. 本次审计实测结果

以下结果在不受限环境下实测获得，直接采用。

| 验证项 | 结果 |
| --- | --- |
| 前端 vitest | 36 个测试文件 / 143 项测试 / 0 失败 |
| `mvn -pl backend -am test` | Tests run 236, Failures 0, Errors 0, Skipped 5 — BUILD SUCCESS |
| `npm --prefix mcp-server test` | 10 项 / 10 通过 / 0 失败 |
| `node --test scripts/evaluate-quality.test.mjs` | 4 项 / 4 通过 |
| `node scripts/evaluate-quality.mjs --validate` | 退出 0，`valid` 为 true，`datasetVersion` 为 `3.0.1`，`retrieval` 47 / `qa` 26 |
| `vue-tsc --noEmit` | 退出 0，无诊断 |
| `vite build` | 退出 0；存在体积告警：主包 1.63 MB、`CodeAtlas3D` 579 kB |

后端 5 项 skipped 与第 2.2 节的条件门控一致：3 个分支数据库类各 1 例被跳过，
`ManagedCodeGraphCliContractTest` 2 例因非 Linux 被跳过；3 个 `*IT` 类不参与 `mvn test`。

### 4.1 受限沙箱下的失败与环境限制

- 受限沙箱会阻止"以管道 stdio 通信的子进程"，由此产生两类**环境限制而非代码缺陷**的失败：
  - Mockito 自附加失败，导致后端出现 109 errors / 2 failures；
  - esbuild 无法启动，导致前端完全无法运行。
- 因此上述全部验证命令必须在**不受限环境**执行；受限沙箱下的失败不应记为代码回归。

## 5. 评测门槛

`evaluation/thresholds.json` 定义 4 个阈值，判定方向由
`scripts/evaluate-quality.mjs:134` 决定：`p95LatencyMs` 取 `<=`，其余取 `>=`。

| 指标 | 阈值 | 判定方向 | 来源 |
| --- | --- | --- | --- |
| `retrievalRecallAt10` | 0.85 | `>=` | evaluation/thresholds.json:2；scripts/evaluate-quality.mjs:134 |
| `citationCoverageRate` | 0.95 | `>=` | evaluation/thresholds.json:3；scripts/evaluate-quality.mjs:134 |
| `statementSupportRate` | 0.9 | `>=` | evaluation/thresholds.json:4；scripts/evaluate-quality.mjs:134 |
| `p95LatencyMs` | 5000 | `<=` | evaluation/thresholds.json:5；scripts/evaluate-quality.mjs:134 |

- 指标计算方式：`retrievalRecallAt10` 为逐用例召回（只看返回路径前 10 条）的均值；
  `citationCoverageRate` 为 `cited/claims`；`statementSupportRate` 为 `supported/claims`；
  `p95LatencyMs` 为检索与问答延迟合并后的最近秩 p95
  （scripts/evaluate-quality.mjs:88-91,104-129）。
- **`evaluate-quality.mjs` 只能对人工产出的结果 JSON 打分，不能生成结果。**
  它通过 `--results <文件>` 读取一份 JSON（`scripts/evaluate-quality.mjs:145`），
  并要求该文件满足：`datasetVersion` 与金标准一致（:94）、`runId` 非空（:95）、
  `retrieval`/`qa` 数组的 ID 集合与金标准完全一致且不重复（:69-78）、
  QA 的 `assessor` 必须是 `{type:'HUMAN', name, assessedAt}`（:111-113）、
  每条 claim 必须带布尔 `cited` 与 `supported`（:114-115）、`latencyMs` 必须为非负有限数（:122）。
  也就是说，陈述支持度与引用覆盖度依赖具名人工评审产出，无法由脚本自动生成。
- 退出码：`--validate` 成功为 0；`--results` 生成的报告 `passed` 为 false 时为 2
  （`scripts/evaluate-quality.mjs:147`）；任何抛出或校验失败为 1，并打印
  `评测失败：<message>`（:151-153）。
- **仓库中没有生成结果 JSON 的 runner**：全仓库对
  `evaluation/results|release.json|results/release` 的检索只命中三处消费方/文档
  ——`.github/workflows/linux-ci.yml:95`、`:96` 与 `evaluation/README.md:12`。
  `scripts/` 下仅有的写文件调用是 `scripts/evaluate-quality.test.mjs:15-20,26`
  （写入 `os.tmpdir()` 下的临时 fixture）与 `scripts/verify-core.mjs:46`
  （创建 `.runtime/verification/<runId>`），都不产出 `evaluation/results/*`。
- `evaluation/results/` 不存在，因此 `evaluation/results/release.json` 也不存在。

## 6. 已知缺口与未验收项

### 缺口 1：CI 的 tag 门禁检查一个从未生成的文件

- `.github/workflows/linux-ci.yml:95` 为 `test -f evaluation/results/release.json`，
  该步骤的 `if` 条件为 `startsWith(github.ref, 'refs/tags/v')`（:93）。
- 同一工作流的前序步骤（Checkout、Java、Node、脚本校验、评测校验、MCP、后端测试、
  PostgreSQL 集成测试、前端构建、后端打包）都不生成该文件，`:96` 只是消费它，
  其后的"Assemble release artifact"（:98-108）只创建 `release/` 目录并复制产物。
- 结论：任何 `v*` tag 构建都必然在该步骤因 `test -f` 失败而中断。

### 缺口 2：三个分支数据库测试的门控环境变量全仓库无人设置

- `BranchIsolationDatabaseTest` 需要 `APP_BRANCH_JDBC_URL`（:26,32），
  `BranchCodeOperationsDatabaseTest` 需要 `APP_BRANCH_OPERATIONS_TEST_URL`（:49,55），
  `BranchPreparationJobsTest` 需要 `APP_BRANCH_JOB_TEST_URL`（:29,33）。
- 全仓库检索（含 `scripts/`、`.github/workflows/` 与所有 `.env*` 文件）显示这三个变量
  只出现在上述测试类自身，没有任何 CI 步骤、工作流、脚本或环境模板设置它们。
- 对照：`APP_RUN_POSTGRES_IT` 被设置在 `.github/workflows/linux-ci.yml:79` 与
  `scripts/verify-core.mjs:22`。
- 结论：这三个类永远不会执行，属长期未运行的死测试。它们也是实测 5 项 skipped 中的 3 项。

### 缺口 3：`GET /api/repositories/{id}/chunks` 不解析分支上下文

- `ChunkController` 只有 `CodeChunkQueryService` 与 `AccessControlService` 两个依赖，
  方法签名中没有 `BranchRequestContext`，因此不会读取 `X-Branch-Context`
  （`backend/src/main/java/com/analyzercoder/interfaces/rest/ChunkController.java:26-44`）。
- `CodeChunkMapper.xml` 的 `currentSnapshot` 片段把查询固定在
  `repositories.current_snapshot_id`（默认版本）
  （`backend/src/main/resources/mappers/CodeChunkMapper.xml:37-41`）。
- `BranchContextInterceptor` 的 GET 白名单只收录了 `/chunks/{uuid}/graph-target`，
  没有 `/chunks`，因此即便前端给它带上 `X-Branch-Context` 也会被拒绝为 409
  `BRANCH_CONTEXT_UNSUPPORTED`
  （`backend/src/main/java/com/analyzercoder/security/BranchContextInterceptor.java:13-24,40-43`）。
- 前端知识卡的代码关联选择器走的正是该接口：
  `frontend/src/features/knowledge/KnowledgeCodeReferenceSelector.vue:41` 调用
  `listChunks`，`frontend/src/features/knowledge/KnowledgeCardEditorDialog.vue:88` 同样调用它；
  而 `listChunks` 不附加分支头
  （`frontend/src/api/repositories.ts:209-219`）。
- 结论：在非默认分支上编辑知识卡时，代码关联候选或绑定到默认分支的代码，或检索不到候选。

### 缺口 4：`mcp-tools.json` 与 stdio 适配器的 schema/描述不一致，且无法复现、CI 不校验

- `export-tool-catalog.mjs` 从 `mcp-server/src/server.mjs` 的注册信息生成目录文件
  （`mcp-server/scripts/export-tool-catalog.mjs:7-11`），即产物应与 `server.mjs` 的
  `title`/`description` 完全一致。
- 实际提交的 `backend/src/main/resources/mcp-tools.json` 与之不符，可确认的差异包括：
  - `resolve_project_context` 的 `title`：目录为 `Resolve branch context`
    （backend/src/main/resources/mcp-tools.json:53），而源码为
    `'Resolve project branch context'`（mcp-server/src/server.mjs:47）。
  - `codegraph_affected` 的 `title`：目录为 `Analyze affected symbols`
    （mcp-tools.json:470），而源码为 `'Analyze affected tests'`（mcp-server/src/server.mjs:73）。
  - `search_project` 的 `description`：目录为
    `Searches the current repository snapshot and published project knowledge in one ranked result set.`
    （mcp-tools.json:5），而源码为
    `'Returns one ranked result set containing current code and published project knowledge.'`
    （mcp-server/src/server.mjs:20）。
  - 各 `codegraph_*` 工具的描述：目录中逐个人工撰写（如 mcp-tools.json:115），
    而源码统一生成 `` `${title} for the token account. Branch queries require branchId or contextId and never switch branches implicitly.` ``
    （mcp-server/src/server.mjs:78）。
- 由于 `export:catalog` 的输入（`server.mjs` 的标题与描述）与已提交文件不同，
  执行该脚本无法复现已提交的 `mcp-tools.json`。
- CI 不校验该一致性：`.github/workflows/linux-ci.yml:68-72` 对 mcp-server 只执行
  `npm ci` 与 `npm test`，没有运行 `export:catalog`，也没有对产物做 diff 或断言。
- 影响范围：`/api/mcp` 的 `tools/list` 返回的是 `mcp-tools.json` 的内容
  （`backend/src/main/java/com/analyzercoder/application/mcp/McpToolCatalog.java:15-23`；
  `backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:77`），
  而入参校验也基于该文件的 schema（`McpToolCatalog.java:25-33`），
  因此 MCP 客户端看到的工具元数据与 stdio 适配器暴露的不一致。

### 缺口 5：无浏览器端到端测试

- 仓库自身没有任何 Playwright/Cypress 配置或 `*.e2e.ts`
  （见第 2.3 节的检索口径与证据）；工作区中仅有的 e2e 文件属于被索引的第三方样例仓库
  `runtime/data/repositories/.../witboot-web/`。
- 影响：登录、CSRF 头、Cookie 属性、SPA 路由守卫、分支上下文头透传等跨前后端的真实
  浏览器行为没有自动化覆盖；这些只能靠组件测试与后端测试间接验证。

### 缺口 6：`scripts/check-runtime.mjs` 无测试文件

- 该脚本没有对应测试文件，也不导入 `node:assert`；它是只读诊断脚本，
  通过 `record(name, passed, required, detail)` 汇总检查项并以
  `process.exitCode = checks.some(item => item.status === 'FAIL') ? 1 : 0` 决定退出码
  （`scripts/check-runtime.mjs:38-41,67-69`）。
- CI 只执行 `node scripts/check-runtime.mjs --help`
  （`.github/workflows/linux-ci.yml:61`），即只验证帮助文本可打印，
  其 PASS/WARN/FAIL 判定逻辑与退出码没有任何自动化断言。
- 影响：诊断逻辑本身（例如变量解析、TCP 探测、健康判定）发生回归时不会被发现。

### 缺口 7：前端路由元数据已声明但未参与路由守卫

- `frontend/src/router/index.ts:23` 为 `repositories` 路由声明 `meta.projectManage: true`，
  `:37` 为 `knowledge` 路由声明 `meta.repositoryRead: true`。
- 路由守卫只使用 `meta.public`（`index.ts:49`）与 `meta.admin`（`index.ts:55`），
  全文没有消费 `projectManage` 或 `repositoryRead` 的代码。
- 唯一引用它们的是测试断言：`frontend/src/router/index.spec.ts:38-39`，
  即测试只断言元数据"存在"，不校验其产生任何行为。
- 影响：这两个元数据目前是声明式的死配置，"项目管理"与"知识管理"页面的访问控制
  实际不生效；细粒度权限只在数据请求层由后端返回 403 拦截。

### 缺口 8：未验收项

以下能力没有验收证据，属未验收而非已知缺陷：

- **真实外部模型**：仓库内测试使用桩或本地替身，没有对真实第三方模型服务的端到端验收记录。
- **真实 CodeGraph CLI**：`ManagedCodeGraphCliContractTest` 受 `@EnabledOnOs(OS.LINUX)` 限制
  （`.../application/intelligence/ManagedCodeGraphCliContractTest.java:24`），
  且依赖外部 `codegraph` 可执行文件
  （`backend/src/main/resources/application.yml:85-87`），未在本次审计环境中运行。
- **远端 Git / GitLab**：远端导入、分支探测与同步依赖真实远端与凭据，
  未做端到端验收；相关策略只有单元级覆盖。
- **生产部署**：当前发布包为 PG/Nginx 容器加宿主机 JAR（见部署手册）；真实目标服务器上线、HTTPS、开机自启仍需按环境验收。旧全容器和纯宿主机方案已移除。
- **历史库升级**：未对包含跨仓库项目数据或跨仓库知识作用域的历史库执行 V9 升级演练
  （V9 的数据守卫见 `backend/src/main/resources/db/migration/V9__remove_cross_repository_projects.sql:6-24`）。

### 缺口 9：surefire 报告残留导致朴素汇总多算 7 项

- `backend/target/surefire-reports/` 中残留 3 个**源码已删除**的测试类报告：
  - `ChangeIntentParserTest`：`Tests run: 3`
    （backend/target/surefire-reports/com.analyzercoder.application.change.ChangeIntentParserTest.txt）；
  - `EngineeringProjectServiceTest`：`Tests run: 3`
    （.../com.analyzercoder.application.project.EngineeringProjectServiceTest.txt）；
  - `EngineeringProjectControllerTest`：`Tests run: 1`
    （.../com.analyzercoder.interfaces.rest.EngineeringProjectControllerTest.txt）。
- 三个类在 `backend/src/test` 下均已不存在；`backend/src/main` 下也已无任何
  `*EngineeringProject*` 文件。
- 该目录下 80 份 txt 报告的朴素求和为 `Tests run: 243, Skipped: 5`；
  减去这 3 份残留（合计 7 项）后为 **236**，与第 4 节实测的 236 一致。
- 影响：任何直接汇总 `backend/target/surefire-reports/` 的统计都会多算 7 项；
  统计前必须先清理 `target/` 或按源码集合过滤。

## 7. 下一步验证优先级

按风险从高到低排列，每项给出可执行动作。

1. **修复 CI tag 门禁（缺口 1）—— 会直接阻断发布。**
   动作：在"Enforce release quality gate"步骤之前新增生成
   `evaluation/results/release.json` 的步骤（由人工评审产出并提交，或由新 runner 生成），
   或将该门禁改为对已提交结果文件的条件跳过；修好后用一个 `v*` tag 或
   `act` 本地跑通 `.github/workflows/linux-ci.yml:92-96` 验证。
2. **让三个分支数据库测试真正运行（缺口 2）—— 分支隔离与准备任务是核心正确性。**
   动作：在 CI 中为 `APP_BRANCH_JDBC_URL`、`APP_BRANCH_OPERATIONS_TEST_URL`、
   `APP_BRANCH_JOB_TEST_URL` 提供独立测试库（可复用现有 `pgvector/pgvector:pg17` 服务、
   建独立 schema 或独立数据库），并同步补上 `_USER`/`_PASSWORD`；
   随后确认 `mvn -pl backend -am test` 的 skipped 从 5 降到 2。
3. **修正非默认分支上的知识卡代码关联（缺口 3）—— 会产生错误绑定。**
   动作：为 `/chunks` 接入分支上下文（在 `ChunkController` 注入 `BranchRequestContext`、
   在映射层支持按快照查询、并把 `/chunks` 加入 `BranchContextInterceptor` 的 GET 白名单），
   或让知识卡编辑器改用已支持分支的接口；
   验收：在非默认分支上创建知识卡并确认代码关联指向该分支的片段。
4. **统一 MCP 工具目录（缺口 4）—— 影响所有 MCP 客户端的工具契约。**
   动作：确认以 `server.mjs` 还是以手写目录为唯一事实来源，修正另一方，
   在 CI 中加入 `npm run export:catalog` 后对 `backend/src/main/resources/mcp-tools.json`
   做 `git diff --exit-code` 校验，并补一条断言覆盖标题与描述一致性。
5. **补齐权限元数据或删除死配置（缺口 7）—— 前端访问控制形同虚设。**
   动作：在路由守卫中消费 `meta.projectManage` 与 `meta.repositoryRead`（或在后端
   已足够的前提下删除这两个元数据），并把 `frontend/src/router/index.spec.ts:38-39`
   从"断言存在"改为"断言守卫行为"。
6. **为 `check-runtime.mjs` 补测试（缺口 6）—— 影响排障可靠性。**
   动作：新增 `scripts/check-runtime.test.mjs`，用环境变量注入与假的 TCP/HTTP 端点
   覆盖 PASS/WARN/FAIL 与退出码分支，并接入 CI 与 `scripts/verify-core.mjs`。
7. **清理 surefire 残留（缺口 9）—— 目前会污染统计。**
   动作：删除 `backend/target/` 后重跑 `mvn -pl backend -am test`；
   在统计脚本中改为从源码类集合出发而非直接汇总 `surefire-reports/`。
8. **补浏览器端到端测试（缺口 5）—— 成本最高，但覆盖面最大。**
   动作：引入 Playwright，优先覆盖登录与强制改密、CSRF 头、分支上下文透传、
   知识卡编辑与代码关联四条主链路，并纳入 CI。
9. **执行第 6 节缺口 8 的未验收项。**
   动作：在具备真实外部模型、真实 CodeGraph CLI、真实远端 Git/GitLab 与生产级
   部署环境的前提下，逐项执行端到端验收；并为 V9 升级准备一份含跨仓库数据的
   历史库副本先做演练，确认守卫会按预期阻断或放行。

## 8. 2026-09-20 部署流程改造验证

本节补充部署改造后的结果；前文测试资产数量和审计结果保留其历史口径。

| 检查 | 本机结果 |
| --- | --- |
| npm ci、前端类型检查和 Vite 生产构建 | 通过；有既有分包体积和导入方式提示 |
| Maven clean package -DskipTests | 通过，生成 Spring Boot JAR；本次未重跑业务单测 |
| `scripts/build-release.test.mjs` | 5 项通过；覆盖无镜像包、凭据排除、校验、镜像标签与架构、失败不交付；另已完成真实镜像导出验证 |
| `scripts/backend-launch.test.mjs` | Windows 下 1 项通过，使用实际测试 JAR 验证工作目录、空格路径、健康、重复启动、PID 身份保护、停止及启动失败清理 |
| 实际 Spring Boot 外部配置加载 | 使用构建 JAR 内 Spring Boot 依赖验证：`backend/config/application.yml` 自动加载，覆盖外部数据库/密钥/端口配置，同时继承 JAR 内默认属性；不连接数据库 |
| Compose 配置、PowerShell 解析、Bash `-n` | 通过；同一配置已由 Compose V2 和官方 `docker/compose:1.29.2` 分别执行 `config` 验证 |
| Nginx 静态入口、SPA 与资源 MIME | 通过；实际启动 Nginx 后 `/index.html`、`/` 和不存在的前端路由均返回 200，真实 JS 返回 `application/javascript`，缺失 asset 返回 404；精确 `/index.html` location 防止内部重定向循环 |
| Windows PowerShell 和 Git Bash 打包入口 | 均实际生成无镜像目录和 `tar.gz`；Git Bash 验证不是 Linux 主机运行验收 |
| 完整离线包与运行链路 | 通过；实际导出并重新加载 linux/amd64 PG/pgvector、Nginx 镜像，以自定义 Windows bind 目录在宿主机 18080 启动 PostgreSQL，Nginx 对外使用 18081；JAR 在 18082 完成 Flyway，前端 HTTP 200，Nginx `/api/health` 返回 `ok`，pgvector 版本为 0.8.6；临时进程、容器和目录已清理 |
| 完整包校验 | 24 个载荷文件 SHA-256 全部通过，压缩包 34 个条目且不含实际 `.env`、运行数据或 `.incomplete` 标记 |

CI 新增 `ubuntu-latest` / `windows-latest` 部署脚本测试矩阵。Windows Docker Desktop 下的完整 HTTP 部署链路已经通过；Linux 宿主机启动、HTTPS、开机自启和真实历史数据升级仍需目标环境验收。
