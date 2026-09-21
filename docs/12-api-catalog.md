# Analyzer Coder HTTP 接口目录
> 本文档由当前实现反推生成（2026-09-19）。描述已实现的需求，不是新设计。

## 1. 范围与方法

- 覆盖范围：`backend/src/main/java/com/analyzercoder/interfaces/rest/` 下所有带
  `@RestController` 的类型，共 31 个（另有 `ApiExceptionHandler` 为
  `@RestControllerAdvice`、`BranchRequestContext` 为 `@Component`，均不提供端点）
  （`backend/src/main/java/com/analyzercoder/interfaces/rest/ApiExceptionHandler.java:17`，
  `backend/src/main/java/com/analyzercoder/interfaces/rest/BranchRequestContext.java:11`）。
- 端点总数：143 个（方法 + 路径组合）。
- 统计口径：对 `interfaces/rest/` 下每个文件中带映射注解的方法逐一计数，
  即匹配行首的 `@GetMapping`、`@PostMapping`、`@PutMapping`、`@DeleteMapping`、
  `@PatchMapping`，共 143 处，分布于上述 31 个类。
  注意 `@RestController` 的字面匹配会额外命中 `ApiExceptionHandler` 的
  `@RestControllerAdvice`，因此按注解字面统计会得到 32 个类，需排除该类才是真实控制器数。
- 各控制器端点数：IntelligenceController 17、RepositoryBranchController 16、
  LlmSettingsController 14、RepositoryController 11、AccountController 9、
  IndexController 8、RepositoryCredentialController 8、RepositorySourceImportController 6、
  AuthController 5、RepositoryGovernanceController 5、CodeGraphController 4、
  RepositoryProjectDraftController 4、其余 19 个类各 1–3 个，合计 143。
- 未实现的端点不写入本文档。

## 2. 认证与权限模型

- 认证入口有两个拦截器，均注册在 `/**`：
  `AccessTokenInterceptor` 先执行，`SessionInterceptor` 后执行
  （`backend/src/main/java/com/analyzercoder/security/WebSecurityConfig.java:21-23`）。
- `SessionInterceptor` 放行的公开路径只有 `/api/health`、`/api/auth/login`、
  `/api/auth/captcha`、`/actuator/health*`、`/error*`
  （`backend/src/main/java/com/analyzercoder/security/SessionInterceptor.java:23-29`）。
- 非安全方法（非 GET/HEAD/OPTIONS）必须携带与账号会话一致的 `X-CSRF-Token`，
  否则返回 403 `CSRF_INVALID`
  （`backend/src/main/java/com/analyzercoder/security/SessionInterceptor.java:16,58-66`）。
- 首次登录且 `mustChangePassword` 为真时，除 `/api/auth/change-password`、
  `/api/auth/logout`、`/api/auth/me` 外一律返回 403 `PASSWORD_CHANGE_REQUIRED`
  （`backend/src/main/java/com/analyzercoder/security/SessionInterceptor.java:67-71`）。
- 仓库权限枚举只有三级：`READ < MAINTAIN < MANAGE`，比较用 `ordinal()`
  （`backend/src/main/java/com/analyzercoder/security/RepositoryPermission.java:4-11`）。
- 权限判定规则：超级管理员恒通过；成员记录中的 `ownerAccountId` 等于当前账号时恒通过；
  否则要求 `permission_level.includes(required)`
  （`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:19-35`）。
- 仅所有者动作由 `requireOwner` 判定，失败返回 403 `OWNER_REQUIRED`
  （`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:46-54`）。
- 账号角色只有 `SUPER_ADMIN` 与 `NORMAL`
  （`backend/src/main/java/com/analyzercoder/security/AccountRole.java:4-7`）。
- 下文"所需权限"列的取值含义：READ/MAINTAIN/MANAGE 为仓库级三级权限；
  "所有者"表示 `requireOwner`；"超级管理员"表示 `SecurityContext.requireAdmin`；
  "账户访问令牌"表示需 `Authorization: Bearer acp_...`。
- 所有"会话"端点默认也接受 `Authorization` 头的访问令牌，但令牌仅被允许用于
  少数路径，见第 13 节。

## 3. 账号与认证

### 3.1 `/api/auth`（AuthController.java:28）

| 方法 | 路径 | 用途 | 所需权限 | 请求要点 | 响应要点 | 来源 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | /api/auth/captcha | 生成登录验证码挑战 | 公开 | 查询参数 `username` | `Challenge{id,prompt,expiresAt}` | AuthController.java:64-67 |
| POST | /api/auth/login | 登录并下发会话 Cookie | 公开 | `LoginRequest{username,password,captchaId,captchaAnswer}`；失败达阈值时返回 429 `CAPTCHA_REQUIRED` | `SessionResponse`；`Set-Cookie: AC_SESSION`（HttpOnly、SameSite=Lax、Path=/） | AuthController.java:69-90,119-146 |
| GET | /api/auth/me | 读取当前会话账号 | 会话 | - | `SessionResponse{id,username,displayName,role,mustChangePassword,lastLoginAt,csrfToken}` | AuthController.java:92-96,128-135 |
| POST | /api/auth/change-password | 修改密码并重新签发会话 | 会话 + CSRF | `ChangePasswordRequest{currentPassword,newPassword}` | `SessionResponse` + 新 Cookie | AuthController.java:98-111,125-126 |
| POST | /api/auth/logout | 注销会话 | 会话 + CSRF | - | 无响应体；`AC_SESSION` 以 Max-Age 0 清空 | AuthController.java:113-117 |

### 3.2 `/api/auth/preferences`（AccountPreferenceController.java:15）

| 方法 | 路径 | 用途 | 所需权限 | 请求要点 | 响应要点 | 来源 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | /api/auth/preferences/current-repository | 读取"当前仓库"偏好 | 会话 | - | `CurrentRepositoryResponse{repositoryId}` | AccountPreferenceController.java:23-26 |
| PUT | /api/auth/preferences/current-repository | 写入"当前仓库"偏好 | 会话 + CSRF；目标仓库不可见时 403 `FORBIDDEN` | `CurrentRepositoryRequest{repositoryId}` | `CurrentRepositoryResponse{repositoryId}` | AccountPreferenceController.java:28-34；AuthService.java:272-283 |

### 3.3 `/api/accounts`（AccountController.java:25）

