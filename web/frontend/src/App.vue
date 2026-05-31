<script setup lang="ts">
import { ElAside, ElContainer, ElHeader, ElTag } from 'element-plus'
import AnalyzerSidebar from './components/analyzer/AnalyzerSidebar.vue'
import AnalysisResultPanel from './components/analyzer/AnalysisResultPanel.vue'
import ProjectManagementPanel from './components/analyzer/ProjectManagementPanel.vue'
import SemanticSearchPanel from './components/analyzer/SemanticSearchPanel.vue'
import VectorManagementPanel from './components/analyzer/VectorManagementPanel.vue'
import { useAnalyzerConsole } from './composables/useAnalyzerConsole'

const {
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
  createProject,
  pullProject,
  loadProjects
} = useAnalyzerConsole()
</script>

<template>
  <ElContainer class="shell">
    <ElAside class="shell-aside">
      <AnalyzerSidebar
        v-model:active-section="activeSection"
        :selected-project="selectedProject"
        @check-health="checkHealth"
      />
    </ElAside>

    <ElContainer class="workspace">
      <ElHeader class="topbar">
        <div>
          <span class="status-dot"></span>
          <span>{{ status }}</span>
        </div>
        <ElTag v-if="busy" type="warning" effect="plain">运行中</ElTag>
      </ElHeader>

      <ProjectManagementPanel
        v-if="activeSection === 'projects'"
        v-model:form="form"
        v-model:project-form="projectForm"
        :project-busy="projectBusy"
        :projects="projects"
        :selected-project="selectedProject"
        @create-project="createProject"
        @pull-project="pullProject"
        @refresh-projects="loadProjects"
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
        :saved-path="savedPath"
        @analyze="analyze"
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
        :saved-path="savedPath"
        @query-store="queryStore"
      />
    </ElContainer>
  </ElContainer>
</template>
