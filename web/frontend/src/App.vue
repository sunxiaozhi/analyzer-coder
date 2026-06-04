<script setup lang="ts">
import { computed } from 'vue'
import { SwitchButton } from '@element-plus/icons-vue'
import { ElAside, ElBreadcrumb, ElBreadcrumbItem, ElButton, ElContainer, ElHeader, ElTag } from 'element-plus'
import AccountManagementPanel from './components/analyzer/AccountManagementPanel.vue'
import AnalyzerSidebar from './components/analyzer/AnalyzerSidebar.vue'
import AnalysisResultPanel from './components/analyzer/AnalysisResultPanel.vue'
import KnowledgeBasePanel from './components/analyzer/KnowledgeBasePanel.vue'
import KnowledgeTemplatePanel from './components/analyzer/KnowledgeTemplatePanel.vue'
import LoginPanel from './components/analyzer/LoginPanel.vue'
import ProjectManagementPanel from './components/analyzer/ProjectManagementPanel.vue'
import SemanticSearchPanel from './components/analyzer/SemanticSearchPanel.vue'
import VectorManagementPanel from './components/analyzer/VectorManagementPanel.vue'
import { useAnalyzerConsole } from './composables/useAnalyzerConsole'

const {
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
  login,
  logout,
  analyze,
  indexProject,
  loadIndexStatus,
  loadIndexRecords,
  queryStore,
  checkHealth,
  createProject,
  pullProject,
  loadProjects,
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
} = useAnalyzerConsole()

const sectionTitle = computed(() => {
  const titles = {
    projects: '项目管理',
    accounts: '账号管理',
    analysis: '代码分析',
    knowledge: '知识库维护',
    templates: '知识库模板',
    vectors: '索引运维',
    search: '语义检索'
  }
  return titles[activeSection.value]
})

const breadcrumbItems = computed(() => {
  if (selectedProject.value && activeSection.value !== 'projects' && activeSection.value !== 'accounts') {
    return [
      { label: '代码智库', section: 'projects' as const },
      { label: selectedProject.value.name, section: 'projects' as const },
      { label: sectionTitle.value, section: activeSection.value }
    ]
  }
  return [
    { label: '代码智库', section: 'projects' as const },
    { label: sectionTitle.value, section: activeSection.value }
  ]
})

const isWorking = computed(() => busy.value || kbBusy.value || projectBusy.value || authBusy.value)

async function refreshIndexData() {
  await loadIndexStatus()
  await loadIndexRecords()
}
</script>