| 方法 | 路径 | 用途 | 所需权限 | 请求要点 | 响应要点 | 来源 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | /api/accounts | 列出全部账号 | 超级管理员 | - | `List<AccountSummary>` | AccountController.java:33-37 |
| GET | /api/accounts/page | 账号分页与搜索 | 超级管理员 | `query`、`pageNum=1`、`pageSize=20` | `PageResult<AccountSummary>` | AccountController.java:39-47 |
| POST | /api/accounts | 创建账号 | 超级管理员 + CSRF | `CreateAccountRequest{username,displayName,role=NORMAL,temporaryPassword}`；不传临时密码则服务端生成 | `CreatedAccount{account,temporaryPassword}` | AccountController.java:49-60,119-134；AuthService.java:298-323 |
| PATCH | /api/accounts/{accountId} | 修改显示名/角色/启用状态 | 超级管理员 + CSRF | `UpdateAccountRequest{displayName,role,enabled,version}`；版本不匹配 409 `ACCOUNT_VERSION_CONFLICT` | `AccountSummary` | AccountController.java:62-76,131-132；AuthService.java:325-373 |
| POST | /api/accounts/{accountId}/reset-password | 重置为临时密码 | 超级管理员 + CSRF | - | `TemporaryPasswordResponse{temporaryPassword}` | AccountController.java:78-84,134 |
| POST | /api/accounts/{accountId}/unlock | 解除登录锁定 | 超级管理员 + CSRF | - | 无响应体 | AccountController.java:86-90 |
| GET | /api/accounts/{accountId}/permissions | 查询账号仓库权限 | 超级管理员 | - | `List<PermissionView{repositoryId,repositoryName,permission}>` | AccountController.java:92-97；AuthService.java:399-409 |
| PUT | /api/accounts/{accountId}/permissions/{repositoryId} | 旧版权限写入（恒定失败） | 超级管理员 + CSRF | `PermissionRequest{permission}` | 恒 409 `USE_REPOSITORY_GOVERNANCE`，不写入任何数据 | AccountController.java:99-108,136；AuthService.java:411-419 |
| GET | /api/accounts/audit | 读取审计事件 | 超级管理员 | `limit=100`（钳制到 1–200）、`offset=0` | `List<AuditView{id,eventType,result,requestId,sourceIp,actorUsername,targetUsername,repositoryName,createdAt}>` | AccountController.java:110-117；AuthService.java:421-436 |

### 3.4 `/api/accounts/{accountId}/access-tokens`（AccountAccessTokenController.java:20）

| 方法 | 路径 | 用途 | 所需权限 | 请求要点 | 响应要点 | 来源 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | /api/accounts/{accountId}/access-tokens | 列出访问令牌 | 本人或超级管理员 | - | `List<TokenView{id,name,prefix,createdAt,expiresAt,lastUsedAt,revokedAt}>`；响应带 `Cache-Control: no-store` | AccountAccessTokenController.java:28-34；AccessTokenService.java:30-33,119-126 |
| POST | /api/accounts/{accountId}/access-tokens | 签发访问令牌 | 本人或超级管理员 + CSRF | `CreateToken{name,expiresInDays}`；名称 1–80 字符，有效期 1–365 天，否则 400 `TOKEN_INPUT_INVALID` | `IssuedToken{token,rawToken}`，明文仅在本次响应返回 | AccountAccessTokenController.java:36-50,60；AccessTokenService.java:36-61 |
| DELETE | /api/accounts/{accountId}/access-tokens/{tokenId} | 撤销访问令牌 | 本人或超级管理员 + CSRF | - | `{"revoked":true}`；不存在返回 404 `TOKEN_NOT_FOUND` | AccountAccessTokenController.java:52-58；AccessTokenService.java:63-69 |

## 4. 仓库与来源

### 4.1 `/api/repositories`（RepositoryController.java:43）

| 方法 | 路径 | 用途 | 所需权限 | 请求要点 | 响应要点 | 来源 |
| --- | --- | --- | --- | --- | --- | --- |
| POST | /api/repositories | 注册本地路径仓库 | 会话 + CSRF；`path` 须落在允许根目录内 | `RegisterRepositoryRequest{name(≤100),path}` | `RepositoryResponse` | RepositoryController.java:73-81,196-197 |
| GET | /api/repositories/page | 可见仓库分页 | 会话（按可见性过滤） | `query`、`pageNum=1`、`pageSize=20` | `PageResult<RepositoryResponse>` | RepositoryController.java:83-93 |
| GET | /api/repositories/{repositoryId} | 单个仓库详情 | READ | - | `RepositoryResponse` | RepositoryController.java:95-101 |
| GET | /api/repositories | 列出全部可见仓库 | 会话（按可见性过滤） | - | `List<RepositoryResponse>` | RepositoryController.java:103-112 |
| POST | /api/repositories/{repositoryId}/rescan | 重新扫描本地工作区 | MAINTAIN + CSRF | - | `RescanRepositoryResponse{changed,repository}` | RepositoryController.java:114-123,205 |
| PATCH | /api/repositories/{repositoryId} | 修改名称/描述/默认分支 | MANAGE + CSRF | `UpdateRepositoryRequest{name(≤100),description(≤500),defaultBranch,version}` | `RepositoryResponse` | RepositoryController.java:162-177,199-203；RepositoryEditingService.java:44-45 |
| DELETE | /api/repositories/{repositoryId} | 请求删除仓库 | 所有者（或超级管理员）+ CSRF | - | 无响应体 | RepositoryController.java:179-183；RepositoryGovernanceService.java:139-140 |

`RepositoryResponse` 关键字段：`id,name,description,version,path,sourceType,branch,commit,worktreeDigest,dirty,contentVersion,contentVersionCreatedAt,codeGraphPath,codeGraphDetected,lastScannedAt,ownerAccountId,ownerDisplayName,relationship,ownershipVersion,repositoryStatus,capabilities`
（RepositoryController.java:210-231）。

### 4.2 `/api/repository-imports`（RepositorySourceImportController.java:23）

| 方法 | 路径 | 用途 | 所需权限 | 请求要点 | 响应要点 | 来源 |
| --- | --- | --- | --- | --- | --- | --- |
| POST | /api/repository-imports/remote-jobs | 提交后台远程导入任务 | 会话 + CSRF；URL 须通过 `RemoteRepositoryTargetPolicy`；`credentialId` 须为本人凭据；仅支持 REMOTE_GIT/GITLAB | `RemoteInput{name,url,branch,sourceType,credentialId,projectDraftId}` | `JobView` | RepositorySourceImportController.java:38-49,98-104；RepositoryImportJobService.java:52-80 |
| GET | /api/repository-imports/jobs | 列出本人导入任务 | 任务所属账号或超级管理员 | - | `List<JobView>` | RepositorySourceImportController.java:51-56；RepositoryImportJobService.java:88-90 |
| GET | /api/repository-imports/jobs/{id} | 查看导入任务 | 任务所属账号或超级管理员 | - | `JobView` | RepositorySourceImportController.java:58-62；RepositoryImportJobService.java:82-86,138-145 |
| POST | /api/repository-imports/jobs/{id}/cancel | 取消导入任务 | 任务所属账号或超级管理员 + CSRF | - | `JobView`；不可取消时 409 `CONFLICT` | RepositorySourceImportController.java:64-68；RepositoryImportJobService.java:92-97 |
| POST | /api/repository-imports/remote | 同步导入远程仓库（旧版，阻塞式） | 会话 + CSRF；URL 白名单 | `RemoteInput` | `RepositoryResponse` | RepositorySourceImportController.java:70-85 |
| POST | /api/repository-imports/zip | 上传 ZIP 导入 | 会话 + CSRF | multipart/form-data：`name`、`file` | `RepositoryResponse` | RepositorySourceImportController.java:87-96 |

### 4.3 `/api/repository-project-drafts`（RepositoryProjectDraftController.java:18）

