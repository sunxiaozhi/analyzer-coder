<script setup lang="ts">
import { computed } from 'vue'
import { ElAside, ElContainer, ElHeader, ElTag } from 'element-plus'
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
  userPasswordForm,
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

const sectionSummary = computed(() => {
  const summaries = {
    projects: '选择工作区或拉取外部项目',
    accounts: '以账号列表维护项目访问权限',
    analysis: '查看结构化代码分析和项目报告',
    knowledge: '维护 docs 文档并同步混合索引',
    vectors: '构建代码与知识库向量索引',
    search: '检索代码证据、知识库证据和关联结果'
  }
  return summaries[activeSection.value]
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
        :current-user="currentUser"
        :selected-project="selectedProject"
        @check-health="checkHealth"
        @logout="logout"
      />
    </ElAside>

    <ElContainer v-if="currentUser" class="workspace">
      <ElHeader class="topbar">
        <div class="topbar-title">
          <p>{{ sectionSummary }}</p>
          <h2>{{ sectionTitle }}</h2>
        </div>
        <div class="topbar-state">
          <div class="connection-state">
            <span class="status-dot"></span>
            <span>{{ status }}</span>
          </div>
          <ElTag v-if="isWorking" type="warning" effect="plain">运行中</ElTag>
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
        v-model:user-password-form="userPasswordForm"
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
