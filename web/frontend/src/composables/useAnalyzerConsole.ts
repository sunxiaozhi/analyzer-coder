import { computed, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import type {
  ActiveView,
  AnalyzerForm,
  AuthUser,
  ConsoleSection,
  JsonValue,
  KnowledgeFile,
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

  const status = shallowRef('未连接')
  const busy = shallowRef(false)
  const projectBusy = shallowRef(false)
  const kbBusy = shallowRef(false)
  const authBusy = shallowRef(false)
  const authReady = shallowRef(false)
  const output = shallowRef('')
  const savedPath = shallowRef('')
  const queryResults = ref<QueryResult[]>([])
  const queryEvidence = ref<QueryEvidence | null>(null)
  const kbFiles = ref<KnowledgeFile[]>([])
  const selectedKbPath = shallowRef('')
  const kbDraftPath = shallowRef('domain/new-topic.md')
  const kbContent = shallowRef('')
  const kbRoot = shallowRef('')
  const kbMessage = shallowRef('')
  const projects = ref<ProjectRecord[]>([])
  const users = ref<AuthUser[]>([])
  const currentUser = ref<AuthUser | null>(null)
  const authMessage = shallowRef('')
  const activeView = shallowRef<ActiveView>('analysis')
  const activeSection = shallowRef<ConsoleSection>('projects')

  const selectedProject = computed(() =>
    projects.value.find((project) => project.id === form.projectId) ?? null
  )

  const outputTitle = computed(() => {
    if (activeView.value === 'query') return '检索结果'
    if (activeView.value === 'index') return '索引结果'
    return '分析结果'
  })

  const outputType = computed<OutputType>(() => {
    if (activeView.value === 'analysis' && form.mode === 'report') return 'markdown'
    if (activeView.value === 'analysis' && form.mode === 'graph') return 'mermaid'
    if (activeView.value === 'analysis' && ['json', 'chunks'].includes(form.mode)) return 'json'
    return 'text'
  })

  const parsedJson = computed<JsonValue | null>(() => {
    if (outputType.value !== 'json' || !output.value) return null
    try {
      return JSON.parse(output.value) as JsonValue
    } catch {
      return null
    }
  })

  async function requestJson<T>(url: string, payload: Record<string, unknown>): Promise<T> {
    busy.value = true
    savedPath.value = ''
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
    savedPath.value = data.savedPath
  }

  async function createProject() {
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
    const data = await projectRequest<ProjectResponse>(`/api/projects/${form.projectId}/pull`, {})
    projects.value = projects.value.map((project) =>
      project.id === data.project.id ? data.project : project
    )
  }

  async function analyze() {
    activeSection.value = 'analysis'
    activeView.value = 'analysis'
    queryResults.value = []
    queryEvidence.value = null
    const data = await requestJson<AnalyzeResponse>('/api/analyze', {
      path: form.path,
      codePath: form.codePath,
      kbPath: form.kbPath,
      projectId: form.projectId,
      source: form.source,
      mode: form.mode
    })
    output.value = data.output
    savedPath.value = data.savedPath
  }

  async function indexProject() {
    activeSection.value = 'vectors'
    activeView.value = 'index'
    queryResults.value = []
    queryEvidence.value = null
    const data = await requestJson<IndexResponse>('/api/index', {
      path: form.path,
      codePath: form.codePath,
      kbPath: form.kbPath,
      projectId: form.projectId,
      source: form.source,
      store: null
    })
    output.value = data.message
    savedPath.value = data.savedPath
  }

  async function queryStore() {
    activeSection.value = 'search'
    activeView.value = 'query'
    output.value = ''
    queryEvidence.value = null
    const data = await requestJson<QueryResponse>('/api/query', {
      store: null,
      projectId: form.projectId,
      query: form.query,
      filterSource: form.filterSource || null,
      topK: 5
    })
    queryResults.value = data.results
    queryEvidence.value = data.evidence
    savedPath.value = data.savedPath
  }

  watch(
    () => form.projectId,
    (projectId, previousProjectId) => {
      if (projectId === previousProjectId) return
      output.value = ''
      savedPath.value = ''
      queryResults.value = []
      queryEvidence.value = null
      activeView.value = 'analysis'
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
      updateLastProject(projectId)
      loadKbFiles()
    }
  )

  watch(
    () => form.kbPath,
    () => {
      selectedKbPath.value = ''
      kbContent.value = ''
      loadKbFiles()
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
    output,
    savedPath,
    queryResults,
    queryEvidence,
    kbFiles,
    selectedKbPath,
    kbDraftPath,
    kbContent,
    kbRoot,
    kbMessage,
    projects,
    users,
    currentUser,
    authMessage,
    selectedProject,
    activeView,
    activeSection,
    outputTitle,
    outputType,
    parsedJson,
    analyze,
    login,
    logout,
    indexProject,
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
    loadKbFile,
    createKbFile,
    saveKbFile,
    deleteKbFile,
    rebuildKnowledgeIndex
  }
}