| 方法 | 路径 | 用途 | 所需权限 | 请求要点 | 响应要点 | 来源 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | /api/repository-project-drafts | 列出本人项目草稿 | 草稿所有者（按 `owner_account_id`） | - | `List<Draft>` | RepositoryProjectDraftController.java:26-29；RepositoryProjectDraftService.java:41-65 |
| POST | /api/repository-project-drafts | 创建项目草稿 | 会话 + CSRF | `Create{name,description}` | `Draft` | RepositoryProjectDraftController.java:31-35,55 |
| PATCH | /api/repository-project-drafts/{id}/source | 配置代码来源 | 草稿所有者 + CSRF | `Source{version,sourceType,sourceLocation,credentialId}`；版本冲突 409 `PROJECT_DRAFT_CONFLICT` | `Draft` | RepositoryProjectDraftController.java:37-47,57-61；RepositoryProjectDraftService.java:41-65 |
| POST | /api/repository-project-drafts/{id}/complete | 关联已导入仓库并完成草稿 | 草稿所有者 + 对该仓库 MANAGE | `Complete{repositoryId}` | `Draft` | RepositoryProjectDraftController.java:49-53,63；RepositoryProjectDraftService.java:83-89 |

### 4.4 凭据（RepositoryCredentialController.java:21、RepositoryCredentialBindingController.java:19）

| 方法 | 路径 | 用途 | 所需权限 | 请求要点 | 响应要点 | 来源 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | /api/repository-credentials | 列出可见凭据 | 凭据创建者本人或超级管理员 | - | `List<CredentialView>` | RepositoryCredentialController.java:29-32；RepositoryCredentialService.java:37-38 |
| POST | /api/repository-credentials | 新建凭据 | 会话 + CSRF | `CredentialInput`（secret 长度 ≥ 8） | `CredentialView` | RepositoryCredentialController.java:34-37；RepositoryCredentialService.java:42-60,222-245 |
| PUT | /api/repository-credentials/{id} | 更新凭据 | 凭据所有者或超级管理员 + CSRF | `CredentialInput` | `CredentialView` | RepositoryCredentialController.java:39-43；RepositoryCredentialService.java:64-85 |
| POST | /api/repository-credentials/{id}/validate | 校验凭据与目标 URL 是否匹配 | 凭据所有者或超级管理员 + CSRF；URL 白名单 | `ValidationInput{repositoryUrl}` | `CredentialView`；服务端 URL 不匹配时拒绝 | RepositoryCredentialController.java:45-53,78；RepositoryCredentialService.java:88-103,251 |
| POST | /api/repository-credentials/{id}/enable | 启用凭据 | 凭据所有者或超级管理员 + CSRF | - | `CredentialView` | RepositoryCredentialController.java:55-59；RepositoryCredentialService.java:151-162 |
| POST | /api/repository-credentials/{id}/disable | 停用凭据 | 凭据所有者或超级管理员 + CSRF | - | `CredentialView` | RepositoryCredentialController.java:61-65 |
| DELETE | /api/repository-credentials/{id} | 删除凭据 | 凭据所有者或超级管理员 + CSRF | - | 无响应体 | RepositoryCredentialController.java:67-70；RepositoryCredentialService.java:166-167 |
| GET | /api/repository-credentials/{id}/bindings | 列出凭据的仓库绑定 | 凭据所有者或超级管理员 | - | `List<BindingView>` | RepositoryCredentialController.java:72-76；RepositoryCredentialService.java:176-177 |
| GET | /api/repositories/{repositoryId}/credential | 读取仓库凭据绑定状态 | 所有者（或超级管理员） | - | `BindingStatus{remoteUrl,validated}` | RepositoryCredentialBindingController.java:27-30；RepositoryCredentialBindingService.java:77-81 |
| PUT | /api/repositories/{repositoryId}/credential | 绑定凭据到仓库 | 所有者（或超级管理员）+ CSRF | `BindInput{credentialId}` | `BindingStatus` | RepositoryCredentialBindingController.java:32-42,52 |
| DELETE | /api/repositories/{repositoryId}/credential | 解绑仓库凭据 | 所有者（或超级管理员）+ CSRF | - | 无响应体 | RepositoryCredentialBindingController.java:44-50 |

### 4.5 `/api/repositories/{repositoryId}/governance`（RepositoryGovernanceController.java:25）

| 方法 | 路径 | 用途 | 所需权限 | 请求要点 | 响应要点 | 来源 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | /governance/members | 列出成员与授权 | MAINTAIN | - | `List<RepositoryMemberRow>` | RepositoryGovernanceController.java:33-37；RepositoryGovernanceService.java:39-41 |
| GET | /governance/candidates | 列出可授权账号 | 所有者（或超级管理员） | - | `List<GovernanceAccountRow>` | RepositoryGovernanceController.java:39-43；RepositoryGovernanceService.java:44-46 |
| PUT | /governance/members/{accountId} | 授予或调整权限 | 所有者（或超级管理员）+ CSRF | `GrantRequest{permission,expectedOwnershipVersion}` | `VersionResponse{ownershipVersion}` | RepositoryGovernanceController.java:45-59,92-93,101；RepositoryGovernanceService.java:49-57 |
| DELETE | /governance/members/{accountId} | 撤销授权 | 所有者（或超级管理员）+ CSRF | `expectedOwnershipVersion`（查询参数，必填） | `VersionResponse` | RepositoryGovernanceController.java:61-74 |
| POST | /governance/transfer | 转移仓库所有权 | 所有者（或超级管理员）+ CSRF | `TransferRequest{newOwnerAccountId,newName,previousOwnerPermission,expectedOwnershipVersion}` | `VersionResponse` | RepositoryGovernanceController.java:76-90,95-99；RepositoryGovernanceService.java:96 |

## 5. 分支与内容版本

### 5.1 `/api/repositories/{repositoryId}`（RepositoryBranchController.java:26）

