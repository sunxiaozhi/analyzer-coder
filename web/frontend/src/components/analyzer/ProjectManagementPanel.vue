<script setup lang="ts">
import { Upload } from '@element-plus/icons-vue'
import { shallowRef } from 'vue'
import {
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElMain,
  ElScrollbar,
  ElTag
} from 'element-plus'
import type { AnalyzerForm, ProjectForm, ProjectRecord } from '../../types'
import ProjectCard from './ProjectCard.vue'

const form = defineModel<AnalyzerForm>('form', { required: true })
const projectForm = defineModel<ProjectForm>('projectForm', { required: true })

defineProps<{
  projectBusy: boolean
  projects: ProjectRecord[]
  selectedProject: ProjectRecord | null
}>()

const emit = defineEmits<{
  createProject: []
  pullProject: []
  refreshProjects: []
}>()

const pullDialogOpen = shallowRef(false)

function selectProject(projectId: string) {
  form.value.projectId = projectId
}

function submitCreateProject() {
  emit('createProject')
  pullDialogOpen.value = false
}
</script>

<template>
  <ElMain class="page-surface">
    <div class="page-header">
      <div>
        <p class="eyebrow">Projects</p>
        <h2>项目管理</h2>
      </div>
      <div class="page-actions">
        <ElTag type="info" effect="plain">{{ projects.length }} 个项目</ElTag>
        <ElButton type="primary" :icon="Upload" @click="pullDialogOpen = true">
          从 Git 拉取
        </ElButton>
      </div>
    </div>

    <ElScrollbar class="project-list card-project-list">
      <div class="project-card-grid">
        <ProjectCard
          :selected="!form.projectId"
          workspace
          @select="selectProject('')"
        />
        <ProjectCard
          v-for="project in projects"
          :key="project.id"
          :busy="projectBusy"
          :project="project"
          :selected="form.projectId === project.id"
          @pull="$emit('pullProject')"
          @select="selectProject(project.id)"
        />
      </div>
    </ElScrollbar>

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