<template>
  <LoginPanel
    v-if="authReady && !currentUser"
    v-model:form="loginForm"
    :busy="authBusy"
    :message="authMessage"
    @login="login"
  />

  <div v-else-if="!authReady" class="app-boot-screen" aria-busy="true"></div>

  <ElContainer v-else class="shell">
    <ElAside class="shell-aside">
      <AnalyzerSidebar
        v-model:active-section="activeSection"
        v-model:project-id="form.projectId"
        :current-user="currentUser"
        :projects="projects"
        @check-health="checkHealth"
      />
    </ElAside>

    <ElContainer v-if="currentUser" class="workspace">
      <ElHeader class="topbar">
        <div class="topbar-title">
          <ElBreadcrumb class="app-breadcrumb" separator="/">
            <ElBreadcrumbItem v-for="(item, index) in breadcrumbItems" :key="`${item.section}-${index}`">
              <button
                class="breadcrumb-link"
                :class="{ current: index === breadcrumbItems.length - 1 }"
                type="button"
                @click="activeSection = item.section"
              >
                {{ item.label }}
              </button>
            </ElBreadcrumbItem>
          </ElBreadcrumb>
        </div>
        <div class="topbar-state">
          <div class="connection-state">
            <span class="status-dot"></span>
            <span>{{ status }}</span>
          </div>
          <ElTag v-if="isWorking" type="warning" effect="plain">运行中</ElTag>
          <div class="topbar-account">
            <div class="topbar-user">
              <strong>{{ currentUser.displayName }}</strong>
            </div>
            <ElTag class="topbar-role" :type="currentUser.isAdmin ? 'warning' : 'info'" effect="plain">
              {{ currentUser.isAdmin ? '管理员' : '项目成员' }}
            </ElTag>
            <ElButton
              aria-label="退出登录"
              class="topbar-logout"
              :disabled="authBusy"
              :icon="SwitchButton"
              title="退出登录"
              @click="logout"
            />
          </div>
        </div>
      </ElHeader>

      <ProjectManagementPanel
        v-if="activeSection === 'projects'"
        v-model:form="form"
        v-model:project-form="projectForm"
        :project-busy="projectBusy"
        :project-message="projectMessage"
        :project-message-project-id="projectMessageProjectId"
        :projects="projects"
        @create-project="createProject"
        @pull-project="pullProject"
        @refresh-projects="loadProjects"
      />

      <AccountManagementPanel
        v-else-if="activeSection === 'accounts' && currentUser.isAdmin"
        v-model:user-form="userForm"
        v-model:user-edit-form="userEditForm"
        :auth-busy="authBusy"
        :auth-message="authMessage"
        :projects="projects"
        :users="users"
        @create-user="createUser"
        @refresh-users="loadUsers"
        @update-user="updateUser"
        @update-user-password="updateUserPassword"
        @update-user-access="updateUserAccess"
      />

      <AnalysisResultPanel
        v-else-if="activeSection === 'analysis'"
        v-model:form="form"
        :busy="busy"
        :output="analysisOutput"
        :output-title="analysisOutputTitle"
        :output-type="analysisOutputType"
        :parsed-json="analysisParsedJson"
        :saved-path="analysisSavedPath"
        :updated-at="analysisUpdatedAt"
        @analyze="analyze"
      />

      <KnowledgeBasePanel
        v-else-if="activeSection === 'knowledge'"
        v-model:draft-path="kbDraftPath"
        v-model:content="kbContent"
        :busy="kbBusy || busy"
        :files="kbFiles"
        :root="kbRoot"
        :selected-path="selectedKbPath"
        :templates="kbTemplates"
        @create-file="createKbFile"
        @delete-file="deleteKbFile"
        @refresh-files="loadKbFiles"
        @rebuild-index="rebuildKnowledgeIndex"
        @save-file="saveKbFile"
        @select-file="loadKbFile"
      />

      <KnowledgeTemplatePanel
        v-else-if="activeSection === 'templates'"
        v-model:template-form="templateForm"
        :busy="kbBusy"
        :message="templateMessage"
        :templates="kbTemplates"
        @create-template="createKbTemplate"
        @delete-template="deleteKbTemplate"
        @refresh-templates="loadKbTemplates"
        @update-template="updateKbTemplate"
      />

      <VectorManagementPanel
        v-else-if="activeSection === 'vectors'"
        v-model:form="form"
        :busy="busy"
        :output="indexOutput"
        :output-title="indexOutputTitle"
        :output-type="indexOutputType"
        :parsed-json="indexParsedJson"
        :saved-path="indexSavedPath"
        :status="indexStatus"
        :records="indexRecords"
        :record-total="indexRecordTotal"
        v-model:record-filters="indexRecordFilters"
        v-model:record-page="indexRecordPage"
        v-model:record-page-size="indexRecordPageSize"
        @index-project="indexProject"
        @refresh-status="refreshIndexData"
        @refresh-records="loadIndexRecords"
      />

      <SemanticSearchPanel
        v-else
        v-model:form="form"
        :busy="busy"
        :query-results="searchResults"
        :query-evidence="searchEvidence"
        :saved-path="searchSavedPath"
        @query-store="queryStore"
      />
    </ElContainer>
  </ElContainer>
</template>