| 方法 | 路径 | 用途 | 所需权限 | 请求要点 | 响应要点 | 来源 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | /branches/discover | 探测远端分支 | MAINTAIN | - | `List<RemoteBranch{name,commitSha}>` | RepositoryBranchController.java:47-51；BranchRemoteService.java:36 |
| GET | /branches | 列出受管分支 | READ | - | `List<Branch{id,name,contentVersion,commitSha,status,error,generation,trackingStatus,archivedAt}>` | RepositoryBranchController.java:53-57；RepositoryBranchService.java:66-78 |
| POST | /branches | 跟踪分支 | MAINTAIN + CSRF；同名已归档分支存在时 409 `BRANCH_ARCHIVED` | `Track{name}` | `Branch` | RepositoryBranchController.java:59-63,117；RepositoryBranchService.java:100-120 |
| POST | /branches/{branchId}/archive | 归档分支 | MANAGE + CSRF；存在进行中任务时 409 `BRANCH_HAS_ACTIVE_TASK` | - | `Branch` | RepositoryBranchController.java:65-71；RepositoryBranchService.java:122-147 |
| POST | /branches/{branchId}/restore | 恢复已归档分支 | MANAGE + CSRF | - | `Branch` | RepositoryBranchController.java:73-79；RepositoryBranchService.java:149-162 |
| POST | /branches/{branchId}/prepare | 提交分支内容版本准备任务 | MAINTAIN + CSRF | - | `Job`；HTTP 202 | RepositoryBranchController.java:81-88；BranchPreparationJobs.java:111-113,136 |
| GET | /branch-preparation-jobs | 每个分支/类型的最新任务 | READ | - | `List<Job{id,branchId,status,stage,error,kind,contentVersion}>` | RepositoryBranchController.java:90-94；BranchPreparationJobs.java:49-54,77-78 |
| POST | /branch-vector-jobs | 提交向量索引任务 | MAINTAIN + CSRF；内容版本须已有内容索引，否则 400 | `Context{branchId,contextId}`；`contextId` 为空时 400 | `Job`；HTTP 202 | RepositoryBranchController.java:96-106,119；BranchPreparationJobs.java:115-132 |
| POST | /contexts | 解析或复用分支阅读上下文 | READ + CSRF | `Context{branchId,contextId}` | `BranchReadContext{contextId,repositoryId,branchId,branchName,contentVersion,commitSha,expiresAt}` | RepositoryBranchController.java:108-115；RepositoryBranchService.java:347-349 |
| GET | /branches/{branchId}/contentVersions/{contentVersion}/retention | 检查内容版本产物保留状态 | MANAGE | - | `Retention` | RepositoryBranchController.java:121-129；BranchArtifactRetentionService.java:44 |
| DELETE | /branches/{branchId}/contentVersions/{contentVersion} | 删除分支内容版本 | MANAGE + CSRF | - | `Retention` | RepositoryBranchController.java:131-139；BranchArtifactRetentionService.java:50 |
| GET | /knowledge/branch-scopes | 列出知识卡分支作用域 | READ | - | `List<Scope{cardId,revision,mode,branchIds}>` | RepositoryBranchController.java:141-145,180；BranchKnowledgeService.java:60-61 |
| PUT | /knowledge/{cardId}/branch-scope | 设置分支作用域 | MANAGE + CSRF | `Scope{revision,mode,branchIds}` | 无响应体；HTTP 204 | RepositoryBranchController.java:147-161；BranchKnowledgeService.java:81 |
| POST | /knowledge/{cardId}/branch-validation | 提交分支校验结论 | MANAGE + CSRF | `Validation{revision,contextId,state,note}` | 无响应体；HTTP 204 | RepositoryBranchController.java:163-178,182；BranchKnowledgeService.java:164-165 |
| GET | /branch-preparation-jobs/history | 任务历史分页 | READ | `branchId`（可选）、`pageNum=1`、`pageSize=15` | `PageResult<Job>` | RepositoryBranchController.java:184-192；BranchPreparationJobs.java:57-59 |
| GET | /knowledge/branch-validations | 列出待校验知识卡 | READ | `contextId`（必填） | `List<ValidationCard{cardId,revision,title,content,state,note}>` | RepositoryBranchController.java:194-201；BranchKnowledgeService.java:29-34 |

### 5.2 `/api/repositories/{repositoryId}`（BranchCodeOperationsController.java:21）

| 方法 | 路径 | 用途 | 所需权限 | 请求要点 | 响应要点 | 来源 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | /branch-index-statuses | 各分支索引就绪状态 | READ | - | `List<IndexStatus{branchId,contentVersion,syncedAt,contentReady,graphReady,vectorsReady}>` | BranchCodeOperationsController.java:36-40；BranchCodeOperationsService.java:205-215 |
| GET | /branches/{branchId}/index-status | 单分支内容版本索引状态 | READ | `contextId`（必填） | `IndexStatus` | BranchCodeOperationsController.java:42-50；BranchCodeOperationsService.java:166,218-219 |
| POST | /branches/{branchId}/code-jobs | 提交分支操作任务 | MAINTAIN + CSRF；`SYNC`/`PREPARE` 不得带 `contextId`，`CONTENT`/`GRAPH` 必须带 `contextId` | `Operation{kind,contextId}`，`kind` 限 `SYNC`/`CONTENT`/`GRAPH`/`PREPARE` | `Job`；HTTP 202 | BranchCodeOperationsController.java:52-68,70；BranchPreparationJobs.java:101-109,136 |

### 5.3 `/api/repositories/{repositoryId}/branch-overview`（BranchOverviewController.java:12）

| 方法 | 路径 | 用途 | 所需权限 | 请求要点 | 响应要点 | 来源 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | /branch-overview | 单分支聚合总览 | READ；必须携带 `X-Branch-Context`，否则 400 | 头 `X-Branch-Context: <contextId>` | `Overview{preparation,codeFacts,health}` | BranchOverviewController.java:21-27；BranchOverviewService.java:41-51 |

## 6. 代码浏览与片段

| 方法 | 路径 | 用途 | 所需权限 | 请求要点 | 响应要点 | 来源 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | /api/repositories/{repositoryId}/files | 内容版本文件清单 | READ | 可选 `X-Branch-Context` | `ContentVersionFiles`（`FileEntry{path,name,language,sizeBytes}`） | RepositoryCodeBrowserController.java:36-45；RepositoryCodeBrowserService.java:267-270 |
| GET | /api/repositories/{repositoryId}/files/content | 读取单文件文本 | READ | `path`（必填）；可选 `X-Branch-Context` | `FileContent` | RepositoryCodeBrowserController.java:47-58；RepositoryCodeBrowserService.java:272 |
| GET | /api/repositories/{repositoryId}/files/raw | 读取图片等二进制内容 | READ | `path`（必填）；可选 `X-Branch-Context` | 字节流；响应头 `X-Content-Type-Options: nosniff`、`Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; sandbox`、`Cache-Control: no-cache` | RepositoryCodeBrowserController.java:60-80 |
| POST | /api/repositories/{repositoryId}/knowledge/attachments | 上传知识附件 | MAINTAIN + CSRF | multipart/form-data：`file` | `Attachment` | KnowledgeAttachmentController.java:37-45 |
| GET | /api/repositories/{repositoryId}/knowledge/attachments/{attachmentId} | 下载知识附件 | READ | - | 文件流；`Content-Disposition: attachment`、`X-Content-Type-Options: nosniff` | KnowledgeAttachmentController.java:47-65 |
| GET | /api/repositories/{repositoryId}/chunks | 代码片段列举/检索 | READ | `q`、`limit`、`offset`；**不解析 `X-Branch-Context`** | `CodeChunkListResponse{total,limit,offset,chunks}`；片段字段见下 | ChunkController.java:34-44 |
| GET | /api/repositories/{repositoryId}/code-evidence-context | 当前文件的可追溯证据上下文 | READ；草稿知识仅 MAINTAIN 可见 | `filePath`（必填）、`symbol`（可选）；可选 `X-Branch-Context` | `CodeEvidenceContext` | CodeEvidenceContextController.java:18,35-55 |

`CodeChunkResponse` 关键字段：`id,repositoryId,contentVersion,commitSha,filePath,symbolId,symbolName,symbolKind,language,assetType,chunkType,startLine,endLine,content,contentHash,createdAt`
（ChunkController.java:57-73）。

