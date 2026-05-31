import { computed, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import type {
  ActiveView,
  AnalyzerForm,
  ConsoleSection,
  JsonValue,
  OutputType,
  ProjectForm,
  ProjectRecord,
  QueryResult
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
  savedPath: string
}

interface ProjectsResponse {
  projects: ProjectRecord[]
}

interface ProjectResponse {
  project: ProjectRecord
}

export function useAnalyzerConsole() {
  const form = reactive<AnalyzerForm>({
    projectId: '',
    path: 'java/src/main/java',
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

  const status = shallowRef('未连接')
  const busy = shallowRef(false)
  const projectBusy = shallowRef(false)
  const output = shallowRef('')
  const savedPath = shallowRef('')
  const queryResults = ref<QueryResult[]>([])
  const projects = ref<ProjectRecord[]>([])
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
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      })
      const data = await response.json()
      if (!response.ok) {
        throw new Error(data.error || `请求失败：${response.status}`)
      }
      return data as T
    } finally {
      busy.value = false
    }
  }

  async function getJson<T>(url: string): Promise<T> {
    const response = await fetch(url)
    const data = await response.json()
    if (!response.ok) {
      throw new Error(data.error || `请求失败：${response.status}`)
    }
    return data as T
  }

  async function projectRequest<T>(url: string, payload: Record<string, unknown>): Promise<T> {
    projectBusy.value = true
    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      })
      const data = await response.json()
      if (!response.ok) {
        throw new Error(data.error || `请求失败：${response.status}`)
      }
      return data as T
    } finally {
      projectBusy.value = false
    }
  }

  async function checkHealth() {
    try {
      const response = await fetch('/api/health')
      const data = await response.json()
      status.value = data.ok ? `已连接：${data.workspace}` : '后端不可用'
    } catch {
      status.value = '后端未启动'
    }
  }

  async function loadProjects() {
    try {
      const data = await getJson<ProjectsResponse>('/api/projects')
      projects.value = data.projects
      if (form.projectId && !projects.value.some((project) => project.id === form.projectId)) {
        form.projectId = ''
      }
    } catch {
      projects.value = []
    }
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
    form.store = ''
    projectForm.name = ''
    projectForm.gitUrl = ''
    projectForm.branch = ''
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
    const data = await requestJson<AnalyzeResponse>('/api/analyze', {
      path: form.path,
      projectId: form.projectId || null,
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
    const data = await requestJson<IndexResponse>('/api/index', {
      path: form.path,
      projectId: form.projectId || null,
      source: form.source,
      store: form.store || null
    })
    output.value = data.message
    savedPath.value = data.savedPath
  }

  async function queryStore() {
    activeSection.value = 'search'
    activeView.value = 'query'
    output.value = ''
    const data = await requestJson<QueryResponse>('/api/query', {
      store: form.store || null,
      projectId: form.projectId || null,
      query: form.query,
      filterSource: form.filterSource || null,
      topK: 5
    })
    queryResults.value = data.results
    savedPath.value = data.savedPath
  }

  watch(
    () => form.projectId,
    (projectId, previousProjectId) => {
      if (projectId === previousProjectId) return
      output.value = ''
      savedPath.value = ''
      queryResults.value = []
    activeView.value = 'analysis'
      if (projectId) {
        form.path = '.'
        form.store = ''
      } else {
        form.path = 'java/src/main/java'
        form.store = '.vector_store/web-project.jsonl'
      }
    }
  )

  onMounted(() => {
    checkHealth()
    loadProjects()
  })

  return {
    form,
    projectForm,
    status,
    busy,
    projectBusy,
    output,
    savedPath,
    queryResults,
    projects,
    selectedProject,
    activeView,
    activeSection,
    outputTitle,
    outputType,
    parsedJson,
    analyze,
    indexProject,
    queryStore,
    checkHealth,
    loadProjects,
    createProject,
    pullProject
  }
}
