import { computed, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import type {
  AnalyzerForm,
  ApiMappingResult,
  AuthUser,
  ConsoleSection,
  GraphChainDetail,
  GraphChainFilters,
  GraphChainSummary,
  GraphOverview,
  GraphRecord,
  GraphRecordFilters,
  GraphRelation,
  IndexRecord,
  IndexRecordFilters,
  IndexStatus,
  KnowledgeAsset,
  KnowledgeAssetFilters,
  KnowledgeAssetForm,
  KnowledgeAssetPagination,
  KnowledgeFile,
  KnowledgeTemplate,
  KnowledgeTemplateForm,
  OutputType,
  ProjectForm,
  ProjectRecord,
  QueryEvidence,
  QueryResult,
  RagFlow,
  UserEditForm,
  UserForm
} from '../types'

interface AnalyzeResponse {
  output: string
  savedPath: string
  externalSync?: {
    enabled: boolean
    ok?: boolean
    error?: string
    qdrant?: Record<string, unknown>
    neo4j?: Record<string, unknown>
  }
}

interface AnalysisResultResponse {
  exists: boolean
  output: string
  savedPath: string
  mode: AnalyzerForm['mode']
  updatedAt: string
}

interface IndexResponse {
  message: string
  savedPath: string
}

interface IndexStatusResponse extends IndexStatus {}

interface IndexRecordsResponse {
  records: IndexRecord[]
  total: number
  limit: number
  offset: number
  store: string
}

interface GraphOverviewResponse extends GraphOverview {}

interface GraphRecordsResponse {
  records: GraphRecord[]
  total: number
  limit: number
  offset: number
  projectId: string
}

interface GraphRelationsResponse {
  relations: GraphRelation[]
  limit: number
  projectId: string
}

interface GraphChainsResponse {
  chains: GraphChainSummary[]
  limit: number
  projectId: string
}

interface GraphChainResponse extends GraphChainDetail {}

interface QueryResponse {
  results: QueryResult[]
  evidence: QueryEvidence
  rag: RagFlow
  savedPath: string
}

interface ProjectsResponse {
  projects: ProjectRecord[]
}

interface ProjectResponse {
  project: ProjectRecord
}

interface AuthResponse {
  user: AuthUser
}

interface UsersResponse {
  users: AuthUser[]
}

interface KnowledgeFilesResponse {
  files: KnowledgeFile[]
  root: string
}

interface KnowledgeTemplatesResponse {
  templates: KnowledgeTemplate[]
}

interface KnowledgeTemplateResponse {
  template?: KnowledgeTemplate
  templates: KnowledgeTemplate[]
}

interface KnowledgeFileResponse {
  file: KnowledgeFile
  content: string
  root: string
}

interface KnowledgeAssetsResponse {
  assets: KnowledgeAsset[]
  total: number
  page: number
  pageSize: number
  types: string[]
  statuses: string[]
}

interface KnowledgeAssetResponse {
  asset?: KnowledgeAsset
  assets: KnowledgeAsset[]
}

type ApiPayload = Record<string, unknown>

const ACTIVE_SECTION_STORAGE_KEY = 'analyzer-console-active-section'
const CURRENT_USER_STORAGE_KEY = 'analyzer-console-current-user'
const consoleSections: readonly ConsoleSection[] = [
  'projects',
  'accounts',
  'assets',
  'evidence',
  'knowledge',
  'analysis',
  'graph',
  'api-map',
  'vectors',
  'search'
]

const collapsedSectionMap: Partial<Record<ConsoleSection, ConsoleSection>> = {
  evidence: 'analysis'
}

async function readJsonResponse<T>(response: Response): Promise<T> {
  const text = await response.text()
  let data: unknown = {}

  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      const preview = text.replace(/\s+/g, ' ').slice(0, 80)
      throw new Error(`接口未返回 JSON：${response.status} ${preview}`)
    }
  }

  if (!response.ok) {
    const message =
      typeof data === 'object' && data !== null && 'error' in data
        ? String((data as ApiPayload).error)
        : `请求失败：${response.status}`
    throw new Error(message)
  }

  return data as T
}

function resolveAnalysisOutputType(mode: AnalyzerForm['mode']): OutputType {
  if (mode === 'report') return 'markdown'
  return 'mermaid'
}

function emptyAnalysisResult(mode: AnalyzerForm['mode']) {
  return {
    output: '',
    savedPath: '',
    outputType: resolveAnalysisOutputType(mode),
    updatedAt: ''
  }
}

function readStoredActiveSection(): ConsoleSection {
  try {
    const section = window.localStorage.getItem(ACTIVE_SECTION_STORAGE_KEY)
    if (section === 'review') return 'assets'
    if (section === 'settings') return 'projects'
    if (section === 'templates') return 'knowledge'
    if (!consoleSections.includes(section as ConsoleSection)) return 'assets'
    return collapsedSectionMap[section as ConsoleSection] ?? (section as ConsoleSection)
  } catch {
    return 'assets'
  }
}

function writeStoredActiveSection(section: ConsoleSection) {
  try {
    window.localStorage.setItem(ACTIVE_SECTION_STORAGE_KEY, section)
  } catch {
    // 本地偏好保存失败不影响控制台使用。
  }
}

function isStoredUser(value: unknown): value is AuthUser {
  if (typeof value !== 'object' || value === null) return false
  const user = value as Partial<AuthUser>
  return (
    typeof user.id === 'string' &&
    typeof user.username === 'string' &&
    typeof user.displayName === 'string' &&
    typeof user.isAdmin === 'boolean' &&
    typeof user.lastProjectId === 'string'
  )
}