`GET /api/repositories/{repositoryId}/code-evidence-context` 的权限细节：
入口要求 READ，随后以 `access.canAccess(account, id, RepositoryPermission.MAINTAIN)`
决定是否包含草稿知识（`CodeEvidenceContextController.java:41-44`）；
携带 `X-Branch-Context` 时会改用分支内容版本仓库并叠加该分支的适用知识卡集合
（`CodeEvidenceContextController.java:45-54`）。该路径已被
`BranchContextInterceptor` 的 GET 白名单收录，因此分支上下文可用
（`backend/src/main/java/com/analyzercoder/security/BranchContextInterceptor.java:18`）。

## 7. 索引与向量

### 7.1 索引任务（IndexController.java:29）

| 方法 | 路径 | 用途 | 所需权限 | 请求要点 | 响应要点 | 来源 |
| --- | --- | --- | --- | --- | --- | --- |
| POST | /api/repositories/{repositoryId}/index | 启动索引任务 | MAINTAIN + CSRF；非 FULL/INCREMENTAL 时 400 | `StartIndexRequest{type}`（可缺省，缺省为 FULL） | `IndexJobResponse` | IndexController.java:43-55,121 |
| GET | /api/repositories/{repositoryId}/index/status | 最近一次索引任务 | READ | - | `IndexJobResponse` | IndexController.java:57-62 |
| GET | /api/index-jobs/page | 索引任务分页 | 超级管理员 | `pageNum=1`、`pageSize=20` | `PageResult<IndexJobResponse>` | IndexController.java:64-71 |
| GET | /api/index-jobs/{jobId} | 单个任务详情 | 该任务所属仓库 READ | - | `IndexJobResponse` | IndexController.java:73-79 |
| GET | /api/index-jobs | 列出任务（按可见仓库过滤） | 超级管理员 | - | `List<IndexJobResponse>` | IndexController.java:81-91 |
| GET | /api/repositories/{repositoryId}/index-jobs | 指定仓库的任务列表 | READ | - | `List<IndexJobResponse>` | IndexController.java:93-99 |
| POST | /api/index-jobs/{jobId}/cancel | 取消任务 | 该任务所属仓库 MAINTAIN + CSRF | - | `IndexJobResponse` | IndexController.java:101-109 |
| POST | /api/index-jobs/{jobId}/retries | 重试任务 | 该任务所属仓库 MAINTAIN + CSRF | - | `IndexJobResponse` | IndexController.java:111-119 |

`IndexJobResponse` 关键字段：`id,repositoryId,type,status,currentStep,executionMode,fallbackReason,failureCode,errorMessage,startedAt,heartbeatAt,timeoutAt,finishedAt,createdAt`
（IndexController.java:123-137）。

### 7.2 `/api/repositories/{repositoryId}/vector-index`（VectorIndexController.java:22）

| 方法 | 路径 | 用途 | 所需权限 | 请求要点 | 响应要点 | 来源 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | /vector-index/summary | 向量索引概览 | READ | - | `Summary` | VectorIndexController.java:33-37；VectorIndexQueryService.java:174 |
| GET | /vector-index/chunks | 向量化片段分页 | READ | `q`、`status`、`chunkType`、`pageNum=1`、`pageSize=15` | `PageResult<ChunkItem>` | VectorIndexController.java:39-50；VectorIndexQueryService.java:189 |
| GET | /vector-index/knowledge | 向量化知识分页 | READ | `q`、`status`、`pageNum=1`、`pageSize=15` | `PageResult<KnowledgeItem>` | VectorIndexController.java:52-62；VectorIndexQueryService.java:209 |

## 8. 检索与问答

### 8.1 `/api`（IntelligenceController.java:30）

| 方法 | 路径 | 用途 | 所需权限 | 请求要点 | 响应要点 | 来源 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | /api/repositories/{repoId}/hybrid-search | 混合检索（旧版） | READ | `query`（必填）、`limit=20`；可选 `X-Branch-Context` | `SearchResponse{hits,retrieval}` | IntelligenceController.java:46-57；IntelligenceService.java:1577 |
| GET | /api/repositories/{repoId}/evidence-search | 统一证据检索 | READ | `query`（必填）、`limit=20`；可选 `X-Branch-Context` | `EvidenceSearchResult{evidence,retrieval}` | IntelligenceController.java:59-70；IntelligenceService.java:1579 |
| POST | /api/repositories/{repoId}/ask | 提交问答 | READ + CSRF | `Question{question,clientRequestId,threadId,modelConfigId}`；可选 `X-Branch-Context` | `Answer` | IntelligenceController.java:72-95,257-258；IntelligenceService.java:1671 |
| GET | /api/repositories/{repoId}/ask/models | 可用问答模型 | READ | - | `List<AskModelView>` | IntelligenceController.java:97-102；LlmSettingsService.java:1138 |
| GET | /api/repositories/{repoId}/qa/records | 问答历史 | READ | `limit=50`、`offset=0`；可选 `X-Branch-Context` | `List<HistoryRecord>` | IntelligenceController.java:104-115；IntelligenceService.java:1700 |
| GET | /api/repositories/{repoId}/qa/records/{conversationId} | 会话详情 | READ | 可选 `X-Branch-Context` | `ThreadDetail` | IntelligenceController.java:117-127；IntelligenceService.java:1691 |
| PATCH | /api/repositories/{repoId}/qa/records/{conversationId} | 重命名会话 | READ + CSRF | `HistoryTitle{title}`；可选 `X-Branch-Context` | `HistoryRecord` | IntelligenceController.java:129-141,260 |
| DELETE | /api/repositories/{repoId}/qa/records/{conversationId} | 删除会话 | READ + CSRF | 可选 `X-Branch-Context` | 无响应体；HTTP 204 | IntelligenceController.java:143-153 |
| GET | /api/repositories/{repoId}/chunks/{chunkId}/graph-target | 片段到图谱目标 | READ | 可选 `X-Branch-Context` | `GraphTarget{symbol,filePath,startLine}` | IntelligenceController.java:155-163；IntelligenceService.java:1716 |
| GET | /api/repositories/{repoId}/graph | 符号邻域图（旧版） | READ | `symbol`（必填）、`depth=3`、`direction=BOTH`；**不接受 `X-Branch-Context`** | `GraphResult` | IntelligenceController.java:165-174；IntelligenceService.java:1722 |
| GET | /api/settings | 读取平台设置 | 超级管理员 | - | `Map<String,String>` | IntelligenceController.java:233-237 |
| PUT | /api/settings | 保存平台设置 | 超级管理员 + CSRF | `Map<String,String>` | `Map<String,String>` | IntelligenceController.java:243-248 |

`X-Branch-Context` 的合法性由 `BranchContextInterceptor` 白名单决定；未被列入白名单的
"方法 + 路径"一旦携带该头，将返回 409 `BRANCH_CONTEXT_UNSUPPORTED` 而不会回退到默认分支
（`backend/src/main/java/com/analyzercoder/security/BranchContextInterceptor.java:10-56`）。

## 9. 知识

### 9.1 知识卡（IntelligenceController.java:176-231）

