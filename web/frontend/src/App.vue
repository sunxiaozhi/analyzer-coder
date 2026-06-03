<script setup lang="ts">
import { computed } from 'vue'
import { SwitchButton } from '@element-plus/icons-vue'
import { ElAside, ElBreadcrumb, ElBreadcrumbItem, ElButton, ElContainer, ElHeader, ElTag } from 'element-plus'
import AccountManagementPanel from './components/analyzer/AccountManagementPanel.vue'
import AnalyzerSidebar from './components/analyzer/AnalyzerSidebar.vue'
import AnalysisResultPanel from './components/analyzer/AnalysisResultPanel.vue'
import KnowledgeBasePanel from './components/analyzer/KnowledgeBasePanel.vue'
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
  login,
  logout,
  analyze,
  indexProject,
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
  loadKbFile,
  createKbFile,
  saveKbFile,
  deleteKbFile,
  rebuildKnowledgeIndex
} = useAnalyzerConsole()

const sectionTitle = computed(() => {
  const titles = {
    projects: '项目管理',
    accounts: '账号管理',
    analysis: '代码分析',
    knowledge: '知识库维护',
    vectors: '向量管理',
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
        :active-view="activeView"
        :busy="busy"
        :output="output"
        :output-title="outputTitle"
        :output-type="outputType"
        :parsed-json="parsedJson"
        :query-results="queryResults"
        :query-evidence="queryEvidence"
        :saved-path="savedPath"
        @analyze="analyze"
      />

      <KnowledgeBasePanel
        v-else-if="activeSection === 'knowledge'"
        v-model:form="form"
        v-model:draft-path="kbDraftPath"
        v-model:content="kbContent"
        :busy="kbBusy || busy"
        :files="kbFiles"
        :message="kbMessage"
        :root="kbRoot"
        :selected-path="selectedKbPath"
        @create-file="createKbFile"
        @delete-file="deleteKbFile"
        @refresh-files="loadKbFiles"
        @rebuild-index="rebuildKnowledgeIndex"
        @save-file="saveKbFile"
        @select-file="loadKbFile"
      />

      <VectorManagementPanel
        v-else-if="activeSection === 'vectors'"
        v-model:form="form"
        :active-view="activeView"
        :busy="busy"
        :output="output"
        :output-title="outputTitle"
        :output-type="outputType"
        :parsed-json="parsedJson"
        :query-results="queryResults"
        :query-evidence="queryEvidence"
        :saved-path="savedPath"
        @index-project="indexProject"
      />

      <SemanticSearchPanel
        v-else
        v-model:form="form"
        :active-view="activeView"
        :busy="busy"
        :output="output"
        :output-title="outputTitle"
        :output-type="outputType"
        :parsed-json="parsedJson"
        :query-results="queryResults"
        :query-evidence="queryEvidence"
        :saved-path="savedPath"
        @query-store="queryStore"
      />
    </ElContainer>
  </ElContainer>
</template>
