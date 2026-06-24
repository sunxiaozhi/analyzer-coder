<script setup lang="ts">
import { computed, shallowRef } from 'vue'
import { FolderOpened, Refresh, Upload } from '@element-plus/icons-vue'
import {
  ElButton,
  ElCard,
  ElDialog,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElIcon,
  ElInput,
  ElMain,
  ElScrollbar,
  ElTag
} from 'element-plus'
import type { AnalyzerForm, ProjectForm, ProjectRecord } from '../../types'
import ProjectCard from './ProjectCard.vue'

const form = defineModel<AnalyzerForm>('form', { required: true })
const projectForm = defineModel<ProjectForm>('projectForm', { required: true })

const props = defineProps<{
  projectBusy: boolean
  projectMessage: string
  projectMessageProjectId: string
  projects: ProjectRecord[]
}>()

const emit = defineEmits<{
  createProject: []
  pullProject: []
}>()

const pullDialogOpen = shallowRef(false)

const selectedProject = computed(() =>
  props.projects.find((project) => project.id === form.value.projectId) ?? null
)

const visibleProjectMessage = computed(() => {
  if (!selectedProject.value || selectedProject.value.id !== props.projectMessageProjectId) return ''
  return props.projectMessage
})

function submitCreateProject() {
  emit('createProject')
  pullDialogOpen.value = false
}

function selectProject(project: ProjectRecord) {
  form.value.projectId = project.id
}

function pullProject(project: ProjectRecord) {
  selectProject(project)
  emit('pullProject')
}

function formatDate(value: string) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(date)
}
</script>

<template>
  <ElMain class="page-surface code-project-page console-page">
    <section class="code-project-command console-command">
      <div class="code-project-heading console-heading">
        <ElIcon><FolderOpened /></ElIcon>
        <div>
          <h2>代码管理</h2>
          <span>{{ selectedProject?.name || '选择或拉取代码项目' }}</span>
        </div>
      </div>

      <div class="code-project-actions console-command-actions">
        <ElTag type="info" effect="plain">{{ projects.length }} 个项目</ElTag>
        <ElButton type="primary" :icon="Upload" @click="pullDialogOpen = true">
          从 Git 拉取
        </ElButton>
      </div>
    </section>

    <section class="code-project-workbench console-workbench console-workbench-detail">
      <ElCard class="panel code-project-list-panel console-card" shadow="never">
        <template #header>
          <div class="panel-title split-title">
            <span>
              <ElIcon><FolderOpened /></ElIcon>
              <span>项目列表</span>
            </span>
            <ElTag type="info" effect="plain">{{ projects.length }}</ElTag>
          </div>
        </template>

        <ElScrollbar class="code-project-list-scroll">
          <ElEmpty v-if="!projects.length" description="暂无代码项目。" />
          <div v-else class="code-project-list">
            <ProjectCard
              v-for="project in projects"
              :key="project.id"
              :project="project"
              :selected="form.projectId === project.id"
              @select="selectProject(project)"
            />
          </div>
        </ElScrollbar>
      </ElCard>

      <aside class="code-project-detail-stack">
        <ElCard class="panel code-project-current-panel console-card" shadow="never">
          <template #header>
            <div class="panel-title split-title">
              <span>
                <ElIcon><FolderOpened /></ElIcon>
                <span>当前项目</span>
              </span>
              <ElTag :type="selectedProject ? 'success' : 'info'" effect="plain">
                {{ selectedProject ? '已选择' : '未选择' }}
              </ElTag>
            </div>
          </template>

          <ElEmpty v-if="!selectedProject" description="从左侧选择一个项目。" />
          <div v-else class="code-project-current">
            <div class="code-project-title">
              <strong>{{ selectedProject.name }}</strong>
              <span>{{ selectedProject.id }}</span>
            </div>

            <dl class="code-project-fields">
              <div class="code-project-field">
                <dt>Git 地址</dt>
                <dd>{{ selectedProject.gitUrl }}</dd>
              </div>
              <div class="code-project-field">
                <dt>分支</dt>
                <dd>{{ selectedProject.branch || '默认分支' }}</dd>
              </div>
              <div class="code-project-field">
                <dt>本地路径</dt>
                <dd>{{ selectedProject.path }}</dd>
              </div>
              <div class="code-project-field">
                <dt>更新时间</dt>
                <dd>{{ formatDate(selectedProject.updatedAt) }}</dd>
              </div>
            </dl>

            <div class="code-project-current-actions">
              <ElButton
                class="code-project-update-button"
                :class="{ updating: projectBusy }"
                type="primary"
                :disabled="projectBusy"
                :icon="Refresh"
                :loading="projectBusy"
                @click="pullProject(selectedProject)"
              >
                <span>{{ projectBusy ? '更新中' : '更新代码' }}</span>
              </ElButton>
              <p v-if="visibleProjectMessage" class="code-project-success">
                {{ visibleProjectMessage }}
              </p>
            </div>
          </div>
        </ElCard>
      </aside>
    </section>

    <ElDialog v-model="pullDialogOpen" title="从 Git 拉取项目" width="520px">
      <ElForm label-position="top" class="control-form">
        <ElFormItem label="项目名称">
          <ElInput v-model="projectForm.name" clearable placeholder="例如 user-service" />
        </ElFormItem>
        <ElFormItem label="Git 地址">
          <ElInput v-model="projectForm.gitUrl" clearable placeholder="https://github.com/org/repo.git" />
        </ElFormItem>
        <ElFormItem label="分支">
          <ElInput v-model="projectForm.branch" clearable placeholder="默认分支" />
        </ElFormItem>
      </ElForm>

      <template #footer>
        <div class="dialog-actions">
          <ElButton :disabled="projectBusy" @click="pullDialogOpen = false">取消</ElButton>
          <ElButton type="primary" :loading="projectBusy" :icon="Upload" @click="submitCreateProject">
            拉取项目
          </ElButton>
        </div>
      </template>
    </ElDialog>
  </ElMain>