| 方法 | 路径 | 用途 | 所需权限 | 请求要点 | 响应要点 | 来源 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | /api/repositories/{repoId}/knowledge | 知识卡列表 | READ；草稿卡仅 MAINTAIN 可见 | 可选 `X-Branch-Context`（按分支适用性过滤） | `List<KnowledgeCard>` | IntelligenceController.java:176-188；IntelligenceService.java:1776 |
| POST | /api/repositories/{repoId}/knowledge | 创建知识卡 | MAINTAIN + CSRF | `CardInput` | `KnowledgeCard` | IntelligenceController.java:190-201；IntelligenceService.java:1744 |
| PUT | /api/repositories/{repoId}/knowledge/{cardId} | 更新知识卡 | MAINTAIN + CSRF | `CardInput` | `KnowledgeCard` | IntelligenceController.java:203-211 |
| POST | /api/repositories/{repoId}/knowledge/{cardId}/review | 审核知识卡 | MANAGE + CSRF | `ReviewCardRequest{reviewStatus}` | `KnowledgeCard` | IntelligenceController.java:213-221,239 |
| POST | /api/repositories/{repoId}/knowledge/{cardId}/publication | 设置发布状态 | MANAGE + CSRF | `PublicationRequest{publicationStatus}` | `KnowledgeCard` | IntelligenceController.java:223-231,241 |

### 9.2 `/api/repositories/{repoId}/knowledge/{cardId}/history`（KnowledgeCardHistoryController.java:20）

| 方法 | 路径 | 用途 | 所需权限 | 请求要点 | 响应要点 | 来源 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | /history | 修订历史 | READ | - | `List<Revision>` | KnowledgeCardHistoryController.java:31-39 |
| POST | /history/{revision}/restore | 恢复到指定修订 | MAINTAIN + CSRF | 路径变量 `revision` | `KnowledgeCard` | KnowledgeCardHistoryController.java:41-50 |

### 9.3 `/api/repositories/{repositoryId}/knowledge/{cardId}`（KnowledgeDriftController.java:24）

| 方法 | 路径 | 用途 | 所需权限 | 请求要点 | 响应要点 | 来源 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | /source-drift | 最近一次来源漂移事件 | READ | - | `DriftEvent`；无事件时 HTTP 204 | KnowledgeDriftController.java:39-51 |
| POST | /source-review | 人工复核来源漂移 | MAINTAIN + CSRF | `SourceReviewRequest{action,expectedRevision(≥1),note}` | `SourceReviewResponse{card,event}` | KnowledgeDriftController.java:53-76 |

### 9.4 `/api/repositories/{repoId}/knowledge/markdown-sources`（MarkdownKnowledgeSourceController.java:24）

| 方法 | 路径 | 用途 | 所需权限 | 请求要点 | 响应要点 | 来源 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | /markdown-sources | Markdown 来源清单 | READ | 可选 `X-Branch-Context` | `MarkdownSourceList` | MarkdownKnowledgeSourceController.java:38-44 |
| POST | /markdown-sources/generate | 由 Markdown 生成知识卡 | MAINTAIN + CSRF | `GenerateRequest{sourcePath,expectedContentVersion,expectedContentHash}`，摘要须匹配 `^[0-9a-fA-F]{64}$`；可选 `X-Branch-Context` | `KnowledgeCard` | MarkdownKnowledgeSourceController.java:46-59,80-84 |
| POST | /markdown-sources/generate-pending | 批量生成待处理来源 | MAINTAIN + CSRF | `GeneratePendingRequest{expectedContentVersion}`；可选 `X-Branch-Context` | `BatchGenerationResult` | MarkdownKnowledgeSourceController.java:61-71,86 |

## 10. 图谱

### 10.1 `/api/repositories/{repoId}/codegraph`（CodeGraphController.java:23）

| 方法 | 路径 | 用途 | 所需权限 | 请求要点 | 响应要点 | 来源 |
| --- | --- | --- | --- | --- | --- | --- |
| POST | /codegraph/build | 构建代码图谱 | MAINTAIN + CSRF | 可选 `X-Branch-Context`（存在时走分支图谱任务） | `IndexJobResponse`；HTTP 202 | CodeGraphController.java:42-52 |
| GET | /codegraph/latest | 读取最新图谱产物 | READ | 可选 `X-Branch-Context` | `Artifact` | CodeGraphController.java:54-64 |
| GET | /codegraph/impact | 符号影响面分析 | READ | `symbol`（必填）、`depth=3`；可选 `X-Branch-Context` | `CodeGraphPropagation` | CodeGraphController.java:66-80 |
| GET | /codegraph/explore | 图谱浏览 | READ；`query` 或 `module` 长度 > 500 时 400 | `module=""`、`query=""`；可选 `X-Branch-Context` | `CodeGraphExplorer.View` | CodeGraphController.java:82-98 |

## 11. MCP

| 方法 | 路径 | 用途 | 所需权限 | 请求要点 | 响应要点 | 来源 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | /api/mcp | 声明本端点不提供 SSE 流 | 账户访问令牌（Bearer） | - | 无响应体；HTTP 405，`Allow: POST` | McpController.java:39-42 |
| POST | /api/mcp | JSON-RPC 2.0 消息端点 | 账户访问令牌（Bearer）；`Origin` 必须与请求自身同源 | JSON-RPC 请求体；可选头 `MCP-Protocol-Version`，支持 `2025-03-26`、`2025-06-18`、`2025-11-25` | JSON-RPC 响应；`Cache-Control: no-store` | McpController.java:44-150；AccessTokenInterceptor.java:26-35,55-75 |

- 支持的方法：`initialize`、`ping`、`tools/list`、`tools/call`；其他方法返回错误码 -32601
  （McpController.java:62-77,142-144）。
- 非法 JSON 返回 -32700；非法请求体返回 -32600；未知工具返回 -32602
  （McpController.java:48-59,82,152-155）。
- 工具目录来自 classpath 资源 `mcp-tools.json`，启用类校验类型、枚举、长度、区间与 `anyOf`
  （`backend/src/main/java/com/analyzercoder/application/mcp/McpToolCatalog.java:15-19,25-105`）。
- 目录中共 12 个工具：`search_project`、`resolve_project_context`、`list_codegraph_scopes`、
  `codegraph_explore`、`codegraph_node`、`codegraph_search`、`codegraph_callers`、
  `codegraph_callees`、`codegraph_impact`、`codegraph_files`、`codegraph_status`、`codegraph_affected`
  （`backend/src/main/resources/mcp-tools.json:1-517`）。
- 工具执行的服务端错误按类型分类：权限/参数错误回显安全文本，图谱错误回显 `code: message`，
  其余异常统一记录日志并只返回 `MCP_INTERNAL_ERROR: 工具执行失败，请查看服务端日志`
  （McpController.java:99-140）。

## 12. 配置与审计

### 12.1 `/api/settings/llm`（LlmSettingsController.java:19）

