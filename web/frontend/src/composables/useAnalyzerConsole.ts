import { computed, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import type {
  AnalyzerForm,
  AuthUser,
  ConsoleSection,
  IndexRecord,
  IndexRecordFilters,
  IndexStatus,
  JsonValue,
  KnowledgeFile,
  KnowledgeTemplate,
  KnowledgeTemplateForm,
  OutputType,
  ProjectForm,
  ProjectRecord,
  QueryEvidence,
  QueryResult,
  UserEditForm,
  UserForm
} from '../types'

interface AnalyzeResponse {
  output: string
  savedPath: string
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

interface QueryResponse {
  results: QueryResult[]
  evidence: QueryEvidence
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

type ApiPayload = Record<string, unknown>

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
  if (mode === 'graph') return 'mermaid'
  if (['json', 'chunks'].includes(mode)) return 'json'
  return 'text'
}

function emptyAnalysisResult(mode: AnalyzerForm['mode']) {
  return {
    output: '',
    savedPath: '',
    outputType: resolveAnalysisOutputType(mode),
    updatedAt: ''
  }
}

export function useAnalyzerConsole() {
  const form = reactive<AnalyzerForm>({
    projectId: '',
    path: '.',
    codePath: '.',
    kbPath: 'docs',
    source: 'code',
    mode: 'report',
    store: '.vector_store/web-project.jsonl',
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

  const status = shallowRef('未连接')
  const busy = shallowRef(false)
  const projectBusy = shallowRef(false)
  const kbBusy = shallowRef(false)
  const authBusy = shallowRef(false)
  const authReady = shallowRef(false)
  const analysisResultsByMode = reactive({
    report: emptyAnalysisResult('report'),
    summary: emptyAnalysisResult('summary'),
    json: emptyAnalysisResult('json'),
    chunks: emptyAnalysisResult('chunks'),
    graph: emptyAnalysisResult('graph')
  })
  const indexOutput = shallowRef('')
  const indexSavedPath = shallowRef('')
  const indexOutputType = shallowRef<OutputType>('text')
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
  const searchResults = ref<QueryResult[]>([])
  const searchEvidence = ref<QueryEvidence | null>(null)
  const searchSavedPath = shallowRef('')
  const kbFiles = ref<KnowledgeFile[]>([])
  const kbTemplates = ref<KnowledgeTemplate[]>([])
  const selectedKbPath = shallowRef('')
  const kbDraftPath = shallowRef('domain/new-topic.md')
  const kbContent = shallowRef('')
  const kbRoot = shallowRef('')
  const kbMessage = shallowRef('')
  const templateMessage = shallowRef('')
  const projectMessage = shallowRef('')
  const projectMessageProjectId = shallowRef('')
  const projects = ref<ProjectRecord[]>([])
  const users = ref<AuthUser[]>([])
  const currentUser = ref<AuthUser | null>(null)
  const authMessage = shallowRef('')
  const activeSection = shallowRef<ConsoleSection>('projects')

  const selectedProject = computed(() =>
    projects.value.find((project) => project.id === form.projectId) ?? null
  )

  const analysisOutputTitle = computed(() => '分析结果')
  const currentAnalysisResult = computed(() => analysisResultsByMode[form.mode])
  const analysisOutput = computed(() => currentAnalysisResult.value.output)
  const analysisSavedPath = computed(() => currentAnalysisResult.value.savedPath)
  const analysisOutputType = computed(() => currentAnalysisResult.value.outputType)
  const analysisUpdatedAt = computed(() => currentAnalysisResult.value.updatedAt)

  const analysisParsedJson = computed<JsonValue | null>(() => {
    if (analysisOutputType.value !== 'json' || !analysisOutput.value) return null
    try {
      return JSON.parse(analysisOutput.value) as JsonValue
    } catch {
      return null
    }
  })

  const indexOutputTitle = computed(() => '索引结果')

  const indexParsedJson = computed<JsonValue | null>(() => {
    if (indexOutputType.value !== 'json' || !indexOutput.value) return null
    try {
      return JSON.parse(indexOutput.value) as JsonValue
    } catch {
      return null
    }
  })

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
      if (!currentUser.value.isAdmin && activeSection.value === 'accounts') {
        activeSection.value = 'projects'
      }
      authMessage.value = ''
      await checkHealth()
      await loadProjects()
      if (currentUser.value.isAdmin) {
        await loadUsers()
      }
      await loadKbFiles()
      await loadKbTemplates()
      await loadIndexStatus()
      await loadIndexRecords()
    } catch (error) {
      authMessage.value = error instanceof Error ? error.message : '登录失败'
      currentUser.value = null
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
      projects.value = []
      users.value = []
      form.projectId = ''
      activeSection.value = 'projects'
      selectedKbPath.value = ''
      kbContent.value = ''
      kbTemplates.value = []
      resetTemplateForm()
      authBusy.value = false
      authMessage.value = ''
    }
  }

  async function loadCurrentUser() {
    authReady.value = false
    try {
      const data = await getJson<AuthResponse>('/api/auth/me')
      currentUser.value = data.user
      if (!currentUser.value.isAdmin && activeSection.value === 'accounts') {
        activeSection.value = 'projects'
      }
      await checkHealth()
      await loadProjects()
      if (currentUser.value.isAdmin) {
        await loadUsers()
      }
      await loadKbFiles()
      await loadKbTemplates()
      await loadIndexStatus()
      await loadIndexRecords()
    } catch {
      currentUser.value = null
      projects.value = []
      users.value = []
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
      status.value = data.ok ? `已连接：${data.workspace}` : '后端不可用'
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
      templateMessage.value = error instanceof Error ? error.message : '知识库模板加载失败'
    }
  }

  function resetTemplateForm() {
    templateForm.id = ''
    templateForm.name = ''
    templateForm.path = ''
    templateForm.content = ''
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
    activeSection.value = 'analysis'
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
      mode
    })
    analysisResultsByMode[mode] = {
      output: data.output,
      savedPath: data.savedPath,
      outputType: resolveAnalysisOutputType(mode),
      updatedAt: new Date().toISOString()
    }
  }

  async function indexProject() {
    activeSection.value = 'vectors'
    indexSavedPath.value = ''
    indexOutputType.value = 'text'
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

  async function queryStore() {
    activeSection.value = 'search'
    searchSavedPath.value = ''
    searchEvidence.value = null
    const data = await requestJson<QueryResponse>('/api/query', {
      store: null,
      projectId: form.projectId,
      query: form.query,
      filterSource: form.filterSource || null,
      topK: 5
    })
    searchResults.value = data.results
    searchEvidence.value = data.evidence
    searchSavedPath.value = data.savedPath
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
      indexOutputType.value = 'text'
      indexStatus.value = null
      indexRecords.value = []
      indexRecordTotal.value = 0
      indexRecordPage.value = 1
      searchResults.value = []
      searchEvidence.value = null
      searchSavedPath.value = ''
      projectMessage.value = ''
      projectMessageProjectId.value = ''
      if (projectId) {
        form.path = '.'
        form.codePath = '.'
        form.kbPath = 'docs'
        form.store = ''
      } else {
        form.path = '.'
        form.codePath = '.'
        form.kbPath = 'docs'
        form.store = '.vector_store/web-project.jsonl'
      }
      selectedKbPath.value = ''
      kbContent.value = ''
      resetTemplateForm()
      updateLastProject(projectId)
      loadKbFiles()
      loadKbTemplates()
      loadIndexStatus()
      loadIndexRecords()
    }
  )

  watch(
    () => form.kbPath,
    () => {
      selectedKbPath.value = ''
      kbContent.value = ''
      resetTemplateForm()
      loadKbFiles()
      loadKbTemplates()
    }
  )

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
    authBusy,
    authReady,
    analysisOutput,
    analysisSavedPath,
    analysisOutputTitle,
    analysisOutputType,
    analysisUpdatedAt,
    analysisParsedJson,
    indexOutput,
    indexSavedPath,
    indexOutputTitle,
    indexOutputType,
    indexParsedJson,
    indexStatus,
    indexRecords,
    indexRecordTotal,
    indexRecordFilters,
    indexRecordPage,
    indexRecordPageSize,
    searchResults,
    searchEvidence,
    searchSavedPath,
    kbFiles,
    kbTemplates,
    selectedKbPath,
    kbDraftPath,
    kbContent,
    kbRoot,
    kbMessage,
    templateForm,
    templateMessage,
    projectMessage,
    projectMessageProjectId,
    projects,
    users,
    currentUser,
    authMessage,
    selectedProject,
    activeSection,
    analyze,
    login,
    logout,
    indexProject,
    loadIndexStatus,
    loadIndexRecords,
    queryStore,
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
