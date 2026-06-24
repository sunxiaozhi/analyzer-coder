<script setup lang="ts">
import { computed } from 'vue'
import { SwitchButton } from '@element-plus/icons-vue'
import { ElAside, ElBreadcrumb, ElBreadcrumbItem, ElButton, ElConfigProvider, ElContainer, ElHeader, ElTag } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import AccountManagementPanel from './components/analyzer/AccountManagementPanel.vue'
import AnalyzerSidebar from './components/analyzer/AnalyzerSidebar.vue'
import AnalysisResultPanel from './components/analyzer/AnalysisResultPanel.vue'
import ApiMappingPanel from './components/analyzer/ApiMappingPanel.vue'
import KnowledgeAssetPanel from './components/analyzer/KnowledgeAssetPanel.vue'
import KnowledgeBasePanel from './components/analyzer/KnowledgeBasePanel.vue'
import LoginPanel from './components/analyzer/LoginPanel.vue'
import ProjectManagementPanel from './components/analyzer/ProjectManagementPanel.vue'
import SemanticSearchPanel from './components/analyzer/SemanticSearchPanel.vue'
import VectorManagementPanel from './components/analyzer/VectorManagementPanel.vue'
import { useAnalyzerConsole } from './composables/useAnalyzerConsole'
import type { ConsoleSection } from './types'

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
  activeSection,
  login,
  logout,
  analyze,
  indexProject,
  loadIndexStatus,
  loadIndexRecords,
  queryStore,
  buildApiMapping,
  refreshSectionData,
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
  rebuildKnowledgeIndex
} = useAnalyzerConsole()

interface BreadcrumbItem {
  label: string
  section?: ConsoleSection
}

const menuBreadcrumbs: Record<ConsoleSection, BreadcrumbItem[]> = {
  assets: [{ label: '知识资产', section: 'assets' }],
  evidence: [{ label: '代码依据', section: 'analysis' }],
  analysis: [{ label: '代码依据', section: 'analysis' }],
  'api-map': [{ label: '接口依据', section: 'api-map' }],
  vectors: [{ label: '索引依据', section: 'vectors' }],
  search: [{ label: '知识问答', section: 'search' }],
  projects: [
    { label: '项目设置' },
    { label: '项目管理', section: 'projects' }
  ],
  accounts: [
    { label: '项目设置' },
    { label: '账号权限', section: 'accounts' }
  ],
  knowledge: [
    { label: '项目设置' },
    { label: '文档库', section: 'knowledge' }
  ]
}

const breadcrumbItems = computed(() => menuBreadcrumbs[activeSection.value])

const isWorking = computed(() => busy.value || kbBusy.value || assetBusy.value || projectBusy.value || authBusy.value)

async function refreshIndexData() {
  await loadIndexStatus()
  await loadIndexRecords()
}

function openBreadcrumbItem(item: BreadcrumbItem) {
  if (item.section) activeSection.value = item.section
}
</script>

<template>
  <ElConfigProvider :locale="zhCn">
    <LoginPanel
      v-if="authReady && !currentUser"
      v-model:form="loginForm"
      :busy="authBusy"
      :message="authMessage"
      @login="login"
    />

    <ElContainer v-else-if="currentUser" class="shell">
      <ElAside class="shell-aside">
        <AnalyzerSidebar
          v-model:active-section="activeSection"
          v-model:project-id="form.projectId"
          :current-user="currentUser"
          :projects="projects"
          @refresh-section="refreshSectionData"
        />
      </ElAside>

      <ElContainer v-if="currentUser" class="workspace">
      <ElHeader class="topbar">
        <div class="topbar-title">
          <ElBreadcrumb class="app-breadcrumb" separator="/">
            <ElBreadcrumbItem v-for="(item, index) in breadcrumbItems" :key="`${item.label}-${index}`">
              <button
                class="breadcrumb-link"
                :class="{ current: index === breadcrumbItems.length - 1, static: !item.section }"
                :disabled="!item.section"
                type="button"
                @click="openBreadcrumbItem(item)"
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
        :saved-path="analysisSavedPath"
        :updated-at="analysisUpdatedAt"
        @analyze="analyze"
      />

      <KnowledgeAssetPanel
        v-else-if="activeSection === 'assets'"
        v-model:asset-form="assetForm"
        v-model:filters="assetFilters"
        v-model:pagination="assetPagination"
        :assets="knowledgeAssets"
        :busy="assetBusy || kbBusy"
        :current-user="currentUser"
        :message="assetMessage"
        :selected-asset-id="selectedAssetId"
        :users="users"
        @apply-filters="applyAssetFilters"
        @change-page="changeAssetPage"
        @change-page-size="changeAssetPageSize"
        @confirm-asset="transitionKnowledgeAsset($event, 'confirm')"
        @create-asset="createKnowledgeAsset"
        @delete-asset="deleteKnowledgeAsset"
        @edit-asset="editAsset"
        @mark-stale="transitionKnowledgeAsset($event, 'mark-stale')"
        @refresh-assets="loadKnowledgeAssets"
        @reset-asset="resetAssetForm"
        @update-asset="updateKnowledgeAsset"
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

      <VectorManagementPanel
        v-else-if="activeSection === 'vectors'"
        v-model:form="form"
        :busy="busy"
        :output="indexOutput"
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

      <ApiMappingPanel
        v-else-if="activeSection === 'api-map'"
        v-model:form="form"
        :busy="busy"
        :message="apiMappingMessage"
        :result="apiMapping"
        :saved-path="apiMappingSavedPath"
        @build-mapping="buildApiMapping"
      />

      <SemanticSearchPanel
        v-else-if="activeSection === 'search'"
        v-model:form="form"
        :busy="busy"
        :query-results="searchResults"
        :query-evidence="searchEvidence"
        :rag-flow="ragFlow"
        :saved-path="searchSavedPath"
        @query-store="queryStore"
      />

      <KnowledgeAssetPanel
        v-else
        v-model:asset-form="assetForm"
        v-model:filters="assetFilters"
        v-model:pagination="assetPagination"
        :assets="knowledgeAssets"
        :busy="assetBusy || kbBusy"
        :current-user="currentUser"
        :message="assetMessage"
        :selected-asset-id="selectedAssetId"
        :users="users"
        @apply-filters="applyAssetFilters"
        @change-page="changeAssetPage"
        @change-page-size="changeAssetPageSize"
        @confirm-asset="transitionKnowledgeAsset($event, 'confirm')"
        @create-asset="createKnowledgeAsset"
        @delete-asset="deleteKnowledgeAsset"
        @edit-asset="editAsset"
        @mark-stale="transitionKnowledgeAsset($event, 'mark-stale')"
        @refresh-assets="loadKnowledgeAssets"
        @reset-asset="resetAssetForm"
        @update-asset="updateKnowledgeAsset"
      />
      </ElContainer>
    </ElContainer>
  </ElConfigProvider>
</template>