| 方法 | 路径 | 用途 | 所需权限 | 请求要点 | 响应要点 | 来源 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | /provider | 读取最新模型配置 | 超级管理员 | - | `ProviderView` | LlmSettingsController.java:27-31；LlmSettingsService.java:1092 |
| GET | /provider/versions | 读取配置版本列表 | 超级管理员 | - | `List<ProviderView>` | LlmSettingsController.java:33-37 |
| GET | /providers | 读取全部 provider | 超级管理员 | - | `List<ProviderView>` | LlmSettingsController.java:39-43 |
| POST | /providers | 新建 provider | 超级管理员 + CSRF | `ProviderInput` | `ProviderView` | LlmSettingsController.java:45-50；LlmSettingsService.java:1037 |
| PUT | /providers/{configId} | 更新 provider | 超级管理员 + CSRF | `ProviderInput` | `ProviderView` | LlmSettingsController.java:52-59 |
| PUT | /provider | 保存 provider（旧版路径） | 超级管理员 + CSRF | `ProviderInput` | `ProviderView` | LlmSettingsController.java:99-104 |
| GET | /vector-models | 向量模型列表 | 超级管理员 | - | `List<VectorModelView>` | LlmSettingsController.java:61-65；LlmSettingsService.java:1060 |
| POST | /vector-models | 新建向量模型 | 超级管理员 + CSRF | `VectorModelInput` | `VectorModelView` | LlmSettingsController.java:67-72；LlmSettingsService.java:1050 |
| PUT | /vector-models/{id} | 更新向量模型 | 超级管理员 + CSRF | `VectorModelInput` | `VectorModelView` | LlmSettingsController.java:74-81 |
| POST | /vector-models/{id}/activate | 激活向量模型 | 超级管理员 + CSRF | `expectedActivationVersion`（查询参数，必填） | `VectorModelView` | LlmSettingsController.java:83-90 |
| POST | /vector-models/{id}/check | 检测向量模型可用性 | 超级管理员 + CSRF | - | `VectorModelCheckView` | LlmSettingsController.java:92-97；LlmSettingsService.java:1080 |
| POST | /connectivity-checks | 发起模型连通性检测 | 超级管理员 + CSRF | `ConnectivityCheckRequest{configId,candidate}` | `CheckView` | LlmSettingsController.java:106-112；LlmSettingsService.java:1090,1116 |
| GET | /connectivity-checks/{checkId} | 查询连通性检测结果 | 超级管理员 | - | `CheckView` | LlmSettingsController.java:114-119 |
| POST | /connectivity-checks/{checkId}/cancel | 取消连通性检测 | 超级管理员 + CSRF | - | `CheckView` | LlmSettingsController.java:121-126 |

### 12.2 审计

- `GET /api/accounts/audit` 是唯一的审计读取端点，要求超级管理员，
  返回 `AuditView`，`limit` 被钳制到 1–200（AccountController.java:110-117；AuthService.java:421-436）。
- 账号、令牌、仓库治理、仓库删除与凭据绑定等动作通过 `AuthService.audit` 写入审计事件
  （`backend/src/main/java/com/analyzercoder/security/AuthService.java:438-455`）。
  审计范围与事件类型的完整清单见账号相关文档。

### 12.3 健康检查

| 方法 | 路径 | 用途 | 所需权限 | 请求要点 | 响应要点 | 来源 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | /api/health | 简单存活探针 | 公开 | - | `HealthResponse{status,timestamp}` | HealthController.java:13-16；SessionInterceptor.java:24 |
| GET | /actuator/health（含子路径） | Spring Boot Actuator 健康端点 | 公开 | - | Actuator 标准响应 | SessionInterceptor.java:27；backend/src/main/resources/application.yml:43-48 |

## 13. 路径中带占位符的端点

以下端点路径含 `{}` 占位符。占位符均为 UUID，唯一例外是
`/api/repositories/{repoId}/knowledge/{cardId}/history/{revision}/restore`
的 `revision`（int）。

- 账号与令牌：`/api/accounts/{accountId}`、`/api/accounts/{accountId}/reset-password`、
  `/api/accounts/{accountId}/unlock`、`/api/accounts/{accountId}/permissions`、
  `/api/accounts/{accountId}/permissions/{repositoryId}`、
  `/api/accounts/{accountId}/access-tokens`、`/api/accounts/{accountId}/access-tokens/{tokenId}`
  （AccountController.java:62-108；AccountAccessTokenController.java:20-58）。
- 仓库与来源：`/api/repositories/{repositoryId}`、`/api/repositories/{repositoryId}/rescan`、
  `/api/repository-imports/jobs/{id}`、`/api/repository-imports/jobs/{id}/cancel`、
  `/api/repository-project-drafts/{id}/source`、`/api/repository-project-drafts/{id}/complete`、
  `/api/repository-credentials/{id}` 及其全部子路径、
  `/api/repositories/{repositoryId}/credential`、
  `/api/repositories/{repositoryId}/governance/members/{accountId}`
  （RepositoryController.java:95-183；RepositorySourceImportController.java:58-68；
  RepositoryProjectDraftController.java:37-53；RepositoryCredentialController.java:39-76；
  RepositoryCredentialBindingController.java:19-50；RepositoryGovernanceController.java:45-74）。
- 分支与内容版本：`/api/repositories/{repositoryId}/branches/{branchId}/archive`、
  `/restore`、`/prepare`、`/index-status`、`/code-jobs`、
  `/branches/{branchId}/contentVersions/{contentVersion}/retention`、
  `DELETE /branches/{branchId}/contentVersions/{contentVersion}`、
  `/knowledge/{cardId}/branch-scope`、`/knowledge/{cardId}/branch-validation`
  （RepositoryBranchController.java:65-178；BranchCodeOperationsController.java:42-68）。
- 代码与知识：`/api/repositories/{repositoryId}/code-evidence-context`、
  `/api/repositories/{repositoryId}/knowledge/attachments/{attachmentId}`、
  `/api/repositories/{repoId}/knowledge/{cardId}`（PUT）、
  `/api/repositories/{repoId}/knowledge/{cardId}/review`、`/publication`、`/source-drift`、
  `/source-review`、`/history`、`/history/{revision}/restore`、
  `/api/repositories/{repositoryId}/chunks/{chunkId}/graph-target`
  （KnowledgeAttachmentController.java:47-65；IntelligenceController.java:203-231；
  KnowledgeDriftController.java:39-70；KnowledgeCardHistoryController.java:31-50；
  IntelligenceController.java:155-163）。
- 索引与任务：`/api/index-jobs/{jobId}`、`/api/index-jobs/{jobId}/cancel`、
  `/api/index-jobs/{jobId}/retries`、`/api/repositories/{repositoryId}/index-jobs`
  （IndexController.java:73-119）。
- 检索与问答：`/api/repositories/{repoId}/qa/records/{conversationId}`（GET/PATCH/DELETE）
  （IntelligenceController.java:117-153）。
- 配置：`/api/settings/llm/providers/{configId}`、`/api/settings/llm/vector-models/{id}`、
  `/activate`、`/check`、`/api/settings/llm/connectivity-checks/{checkId}`、
  `/connectivity-checks/{checkId}/cancel`（LlmSettingsController.java:52-126）。
- 无占位符的端点：`/api/health`、`/api/auth/*`、`/api/auth/preferences/*`、
  `/api/accounts`、`/api/accounts/page`、`/api/accounts/audit`、
  `/api/repositories`、`/api/repositories/page`、`/api/repository-credentials`、
  `/api/repository-imports/remote-jobs`、`/jobs`、`/remote`、`/zip`、
  `/api/repository-project-drafts`、`/api/index-jobs`、`/api/index-jobs/page`、
  `/api/settings`、`/api/settings/llm/*`（除上列带参者）、`/api/mcp`。