</template>

<style scoped>
.code-project-page {
  gap: 0;
  padding: 0;
}

.code-project-command {
  align-items: center;
  background: var(--surface);
  border-bottom: 1px solid var(--line);
  box-shadow: var(--shadow-sm);
  display: flex;
  gap: 16px;
  justify-content: space-between;
  padding: 16px 24px;
}

.code-project-heading {
  align-items: center;
  display: flex;
  gap: 10px;
  min-width: 0;
}

.code-project-heading > .el-icon {
  align-items: center;
  background: #f1f5f9;
  border-radius: 8px;
  color: #475569;
  display: inline-flex;
  flex: 0 0 36px;
  height: 36px;
  justify-content: center;
  width: 36px;
}

.code-project-heading h2 {
  color: var(--text);
  font-size: 1.08rem;
  margin: 0;
}

.code-project-heading span {
  color: var(--text-faint);
  display: block;
  font-size: 0.78rem;
  margin-top: 3px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.code-project-actions {
  align-items: center;
  display: flex;
  gap: 10px;
}

.code-project-workbench {
  display: grid;
  gap: 14px;
  grid-template-columns: minmax(0, 1fr) minmax(320px, 360px);
  min-height: 0;
  overflow: hidden;
  padding: 18px 24px 24px;
}

.code-project-list-panel.el-card {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-height: 0;
  overflow: hidden;
}

.code-project-list-panel.el-card,
.code-project-current-panel.el-card {
  box-shadow: none;
}

.code-project-list-panel :deep(.el-card__body) {
  min-height: 0;
  padding: 12px;
}

.split-title > span {
  align-items: center;
  display: inline-flex;
  gap: 7px;
}

.code-project-list-scroll {
  height: 100%;
  min-height: 0;
}

.code-project-list {
  display: grid;
  gap: 8px;
}

.code-project-detail-stack {
  display: grid;
  grid-template-rows: minmax(0, 1fr);
  min-height: 0;
}

.code-project-current {
  display: grid;
  align-content: start;
  gap: 14px;
}

.code-project-title {
  border-bottom: 1px solid var(--line);
  display: grid;
  gap: 4px;
  padding-bottom: 12px;
}

.code-project-title strong {
  color: var(--text);
  font-size: 1rem;
  overflow-wrap: anywhere;
}

.code-project-title span {
  color: var(--text-faint);
  font-size: 0.78rem;
  overflow-wrap: anywhere;
}

.code-project-fields {
  display: grid;
  gap: 0;
  margin: 0;
}

.code-project-field {
  border-bottom: 1px solid var(--line);
  display: grid;
  gap: 6px;
  grid-template-columns: 72px minmax(0, 1fr);
  padding: 11px 0;
}

.code-project-field:first-child {
  padding-top: 0;
}

.code-project-field:last-child {
  border-bottom: 0;
  padding-bottom: 0;
}

.code-project-field dt {
  color: var(--text-faint);
  font-size: 0.72rem;
  margin: 0;
}

.code-project-field dd {
  color: var(--text);
  font-size: 0.86rem;
  font-weight: 700;
  margin: 0;
  overflow-wrap: anywhere;
}

.code-project-current-actions {
  display: grid;
  gap: 10px;
}

.code-project-update-button.el-button {
  background: var(--accent);
  border-color: var(--accent);
  color: #ffffff;
  min-width: 112px;
  overflow: hidden;
  position: relative;
  width: 100%;
}

.code-project-update-button.el-button::after {
  background: linear-gradient(90deg, transparent, rgb(255 255 255 / 34%), transparent);
  content: "";
  height: 100%;
  left: -45%;
  opacity: 0;
  pointer-events: none;
  position: absolute;
  top: 0;
  transform: skewX(-18deg);
  width: 40%;
}

.code-project-update-button.el-button.updating {
  animation: updatePulse 1.1s ease-in-out infinite;
}

.code-project-update-button.el-button.updating::after {
  animation: updateSweep 1.2s ease-in-out infinite;
  opacity: 1;
}

.code-project-update-button.el-button:hover,
.code-project-update-button.el-button:focus {
  background: var(--accent-strong);
  border-color: var(--accent-strong);
  color: #ffffff;
}

.code-project-update-button.el-button.is-disabled,
.code-project-update-button.el-button.is-disabled:hover {
  background: #d8e0e8;
  border-color: #d8e0e8;
  color: #64748b;
}

.code-project-update-button.el-button.updating.is-disabled,
.code-project-update-button.el-button.updating.is-disabled:hover {
  background: var(--accent);
  border-color: var(--accent);
  color: #ffffff;
}

.code-project-update-button :deep(.el-icon) {
  color: currentColor;
}

.code-project-update-button :deep(span) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: max-content;
}

