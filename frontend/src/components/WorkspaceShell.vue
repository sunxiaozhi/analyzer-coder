<script setup lang="ts">
import { BookOpen, Boxes, GitBranch, LayoutDashboard, ListChecks, LogOut, Search, Settings, Users, Workflow } from 'lucide-vue-next';
import { computed, nextTick, onMounted, reactive, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/authStore';
import { useRepositoryStore } from '@/stores/repositoryStore';
import { useWorkspaceTabsStore, type WorkspaceTab } from '@/stores/workspaceTabs';
import WorkspaceTabs from '@/components/WorkspaceTabs.vue';
import ProductLogo from '@/components/ProductLogo.vue';

const route = useRoute(); const router = useRouter(); const auth = useAuthStore(); const repositoryStore = useRepositoryStore();
const workspaceTabs = useWorkspaceTabsStore();
const refreshVersions = reactive<Record<string, number>>({});
const navItems = computed(() => [
  { to: '/overview', label: '项目总览', icon: LayoutDashboard },
  { to: '/change-impact', label: '变更分析', icon: Workflow },
  { to: '/ask', label: '知识问答', icon: BookOpen }, { to: '/knowledge', label: '知识卡片', icon: BookOpen },
  { to: '/graph', label: '调用图谱', icon: Boxes }, { to: '/search', label: '源码检索', icon: Search },
  { to: '/repositories', label: '仓库管理', icon: GitBranch }, { to: '/indexing', label: '索引任务', icon: ListChecks },
  ...(auth.isAdmin ? [{ to: '/accounts', label: '账号管理', icon: Users }, { to: '/settings', label: '系统设置', icon: Settings }] : []),
]);
const titles: Record<string, string> = { overview: '项目总览', 'change-impact': '变更分析', repositories: '仓库管理', indexing: '索引任务', search: '源码检索', ask: '知识问答', graph: '调用图谱', knowledge: '知识卡片', accounts: '账号管理', settings: '系统设置' };
const pageTitle = computed(() => titles[String(route.name)] ?? '代码知识平台');
const activeRouteName = computed(() => String(route.name ?? ''));
async function logout() { await auth.logout(); workspaceTabs.closeAll(); await router.replace('/login'); }
async function changeRepository(repositoryId: string | null) {
  try {
    await repositoryStore.selectRepository(repositoryId);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存当前仓库失败');
  }
}
function activateTab(tab: WorkspaceTab) {
  if (tab.fullPath !== route.fullPath) void router.push(tab.fullPath);
}
async function refreshTab(tab: WorkspaceTab) {
  refreshVersions[tab.name] = (refreshVersions[tab.name] ?? 0) + 1;
  if (tab.fullPath !== route.fullPath) await router.push(tab.fullPath);
  await nextTick();
}
function closeTab(tab: WorkspaceTab) {
  const wasActive = tab.name === activeRouteName.value;
  const next = workspaceTabs.close(tab.name);
  if (!wasActive) return;
  const target = next?.fullPath ?? '/overview';
  if (target === route.fullPath) {
    workspaceTabs.open({
      name: activeRouteName.value,
      title: String(route.meta.title ?? pageTitle.value),
      fullPath: route.fullPath,
    });
  } else {
    void router.push(target);
  }
}
function closeOtherTabs(tab?: WorkspaceTab) {
  const target = tab ?? workspaceTabs.tabs.find(item => item.name === activeRouteName.value);
  if (!target) return;
  workspaceTabs.closeOthers(target.name);
  if (target.fullPath !== route.fullPath) void router.push(target.fullPath);
}
function closeLeftTabs(tab: WorkspaceTab) {
  const closed = workspaceTabs.closeLeft(tab.name);
  if (closed.some(item => item.name === activeRouteName.value)) void router.push(tab.fullPath);
}
function closeRightTabs(tab: WorkspaceTab) {
  const closed = workspaceTabs.closeRight(tab.name);
  if (closed.some(item => item.name === activeRouteName.value)) void router.push(tab.fullPath);
}
async function copyTabLink(tab: WorkspaceTab) {
  const link = new URL(tab.fullPath, window.location.origin).toString();
  try {
    await navigator.clipboard.writeText(link);
    ElMessage.success('页面链接已复制');
  } catch {
    ElMessage.error('复制失败，请检查浏览器剪贴板权限');
  }
}
function closeAllTabs() {
  workspaceTabs.closeAll();
  if (route.path !== '/overview') {
    void router.push('/overview');
  } else {
    workspaceTabs.open({ name: 'overview', title: '项目总览', fullPath: route.fullPath });
  }
}
watch(() => route.fullPath, () => {
  if (!route.meta.public && typeof route.name === 'string') {
    workspaceTabs.open({
      name: route.name,
      title: String(route.meta.title ?? titles[route.name] ?? route.name),
      fullPath: route.fullPath,
    });
  }
}, { immediate: true });
onMounted(() => {
  workspaceTabs.retain(new Set(navItems.value.map(item => item.to.slice(1))));
  void repositoryStore.loadRepositories();
});
</script>

<template><div class="app-shell">
  <aside class="sidebar"><RouterLink class="brand" to="/overview"><span class="brand-mark"><ProductLogo /></span><span>项目知识平台</span></RouterLink>
    <nav class="nav-list" aria-label="主导航"><RouterLink v-for="item in navItems" :key="item.to" class="nav-link" :to="item.to"><component :is="item.icon" :size="16" /><span>{{ item.label }}</span></RouterLink></nav>
  </aside>
  <main class="workspace"><header class="topbar"><span class="repository-label">当前仓库</span><el-select :model-value="repositoryStore.selectedRepositoryId" class="global-repository-switcher" placeholder="请选择仓库" clearable @change="changeRepository"><el-option v-for="repository in repositoryStore.repositories" :key="repository.id" :label="repository.name" :value="repository.id" /></el-select><div class="topbar-spacer" /><span class="context-chip">{{ auth.account?.displayName }} · {{ auth.isAdmin ? '管理员' : '普通用户' }}</span><el-button link title="退出登录" @click="logout"><LogOut :size="16" /></el-button></header>
    <div class="page-frame">
      <WorkspaceTabs
        :tabs="workspaceTabs.tabs"
        :active-name="activeRouteName"
        @activate="activateTab"
        @refresh="refreshTab"
        @close="closeTab"
        @close-others="closeOtherTabs"
        @close-left="closeLeftTabs"
        @close-right="closeRightTabs"
        @close-all="closeAllTabs"
        @copy-link="copyTabLink"
      />
      <div class="route-view">
        <RouterView v-slot="{ Component, route: viewRoute }">
          <KeepAlive :max="12">
            <component
              :is="Component"
              :key="`${String(viewRoute.name)}:${refreshVersions[String(viewRoute.name)] ?? 0}`"
            />
          </KeepAlive>
        </RouterView>
      </div>
    </div>
  </main>
</div></template>