## 14. 消费 multipart/form-data 的端点

当前实现中只有 2 个端点在方法签名上绑定 multipart：

| 方法 | 路径 | 消费类型 | 表单字段 | 来源 |
| --- | --- | --- | --- | --- |
| POST | /api/repository-imports/zip | `multipart/form-data` | `name`（`@RequestParam`）、`file`（`@RequestPart MultipartFile`） | RepositorySourceImportController.java:87-91 |
| POST | /api/repositories/{repositoryId}/knowledge/attachments | multipart（由 `@RequestPart` 推断） | `file`（`@RequestPart("file") MultipartFile`） | KnowledgeAttachmentController.java:37-41 |

## 15. 令牌可调用的端点

`AccessTokenInterceptor` 对携带 `Authorization: Bearer` 的请求做路径白名单校验；
白名单之外的路径一律返回 403 `TOKEN_ENDPOINT_FORBIDDEN`
（`backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:29-30,50-53`）。

仅接受账户访问令牌的端点（会话 Cookie 无法替代）：

| 方法 | 路径 | 说明 | 来源 |
| --- | --- | --- | --- |
| GET | /api/mcp | 因 `mcp` 分支强制要求 Bearer，无令牌时返回 401 `ACCESS_TOKEN_REQUIRED` | AccessTokenInterceptor.java:26-35 |
| POST | /api/mcp | 同上 | AccessTokenInterceptor.java:26-35 |

允许访问令牌、同时也接受会话 Cookie 的端点：

| 方法 | 路径 | 来源 |
| --- | --- | --- |
| GET | /api/repositories/{uuid}/evidence-search | AccessTokenInterceptor.java:13-14,51 |
| POST | /api/repositories/{uuid}/contexts | AccessTokenInterceptor.java:15,52 |

- 令牌格式与校验：前缀 `acp_` + 43 位 URL-safe Base64，库中仅存 SHA-256 摘要；
  已撤销、已过期或账户停用时返回 401 `ACCESS_TOKEN_INVALID`
  （AccessTokenService.java:43-45,71-77,104-106）。
- 令牌只代表账号身份，仓库权限仍在每次业务请求中由 `AccessControlService` 实时判定
  （AccessTokenService.java:16；AccessControlService.java:19-35）。
- Node stdio MCP 适配器调用的正是 `POST /api/repositories/{id}/contexts`、
  `GET /api/repositories/{id}/evidence-search` 与 `POST /api/mcp`
  （`mcp-server/src/server.mjs:33,37,53,83`）。

## 16. 当前没有前端或 MCP 调用方的端点

核实方法：在 `frontend/src` 与 `mcp-server/src` 中检索每个端点路径字面量
（`frontend/src/api/*.ts`、`frontend/src/**/*.vue`、`mcp-server/src/*.mjs`）。
下表只列出两个调用方集合都不命中的端点。标注"旧版"的依据是该端点存在语义等价的替代路径，
且前端已全部改用替代路径。

| 方法 | 路径 | 判定 | 依据 |
| --- | --- | --- | --- |
| GET | /api/repositories/{repoId}/hybrid-search | 旧版：已被 `evidence-search` 取代 | 前端仅调用 `evidence-search`（frontend/src/api/intelligence.ts:437-442）；`hybrid-search` 无任何引用 |
| GET | /api/repositories/{repoId}/graph | 旧版：前端改用 `codegraph/impact` | frontend/src/api/intelligence.ts:477-481 的 `graph()` 实际请求 `/codegraph/impact` |
| POST | /api/repository-imports/remote | 旧版：被 `/remote-jobs` 取代 | 前端只调用 `remote-jobs`、`jobs/{id}`、`zip`（frontend/src/api/sourceImports.ts:22-28） |
| GET | /api/settings/llm/provider | 旧版：被 `/providers` 取代 | 前端只调用 `/providers` 系列（frontend/src/api/llmSettings.ts:115-126） |
| GET | /api/settings/llm/provider/versions | 无调用方 | frontend/src/api/llmSettings.ts:115-153 未引用 |
| PUT | /api/settings/llm/provider | 旧版：被 `PUT /providers/{configId}` 取代 | frontend/src/api/llmSettings.ts:122-126 |
| PUT | /api/accounts/{accountId}/permissions/{repositoryId} | 无调用方，且实现恒返回 409 | AuthService.java:411-419 直接抛 `USE_REPOSITORY_GOVERNANCE` |
| GET | /api/accounts/{accountId}/permissions | 无调用方 | frontend/src/api/accounts.ts:7-19 无此方法 |
| GET | /api/accounts | 无调用方：前端使用 `/api/accounts/page` | frontend/src/api/accounts.ts:8-12 |
| GET | /api/repositories/{repositoryId}/index/status | 无调用方：前端读 `/api/index-jobs/{jobId}` | frontend/src/api/repositories.ts:205-207 |
| GET | /api/repositories/{repositoryId}/index-jobs | 无调用方 | frontend/src/api/indexJobs.ts:5-15 只用 `/page`、`/cancel`、`/retries` |
| GET | /api/index-jobs | 无调用方（且要求超级管理员） | 同上 |
| GET | /api/settings、PUT /api/settings | 无调用方 | frontend/src/api 下无 `/api/settings'` 裸路径引用 |
| GET | /api/repository-imports/jobs | 无调用方 | frontend/src/api/sourceImports.ts:21-29 |
| POST | /api/repository-imports/jobs/{id}/cancel | 无调用方 | 同上 |
| POST | /api/repositories/{repositoryId}/branches/{branchId}/prepare | 无调用方：前端走 `code-jobs` 的 `PREPARE` | frontend/src/features/branches/BranchListTable.vue:64 发出 `operate(branchId,'PREPARE')`，经 frontend/src/api/branches.ts:47-48 打到 `/code-jobs` |
| GET | /api/repositories/{repositoryId} | 无调用方：前端只有 PATCH 与 DELETE | frontend/src/api/repositories.ts:185,195 |
| GET | /api/settings/llm/connectivity-checks/{checkId}/cancel | 无调用方 | frontend/src/api/llmSettings.ts:127-133 只发起与查询检测 |

补充说明：

- `GET /api/repositories/{repositoryId}/code-evidence-context` **不在**上表内：
  前端有调用方，`frontend/src/api/intelligence.ts:443-450` 的 `codeEvidenceContext()`
  请求该路径，并被文件证据面板 `frontend/src/features/code/CodeEvidencePanel.vue:13`
  使用，因此不是死接口。
- `GET /api/health` 无前端调用方，但被健康探针与 `scripts/check-runtime.mjs` 之外的
  部署脚本使用，属于探针端点而非死接口（HealthController.java:10-18）。
- `GET /api/mcp` 无调用方，是协议层桩：显式声明不支持 SSE 并返回 405，
  供 MCP 客户端探测（McpController.java:39-42）。
- `POST /api/mcp` 由 `mcp-server/src/server.mjs:83` 与外部 MCP 客户端调用，不属于死接口。