.code-project-success {
  align-items: center;
  background: #eaf2ff;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  color: #1d4ed8;
  display: flex;
  font-size: 0.84rem;
  font-weight: 700;
  justify-content: center;
  margin: 0;
  min-height: 34px;
}

@keyframes updatePulse {
  0%,
  100% {
    box-shadow: 0 0 0 0 rgb(37 99 235 / 22%);
  }

  50% {
    box-shadow: 0 0 0 5px rgb(37 99 235 / 10%);
  }
}

@keyframes updateSweep {
  0% {
    left: -45%;
  }

  100% {
    left: 110%;
  }
}

@media (max-width: 1100px) {
  .code-project-workbench {
    grid-template-columns: 1fr;
  }

  .code-project-detail-stack {
    grid-template-columns: 1fr;
    grid-template-rows: auto;
  }
}

@media (max-width: 760px) {
  .code-project-command {
    align-items: stretch;
    flex-direction: column;
    padding: 14px 16px;
  }

  .code-project-actions {
    align-items: stretch;
    display: grid;
  }

  .code-project-actions .el-button {
    width: 100%;
  }

  .code-project-workbench {
    grid-template-columns: 1fr;
    padding: 14px 16px;
  }

  .code-project-detail-stack {
    grid-template-columns: 1fr;
  }
}
</style>