function readStoredCurrentUser(): AuthUser | null {
  try {
    const raw = window.localStorage.getItem(CURRENT_USER_STORAGE_KEY)
    if (!raw) return null
    const user = JSON.parse(raw) as unknown
    return isStoredUser(user) ? user : null
  } catch {
    return null
  }
}

function writeStoredCurrentUser(user: AuthUser) {
  try {
    window.localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(user))
  } catch {
    // 会话缓存失败时退化为接口恢复。
  }
}

function clearStoredCurrentUser() {
  try {
    window.localStorage.removeItem(CURRENT_USER_STORAGE_KEY)
  } catch {
    // 清理失败不影响退出流程。
  }
}

export function useAnalyzerConsole() {
  const form = reactive<AnalyzerForm>({
    projectId: '',
    path: '.',
    codePath: '.',
    frontendPath: 'web/frontend/src',
    backendPath: 'src/main/java',
    kbPath: 'docs',
    source: 'code',
    mode: 'report',
    store: '',
    query: 'register user endpoint',
    filterSource: ''
  })
  const projectForm = reactive<ProjectForm>({
    name: '',
    gitUrl: '',
    branch: ''
  })
  const loginForm = reactive({
    username: 'admin',
    password: 'admin123'
  })
  const userForm = reactive<UserForm>({
    username: '',
    displayName: '',
    password: '',
    projectIds: [],
    isAdmin: false
  })
  const userEditForm = reactive<UserEditForm>({
    id: '',
    username: '',
    displayName: '',
    isAdmin: false
  })
  const templateForm = reactive<KnowledgeTemplateForm>({
    id: '',
    name: '',
    path: '',
    content: ''
  })
  const assetForm = reactive<KnowledgeAssetForm>({
    id: '',
    type: 'business_rule',
    title: '',
    summary: '',
    content: '',
    status: 'draft',
    ownerUserId: '',
    reviewerUserId: '',
    tagsText: '',
    evidence: [],
    sourcePath: '',
    reviewDueAt: ''
  })
  const assetFilters = reactive<KnowledgeAssetFilters>({
    type: '',
    status: '',
    query: ''
  })
  const assetPagination = reactive<KnowledgeAssetPagination>({
    page: 1,
    pageSize: 20,
    total: 0
  })

  const status = shallowRef('未连接')
  const busy = shallowRef(false)
  const projectBusy = shallowRef(false)
  const kbBusy = shallowRef(false)
  const assetBusy = shallowRef(false)
  const authBusy = shallowRef(false)
  const authReady = shallowRef(false)
  const analysisResultsByMode = reactive({
    report: emptyAnalysisResult('report'),
    graph: emptyAnalysisResult('graph')
  })
  const indexOutput = shallowRef('')
  const indexSavedPath = shallowRef('')
  const indexStatus = shallowRef<IndexStatus | null>(null)
  const indexRecords = ref<IndexRecord[]>([])
  const indexRecordTotal = shallowRef(0)
  const indexRecordFilters = reactive<IndexRecordFilters>({
    source: '',
    kind: '',
    query: ''
  })
  const indexRecordPage = shallowRef(1)
  const indexRecordPageSize = shallowRef(20)
  const graphOverview = shallowRef<GraphOverview | null>(null)
  const graphRecords = ref<GraphRecord[]>([])
  const graphRelations = ref<GraphRelation[]>([])
  const graphRecordTotal = shallowRef(0)
  const graphRecordFilters = reactive<GraphRecordFilters>({
    type: '',
    query: ''
  })
  const graphRecordPage = shallowRef(1)
  const graphRecordPageSize = shallowRef(100)
  const graphChains = ref<GraphChainSummary[]>([])
  const selectedGraphChainId = shallowRef('')
  const selectedGraphChain = shallowRef<GraphChainDetail | null>(null)
  const graphChainFilters = reactive<GraphChainFilters>({
    type: '',
    query: ''
  })
  const searchResults = ref<QueryResult[]>([])
  const searchEvidence = ref<QueryEvidence | null>(null)
  const ragFlow = shallowRef<RagFlow | null>(null)
  const searchSavedPath = shallowRef('')
  const apiMapping = shallowRef<ApiMappingResult | null>(null)
  const apiMappingSavedPath = shallowRef('')
  const apiMappingMessage = shallowRef('')
  const kbFiles = ref<KnowledgeFile[]>([])
  const kbTemplates = ref<KnowledgeTemplate[]>([])
  const knowledgeAssets = ref<KnowledgeAsset[]>([])
  const selectedAssetId = shallowRef('')
  const selectedKbPath = shallowRef('')
  const kbDraftPath = shallowRef('domain/new-topic.md')
  const kbContent = shallowRef('')
  const kbRoot = shallowRef('')
  const kbMessage = shallowRef('')
  const templateMessage = shallowRef('')
  const assetMessage = shallowRef('')
  const projectMessage = shallowRef('')
  const projectMessageProjectId = shallowRef('')
  const projects = ref<ProjectRecord[]>([])
  const users = ref<AuthUser[]>([])
  const currentUser = ref<AuthUser | null>(readStoredCurrentUser())
  const authMessage = shallowRef('')
  let assetLoadSequence = 0
  const activeSection = shallowRef<ConsoleSection>(readStoredActiveSection())

  const selectedProject = computed(() =>
    projects.value.find((project) => project.id === form.projectId) ?? null
  )

  const analysisOutputTitle = computed(() => '分析结果')
  const currentAnalysisResult = computed(() => analysisResultsByMode[form.mode])
  const analysisOutput = computed(() => currentAnalysisResult.value.output)
  const analysisSavedPath = computed(() => currentAnalysisResult.value.savedPath)
  const analysisOutputType = computed(() => currentAnalysisResult.value.outputType)
  const analysisUpdatedAt = computed(() => currentAnalysisResult.value.updatedAt)

  async function requestJson<T>(url: string, payload: Record<string, unknown>): Promise<T> {
    busy.value = true
    try {
      const response = await fetch(url, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      })
      return await readJsonResponse<T>(response)
    } finally {
      busy.value = false
    }
  }

  async function getJson<T>(url: string): Promise<T> {
    const response = await fetch(url, { credentials: 'same-origin' })
    return await readJsonResponse<T>(response)
  }

  async function projectRequest<T>(url: string, payload: Record<string, unknown>): Promise<T> {
    projectBusy.value = true
    try {
      const response = await fetch(url, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      })
      return await readJsonResponse<T>(response)
    } finally {
      projectBusy.value = false
    }
  }

  function kbParams(extra: Record<string, string> = {}) {
    return new URLSearchParams({
      projectId: form.projectId,
      kbPath: form.kbPath,
      ...extra
    })
  }

  async function kbRequest<T>(url: string, options: RequestInit = {}): Promise<T> {
    kbBusy.value = true
    try {
      const response = await fetch(url, { credentials: 'same-origin', ...options })
      return await readJsonResponse<T>(response)
    } finally {
      kbBusy.value = false
    }
  }

  async function login() {
    authBusy.value = true
    authReady.value = true
    authMessage.value = ''
    try {
      const response = await fetch('/api/auth/login', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(loginForm)
      })
      const data = await readJsonResponse<AuthResponse>(response)
      currentUser.value = data.user
      writeStoredCurrentUser(data.user)
      if (!currentUser.value.isAdmin && activeSection.value === 'accounts') {
        activeSection.value = 'projects'
      }
      authMessage.value = ''
      await checkHealth()
      await loadProjects()
      await loadAnalysisResult()
      if (currentUser.value.isAdmin) {
        await loadUsers()
      }
      await loadKbFiles()
      await loadKbTemplates()
      await loadKnowledgeAssets()
      await loadIndexStatus()
      await loadIndexRecords()
    } catch (error) {
      authMessage.value = error instanceof Error ? error.message : '登录失败'
      currentUser.value = null
      clearStoredCurrentUser()
    } finally {
      authBusy.value = false
    }
  }

  async function logout() {
    authBusy.value = true
    authReady.value = true
    try {
      await fetch('/api/auth/logout', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' }
      })
    } finally {
      currentUser.value = null
      clearStoredCurrentUser()
      projects.value = []
      users.value = []
      form.projectId = ''
      activeSection.value = 'assets'
      selectedKbPath.value = ''
      kbContent.value = ''
      kbTemplates.value = []
      knowledgeAssets.value = []
      assetPagination.total = 0
      assetPagination.page = 1
      selectedAssetId.value = ''
      resetTemplateForm()
      resetAssetForm()
      authBusy.value = false
      authMessage.value = ''
    }
  }

  async function loadCurrentUser() {
    authReady.value = false
    try {
      const data = await getJson<AuthResponse>('/api/auth/me')
      currentUser.value = data.user
      writeStoredCurrentUser(data.user)
      if (!currentUser.value.isAdmin && activeSection.value === 'accounts') {
        activeSection.value = 'projects'
      }
      authReady.value = true
      await checkHealth()
      await loadProjects()
      await loadAnalysisResult()
      if (currentUser.value.isAdmin) {
        await loadUsers()
      }
      await loadKbFiles()
      await loadKbTemplates()
      await loadKnowledgeAssets()
      await loadIndexStatus()
      await loadIndexRecords()
    } catch {
      if (!authReady.value) {
        currentUser.value = null
        clearStoredCurrentUser()
        projects.value = []
        users.value = []
      }
    } finally {
      authReady.value = true
    }
  }

  async function loadUsers() {
    if (!currentUser.value?.isAdmin) return
    const data = await getJson<UsersResponse>('/api/users')
    users.value = data.users
  }

  async function createUser() {
    if (!currentUser.value?.isAdmin) return
    authBusy.value = true
    authMessage.value = ''
    try {
      const response = await fetch('/api/users', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(userForm)
      })
      const data = await readJsonResponse<AuthResponse>(response)
      users.value = [...users.value, data.user]
      userForm.username = ''
      userForm.displayName = ''
      userForm.password = ''
      userForm.projectIds = []
      userForm.isAdmin = false
      authMessage.value = '账号已创建'
    } catch (error) {
      authMessage.value = error instanceof Error ? error.message : '创建账号失败'
    } finally {
      authBusy.value = false
    }
  }

  async function updateUser() {
    if (!currentUser.value?.isAdmin || !userEditForm.id) return
    authBusy.value = true
    authMessage.value = ''
    try {
      const response = await fetch('/api/users', {
        method: 'PUT',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          id: userEditForm.id,
          username: userEditForm.username,
          displayName: userEditForm.displayName,
          isAdmin: userEditForm.isAdmin
        })
      })
      const data = await readJsonResponse<AuthResponse>(response)
      const updated = data.user
      users.value = users.value.map((user) => (user.id === updated.id ? updated : user))
      if (currentUser.value.id === updated.id) {
        currentUser.value = updated
      }
      authMessage.value = '账号信息已保存'
    } catch (error) {
      authMessage.value = error instanceof Error ? error.message : '保存账号失败'
    } finally {
      authBusy.value = false
    }
  }

  async function updateUserPassword(userId: string, password: string) {
    if (!currentUser.value?.isAdmin || !userId) return
    authBusy.value = true
    authMessage.value = ''
    try {
      const response = await fetch('/api/users/password', {
        method: 'PUT',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id: userId, password })
      })
      const data = await readJsonResponse<AuthResponse>(response)
      const updated = data.user
      users.value = users.value.map((user) => (user.id === updated.id ? updated : user))
      authMessage.value = '密码已修改'
    } catch (error) {
      authMessage.value = error instanceof Error ? error.message : '修改密码失败'
    } finally {
      authBusy.value = false
    }
  }

  async function updateUserAccess(userId: string, projectIds: string[]) {
    if (!currentUser.value?.isAdmin) return
    authBusy.value = true
    authMessage.value = ''
    try {
      const response = await fetch('/api/users/access', {
        method: 'PUT',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id: userId, projectIds })
      })
      const data = await readJsonResponse<AuthResponse>(response)
      const updated = data.user
      users.value = users.value.map((user) => (user.id === updated.id ? updated : user))
      authMessage.value = '授权已保存'
    } catch (error) {
      authMessage.value = error instanceof Error ? error.message : '保存授权失败'
    } finally {
      authBusy.value = false
    }
  }

  async function updateLastProject(projectId: string) {
    if (!currentUser.value) return
    try {
      const response = await fetch('/api/auth/last-project', {
        method: 'PUT',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ projectId })
      })
      const data = await readJsonResponse<AuthResponse>(response)
      currentUser.value = data.user
    } catch {
      // 选择项目不应被偏好保存失败阻断。
    }
  }

  async function checkHealth() {
    try {
      const response = await fetch('/api/health', { credentials: 'same-origin' })
      const data = await readJsonResponse<{ ok: boolean; workspace: string }>(response)
      status.value = data.ok ? '已连接' : '后端不可用'
    } catch {
      status.value = '后端未启动'
    }
  }

  async function loadProjects() {
    if (!currentUser.value) return
    try {
      const data = await getJson<ProjectsResponse>('/api/projects')
      projects.value = data.projects
      if (form.projectId && !projects.value.some((project) => project.id === form.projectId)) {
        form.projectId = ''
      }
      if (
        !form.projectId &&
        currentUser.value.lastProjectId &&
        projects.value.some((project) => project.id === currentUser.value?.lastProjectId)
      ) {
        form.projectId = currentUser.value.lastProjectId
      }
      if (!form.projectId && projects.value.length > 0 && !currentUser.value.isAdmin) {
        form.projectId = projects.value[0].id
      }
    } catch {
      projects.value = []
    }
  }

  async function loadKbFiles() {
    if (!currentUser.value || !form.projectId) {
      kbFiles.value = []
      kbRoot.value = ''
      return
    }
    try {
      const data = await kbRequest<KnowledgeFilesResponse>(`/api/kb/files?${kbParams()}`)
      kbFiles.value = data.files
      kbRoot.value = data.root
      if (selectedKbPath.value && !data.files.some((file) => file.path === selectedKbPath.value)) {
        selectedKbPath.value = ''
        kbContent.value = ''
      }
    } catch (error) {
      kbFiles.value = []
      kbMessage.value = error instanceof Error ? error.message : '知识库加载失败'
    }
  }

  async function loadKbTemplates() {
    if (!currentUser.value || !form.projectId) {
      kbTemplates.value = []
      return
    }
    try {
      const data = await kbRequest<KnowledgeTemplatesResponse>(`/api/kb/templates?${kbParams()}`)
      kbTemplates.value = data.templates
    } catch (error) {
      kbTemplates.value = []
      templateMessage.value = error instanceof Error ? error.message : '知识模板加载失败'
    }
  }

  function resetTemplateForm() {
    templateForm.id = ''
    templateForm.name = ''
    templateForm.path = ''
    templateForm.content = ''
  }

  function resetAssetForm() {
    selectedAssetId.value = ''
    assetForm.id = ''
    assetForm.type = 'business_rule'
    assetForm.title = ''
    assetForm.summary = ''
    assetForm.content = ''
    assetForm.status = 'draft'
    assetForm.ownerUserId = currentUser.value?.id ?? ''
    assetForm.reviewerUserId = ''
    assetForm.tagsText = ''
    assetForm.evidence = []
    assetForm.sourcePath = ''
    assetForm.reviewDueAt = ''
  }

  function editAsset(asset: KnowledgeAsset) {
    assetForm.id = asset.id
    assetForm.type = asset.type
    assetForm.title = asset.title
    assetForm.summary = asset.summary
    assetForm.content = asset.content
    assetForm.status = asset.status
    assetForm.ownerUserId = asset.ownerUserId
    assetForm.reviewerUserId = asset.reviewerUserId
    assetForm.tagsText = asset.tags.join(', ')
    assetForm.evidence = asset.evidence.map((item) => ({ ...item }))
    assetForm.sourcePath = asset.sourcePath
    assetForm.reviewDueAt = asset.reviewDueAt
    selectedAssetId.value = asset.id
  }

  function assetPayload() {
    return {
      projectId: form.projectId,
      type: assetForm.type,
      title: assetForm.title,
      summary: assetForm.summary,
      content: assetForm.content,
      status: assetForm.status,
      ownerUserId: assetForm.ownerUserId,
      reviewerUserId: assetForm.reviewerUserId,
      tags: assetForm.tagsText,
      evidence: assetForm.evidence,
      sourcePath: assetForm.sourcePath,
      reviewDueAt: assetForm.reviewDueAt
    }
  }

  function assetParams() {
    const params = new URLSearchParams({
      projectId: form.projectId,
      page: String(assetPagination.page),
      pageSize: String(assetPagination.pageSize)
    })
    if (assetFilters.type) params.set('type', assetFilters.type)
    if (assetFilters.status) params.set('status', assetFilters.status)
    if (assetFilters.query) params.set('query', assetFilters.query)
    return params
  }

  async function loadKnowledgeAssets() {
    if (!currentUser.value || !form.projectId) {
      assetLoadSequence += 1
      knowledgeAssets.value = []
      assetPagination.total = 0
      assetBusy.value = false
      return
    }
    const sequence = ++assetLoadSequence
    assetBusy.value = true
    try {
      const response = await fetch(`/api/knowledge/assets?${assetParams()}`, { credentials: 'same-origin' })
      const data = await readJsonResponse<KnowledgeAssetsResponse>(response)
      if (sequence !== assetLoadSequence) return
      const pageCount = Math.max(1, Math.ceil(data.total / data.pageSize))
      if (data.assets.length === 0 && data.total > 0 && data.page > pageCount) {
        assetPagination.page = pageCount
        await loadKnowledgeAssets()
        return
      }
      knowledgeAssets.value = data.assets
      assetPagination.total = data.total
      assetPagination.page = data.page
      assetPagination.pageSize = data.pageSize
      if (selectedAssetId.value && !data.assets.some((asset) => asset.id === selectedAssetId.value)) {
        selectedAssetId.value = ''
        resetAssetForm()
      }
    } catch (error) {
      if (sequence !== assetLoadSequence) return
      knowledgeAssets.value = []
      assetPagination.total = 0
      assetMessage.value = error instanceof Error ? error.message : '知识资产加载失败'
    } finally {
      if (sequence === assetLoadSequence) assetBusy.value = false
    }
  }

  async function changeAssetPage(page: number) {
    assetPagination.page = page
    await loadKnowledgeAssets()
  }

  async function changeAssetPageSize(pageSize: number) {
    assetPagination.pageSize = pageSize
    assetPagination.page = 1
    await loadKnowledgeAssets()
  }

  async function applyAssetFilters() {
    assetPagination.page = 1
    await loadKnowledgeAssets()
  }

  async function createKnowledgeAsset() {
    const data = await kbRequest<KnowledgeAssetResponse>('/api/knowledge/assets', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(assetPayload())
    })
    if (data.asset) editAsset(data.asset)
    assetPagination.page = Math.max(1, Math.ceil(data.assets.length / assetPagination.pageSize))
    await loadKnowledgeAssets()
    assetMessage.value = '知识资产已创建'
  }

  async function updateKnowledgeAsset() {
    if (!assetForm.id) return
    const data = await kbRequest<KnowledgeAssetResponse>(`/api/knowledge/assets/${encodeURIComponent(assetForm.id)}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(assetPayload())
    })
    if (data.asset) editAsset(data.asset)
    await loadKnowledgeAssets()
    assetMessage.value = '知识资产已保存'
  }

  async function deleteKnowledgeAsset(assetId: string) {
    const data = await kbRequest<{ deleted: string; assets: KnowledgeAsset[] }>(
      `/api/knowledge/assets/${encodeURIComponent(assetId)}`,
      {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ projectId: form.projectId })
      }
    )
    await loadKnowledgeAssets()
    if (assetForm.id === assetId) {
      selectedAssetId.value = ''
      resetAssetForm()
    }
    assetMessage.value = `已删除：${data.deleted}`
  }

  async function transitionKnowledgeAsset(assetId: string, action: 'confirm' | 'mark-stale') {
    const endpoint = action === 'confirm' ? 'confirm' : 'mark-stale'
    const data = await kbRequest<KnowledgeAssetResponse>(
      `/api/knowledge/assets/${encodeURIComponent(assetId)}/${endpoint}`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ projectId: form.projectId })
      }
    )
    if (data.asset && assetForm.id === assetId) editAsset(data.asset)
    await loadKnowledgeAssets()
    assetMessage.value = action === 'confirm' ? '知识资产已确认' : '知识资产已标记待复审'
  }

  async function createKbTemplate() {
    const data = await kbRequest<KnowledgeTemplateResponse>('/api/kb/templates', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        projectId: form.projectId,
        kbPath: form.kbPath,
        name: templateForm.name,
        path: templateForm.path,
        content: templateForm.content
      })
    })
    kbTemplates.value = data.templates
    templateMessage.value = '模板已创建'
    resetTemplateForm()
  }

  async function updateKbTemplate() {
    if (!templateForm.id) return
    const data = await kbRequest<KnowledgeTemplateResponse>('/api/kb/template', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        projectId: form.projectId,
        kbPath: form.kbPath,
        id: templateForm.id,
        name: templateForm.name,
        path: templateForm.path,
        content: templateForm.content
      })
    })
    kbTemplates.value = data.templates
    templateMessage.value = '模板已保存'
  }

  async function deleteKbTemplate(templateId: string) {
    const data = await kbRequest<KnowledgeTemplateResponse>('/api/kb/template', {
      method: 'DELETE',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        projectId: form.projectId,
        kbPath: form.kbPath,
        id: templateId
      })
    })
    kbTemplates.value = data.templates
    if (templateForm.id === templateId) {
      resetTemplateForm()
    }
    templateMessage.value = '模板已删除'
  }

  async function loadKbFile(path: string) {
    const data = await kbRequest<KnowledgeFileResponse>(`/api/kb/file?${kbParams({ path })}`)
    selectedKbPath.value = data.file.path
    kbDraftPath.value = data.file.path
    kbContent.value = data.content
    kbRoot.value = data.root
    kbMessage.value = `已打开：${data.file.path}`
  }

  async function createKbFile(path: string, content: string) {
    const data = await kbRequest<KnowledgeFileResponse>('/api/kb/files', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        projectId: form.projectId,
        kbPath: form.kbPath,
        path,
        content
      })
    })
    selectedKbPath.value = data.file.path
    kbDraftPath.value = data.file.path
    kbContent.value = data.content
    kbRoot.value = data.root
    kbMessage.value = `已创建：${data.file.path}`
    await loadKbFiles()
  }

  async function saveKbFile() {
    const path = selectedKbPath.value || kbDraftPath.value
    const data = await kbRequest<KnowledgeFileResponse>('/api/kb/file', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        projectId: form.projectId,
        kbPath: form.kbPath,
        path,
        content: kbContent.value
      })
    })
    selectedKbPath.value = data.file.path
    kbDraftPath.value = data.file.path
    kbContent.value = data.content
    kbRoot.value = data.root
    kbMessage.value = `已保存：${data.file.path}`
    await loadKbFiles()
  }

  async function deleteKbFile(path: string) {
    const data = await kbRequest<{ deleted: string }>('/api/kb/file', {
      method: 'DELETE',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        projectId: form.projectId,
        kbPath: form.kbPath,
        path
      })
    })
    if (selectedKbPath.value === path) {
      selectedKbPath.value = ''
      kbContent.value = ''
    }
    kbMessage.value = `已删除：${data.deleted}`
    await loadKbFiles()
  }

  async function rebuildKnowledgeIndex() {
    const data = await requestJson<IndexResponse>('/api/index', {
      path: form.path,
      codePath: form.codePath,
      kbPath: form.kbPath,
      projectId: form.projectId,
      source: 'mixed',
      store: null
    })
    kbMessage.value = data.message
    await loadIndexStatus()
  }

  async function createProject() {
    projectMessage.value = ''
    projectMessageProjectId.value = ''
    const data = await projectRequest<ProjectResponse>('/api/projects', {
      name: projectForm.name,
      gitUrl: projectForm.gitUrl,
      branch: projectForm.branch || null
    })
    projects.value = [...projects.value, data.project]
    form.projectId = data.project.id
    activeSection.value = 'assets'
    form.path = '.'
    form.codePath = '.'
    form.kbPath = 'docs'
    form.store = ''
    projectForm.name = ''
    projectForm.gitUrl = ''
    projectForm.branch = ''
    if (currentUser.value?.isAdmin) {
      await loadUsers()
    }
  }

  async function pullProject() {
    if (!form.projectId) return
    const projectId = form.projectId
    projectMessage.value = ''
    projectMessageProjectId.value = ''
    const data = await projectRequest<ProjectResponse>(`/api/projects/${projectId}/pull`, {})
    projects.value = projects.value.map((project) =>
      project.id === data.project.id ? data.project : project
    )
    projectMessage.value = '更新成功'
    projectMessageProjectId.value = data.project.id
  }

  async function analyze() {
    activeSection.value = 'analysis'
    const mode = form.mode
    analysisResultsByMode[mode].savedPath = ''
    analysisResultsByMode[mode].updatedAt = ''
    const data = await requestJson<AnalyzeResponse>('/api/analyze', {
      projectId: form.projectId,
      mode,
      syncExternal: true
    })
    analysisResultsByMode[mode] = {
      output: data.output,
      savedPath: data.savedPath,
      outputType: resolveAnalysisOutputType(mode),
      updatedAt: new Date().toISOString()
    }
    await loadGraphData()
  }

  async function loadAnalysisResult(mode: AnalyzerForm['mode'] = form.mode) {
    if (!currentUser.value || !form.projectId) return
    const params = new URLSearchParams({
      projectId: form.projectId,
      mode
    })
    const data = await getJson<AnalysisResultResponse>(`/api/analysis/result?${params}`)
    analysisResultsByMode[mode] = data.exists
      ? {
          output: data.output,
          savedPath: data.savedPath,
          outputType: resolveAnalysisOutputType(mode),
          updatedAt: data.updatedAt
        }
      : emptyAnalysisResult(mode)
  }

  async function indexProject() {
    activeSection.value = 'vectors'
    indexSavedPath.value = ''
    const data = await requestJson<IndexResponse>('/api/index', {
      path: form.path,
      codePath: form.codePath,
      kbPath: form.kbPath,
      projectId: form.projectId,
      source: form.source,
      store: null
    })
    indexOutput.value = data.message
    indexSavedPath.value = data.savedPath
    await loadIndexStatus()
    await loadIndexRecords()
  }

  async function loadIndexStatus() {
    if (!currentUser.value || !form.projectId) {
      indexStatus.value = null
      return
    }
    const params = new URLSearchParams({ projectId: form.projectId })
    const data = await getJson<IndexStatusResponse>(`/api/index/status?${params}`)
    indexStatus.value = data
  }

  async function loadIndexRecords() {
    if (!currentUser.value || !form.projectId) {
      indexRecords.value = []
      indexRecordTotal.value = 0
      return
    }
    const params = new URLSearchParams({
      projectId: form.projectId,
      limit: String(indexRecordPageSize.value),
      offset: String((indexRecordPage.value - 1) * indexRecordPageSize.value)
    })
    if (indexRecordFilters.source) params.set('source', indexRecordFilters.source)
    if (indexRecordFilters.kind) params.set('kind', indexRecordFilters.kind)
    if (indexRecordFilters.query) params.set('query', indexRecordFilters.query)
    const data = await getJson<IndexRecordsResponse>(`/api/index/records?${params}`)
    indexRecords.value = data.records
    indexRecordTotal.value = data.total
  }

  async function loadGraphOverview() {
    if (!currentUser.value || !form.projectId) {
      graphOverview.value = null
      return
    }
    const params = new URLSearchParams({ projectId: form.projectId })
    graphOverview.value = await getJson<GraphOverviewResponse>(`/api/graph/overview?${params}`)
  }

  async function loadGraphRecords() {
    if (!currentUser.value || !form.projectId) {
      graphRecords.value = []
      graphRecordTotal.value = 0
      return
    }
    const params = new URLSearchParams({
      projectId: form.projectId,
      limit: String(graphRecordPageSize.value),
      offset: String((graphRecordPage.value - 1) * graphRecordPageSize.value)
    })
    if (graphRecordFilters.type) params.set('type', graphRecordFilters.type)
    if (graphRecordFilters.query) params.set('query', graphRecordFilters.query)
    const data = await getJson<GraphRecordsResponse>(`/api/graph/records?${params}`)
    graphRecords.value = data.records
    graphRecordTotal.value = data.total
  }

  async function loadGraphRelations() {
    if (!currentUser.value || !form.projectId) {
      graphRelations.value = []
      selectedGraphChain.value = null
      return
    }
    const params = new URLSearchParams({
      projectId: form.projectId,
      limit: '500'
    })
    const data = await getJson<GraphRelationsResponse>(`/api/graph/relations?${params}`)
    graphRelations.value = data.relations
  }

  async function loadGraphChains() {
    if (!currentUser.value || !form.projectId) {
      graphChains.value = []
      selectedGraphChainId.value = ''
      selectedGraphChain.value = null
      return
    }
    const params = new URLSearchParams({
      projectId: form.projectId,
      limit: '120'
    })
    if (graphChainFilters.type) params.set('type', graphChainFilters.type)
    if (graphChainFilters.query) params.set('query', graphChainFilters.query)
    try {
      const data = await getJson<GraphChainsResponse>(`/api/graph/chains?${params}`)
      graphChains.value = data.chains
    } catch {
      graphChains.value = []
      selectedGraphChainId.value = ''
      selectedGraphChain.value = null
      return
    }

    const nextChainId = graphChains.value.some((chain) => chain.id === selectedGraphChainId.value)
      ? selectedGraphChainId.value
      : graphChains.value[0]?.id ?? ''
    if (nextChainId) {
      await loadGraphChain(nextChainId)
    } else {
      selectedGraphChainId.value = ''
      selectedGraphChain.value = null
    }
  }

  async function loadGraphChain(chainId: string = selectedGraphChainId.value) {
    if (!currentUser.value || !form.projectId || !chainId) {
      selectedGraphChainId.value = ''
      selectedGraphChain.value = null
      return
    }
    selectedGraphChainId.value = chainId
    const params = new URLSearchParams({ projectId: form.projectId })
    try {
      selectedGraphChain.value = await getJson<GraphChainResponse>(
        `/api/graph/chains/${encodeURIComponent(chainId)}?${params}`
      )
    } catch {
      selectedGraphChainId.value = ''
      selectedGraphChain.value = null
    }
  }

  async function applyGraphChainFilters() {
    selectedGraphChainId.value = ''
    selectedGraphChain.value = null
    await loadGraphChains()
  }

  async function loadGraphData() {
    await loadGraphOverview()
    await loadGraphChains()
  }

  async function queryStore() {
    activeSection.value = 'search'
    searchSavedPath.value = ''
    searchEvidence.value = null
    ragFlow.value = null
    const data = await requestJson<QueryResponse>('/api/rag/search', {
      store: null,
      projectId: form.projectId,
      query: form.query,
      filterSource: form.filterSource || null,
      topK: 5
    })
    searchResults.value = data.results
    searchEvidence.value = data.evidence
    ragFlow.value = data.rag
    searchSavedPath.value = data.savedPath
  }

  async function buildApiMapping() {
    activeSection.value = 'api-map'
    apiMappingSavedPath.value = ''
    apiMappingMessage.value = ''
    try {
      const data = await requestJson<ApiMappingResult>('/api/api-map', {
        projectId: form.projectId,
        frontendPath: form.frontendPath,
        backendPath: form.backendPath
      })
      apiMapping.value = data
      apiMappingSavedPath.value = data.savedPath
      apiMappingMessage.value = '映射已生成'
    } catch (error) {
      apiMappingMessage.value = error instanceof Error ? error.message : '接口映射生成失败'
    }
  }

  async function refreshSectionData(section: ConsoleSection = activeSection.value) {
    if (!currentUser.value) return
    if (section === 'projects') {
      await loadProjects()
      return
    }
    if (section === 'accounts') {
      if (currentUser.value.isAdmin) {
        await loadUsers()
      }
      return
    }
    if (section === 'analysis') {
      await loadAnalysisResult()
      return
    }
    if (section === 'knowledge') {
      await loadKbFiles()
      await loadKbTemplates()
      return
    }
    if (section === 'assets') {
      await loadKnowledgeAssets()
      return
    }
    if (section === 'evidence') {
      await loadIndexStatus()
      return
    }
    if (section === 'vectors') {
      await loadIndexStatus()
      await loadIndexRecords()
      return
    }
    if (section === 'graph') {
      await loadGraphData()
      return
    }
    if (section === 'api-map') return
  }

  watch(
    () => form.projectId,
    (projectId, previousProjectId) => {
      if (projectId === previousProjectId) return
      for (const mode of Object.keys(analysisResultsByMode) as Array<AnalyzerForm['mode']>) {
        analysisResultsByMode[mode] = emptyAnalysisResult(mode)
      }
      indexOutput.value = ''
      indexSavedPath.value = ''
      indexStatus.value = null
      indexRecords.value = []
      indexRecordTotal.value = 0
      indexRecordPage.value = 1
      graphOverview.value = null
      graphRecords.value = []
      graphRelations.value = []
      graphRecordTotal.value = 0
      graphRecordPage.value = 1
      graphRecordFilters.type = ''
      graphRecordFilters.query = ''
      graphChains.value = []
      selectedGraphChainId.value = ''
      selectedGraphChain.value = null
      graphChainFilters.type = ''
      graphChainFilters.query = ''
      searchResults.value = []
      searchEvidence.value = null
      ragFlow.value = null
      searchSavedPath.value = ''
      apiMapping.value = null
      apiMappingSavedPath.value = ''
      apiMappingMessage.value = ''
      projectMessage.value = ''
      projectMessageProjectId.value = ''
      knowledgeAssets.value = []
      assetPagination.total = 0
      assetPagination.page = 1
      selectedAssetId.value = ''
      assetMessage.value = ''
      if (projectId) {
        form.path = '.'
        form.codePath = '.'
        form.frontendPath = 'web/frontend/src'
        form.backendPath = 'src/main/java'
        form.kbPath = 'docs'
        form.store = ''
      } else {
        form.path = '.'
        form.codePath = '.'
        form.frontendPath = 'web/frontend/src'
        form.backendPath = 'src/main/java'
        form.kbPath = 'docs'
        form.store = ''
      }
      selectedKbPath.value = ''
      kbContent.value = ''
      resetTemplateForm()
      resetAssetForm()
      updateLastProject(projectId)
      loadKbFiles()
      loadKbTemplates()
      loadKnowledgeAssets()
      loadAnalysisResult()
      loadIndexStatus()
      loadIndexRecords()
      if (activeSection.value === 'graph') {
        loadGraphData()
      }
    }
  )

  watch(
    () => form.mode,
    (mode) => {
      loadAnalysisResult(mode)
    }
  )

  watch(
    () => form.kbPath,
    () => {
      selectedKbPath.value = ''
      kbContent.value = ''
      resetTemplateForm()
      resetAssetForm()
      loadKbFiles()
      loadKbTemplates()
      loadKnowledgeAssets()
    }
  )

  watch(activeSection, (section) => {
    if (section === 'evidence') {
      activeSection.value = 'analysis'
      return
    }
    writeStoredActiveSection(section)
    refreshSectionData(section)
  })

  onMounted(() => {
    loadCurrentUser()
  })

  return {
    form,
    projectForm,
    loginForm,
    userForm,
    userEditForm,
    status,
    busy,
    projectBusy,
    kbBusy,
    assetBusy,
    authBusy,
    authReady,
    analysisOutput,
    analysisSavedPath,
    analysisOutputTitle,
    analysisOutputType,
    analysisUpdatedAt,
    indexOutput,
    indexSavedPath,
    indexStatus,
    indexRecords,
    indexRecordTotal,
    indexRecordFilters,
    indexRecordPage,
    indexRecordPageSize,
    graphOverview,
    graphRecords,
    graphRelations,
    graphRecordTotal,
    graphRecordFilters,
    graphRecordPage,
    graphRecordPageSize,
    graphChains,
    selectedGraphChainId,
    selectedGraphChain,
    graphChainFilters,
    searchResults,
    searchEvidence,
    ragFlow,
    searchSavedPath,
    apiMapping,
    apiMappingSavedPath,
    apiMappingMessage,
    kbFiles,
    kbTemplates,
    knowledgeAssets,
    selectedAssetId,
    selectedKbPath,
    kbDraftPath,
    kbContent,
    kbRoot,
    kbMessage,
    templateForm,
    templateMessage,
    assetForm,
    assetFilters,
    assetPagination,
    assetMessage,
    projectMessage,
    projectMessageProjectId,
    projects,
    users,
    currentUser,
    authMessage,
    selectedProject,
    activeSection,
    analyze,
    loadAnalysisResult,
    login,
    logout,
    indexProject,
    loadIndexStatus,
    loadIndexRecords,
    loadGraphData,
    loadGraphOverview,
    loadGraphRecords,
    loadGraphRelations,
    loadGraphChains,
    loadGraphChain,
    applyGraphChainFilters,
    queryStore,
    buildApiMapping,
    refreshSectionData,
    checkHealth,
    loadCurrentUser,
    loadProjects,
    createProject,
    pullProject,
    loadUsers,
    createUser,
    updateUser,
    updateUserPassword,
    updateUserAccess,
    loadKbFiles,
    loadKbTemplates,
    loadKnowledgeAssets,
    applyAssetFilters,
    changeAssetPage,
    changeAssetPageSize,
    resetAssetForm,
    editAsset,
    createKnowledgeAsset,
    updateKnowledgeAsset,
    deleteKnowledgeAsset,
    transitionKnowledgeAsset,
    loadKbFile,
    createKbFile,
    saveKbFile,
    deleteKbFile,
    createKbTemplate,
    updateKbTemplate,
    deleteKbTemplate,
    rebuildKnowledgeIndex
  }
}
