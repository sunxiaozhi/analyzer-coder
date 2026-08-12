<script setup lang="ts">
import { BookOpen, Boxes, GitBranch, ListChecks, LogOut, Search, Settings, Users } from 'lucide-vue-next';
import { computed, onMounted, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/authStore';
import { useRepositoryStore } from '@/stores/repositoryStore';
import { useWorkspaceTabsStore, type WorkspaceTab } from '@/stores/workspaceTabs';
import WorkspaceTabs from '@/components/WorkspaceTabs.vue';
import ProductLogo from '@/components/ProductLogo.vue';

const route = useRoute(); const router = useRouter(); const auth = useAuthStore(); const repositoryStore = useRepositoryStore();
const workspaceTabs = useWorkspaceTabsStore();
const navItems = computed(() => [
  { to: '/ask', label: '知识问答', icon: BookOpen }, { to: '/knowledge', label: '知识卡片', icon: BookOpen },
  { to: '/graph', label: '调用图谱', icon: Boxes }, { to: '/search', label: '源码检索', icon: Search },
  { to: '/repositories', label: '仓库管理', icon: GitBranch }, { to: '/indexing', label: '索引任务', icon: ListChecks },
  ...(auth.isAdmin ? [{ to: '/accounts', label: '账号管理', icon: Users }, { to: '/settings', label: '系统设置', icon: Settings }] : []),
]);
const titles: Record<string, string> = { repositories: '仓库管理', indexing: '索引任务', search: '源码检索', ask: '知识问答', graph: '调用图谱', knowledge: '知识卡片', accounts: '账号管理', settings: '系统设置' };
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
function closeTab(tab: WorkspaceTab) {
  const wasActive = tab.name === activeRouteName.value;
  const next = workspaceTabs.close(tab.name);
  if (!wasActive) return;
  const target = next?.fullPath ?? '/ask';
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
function closeOtherTabs() {
  workspaceTabs.closeOthers(activeRouteName.value);
}
function closeAllTabs() {
  workspaceTabs.closeAll();
  if (route.path !== '/ask') {
    void router.push('/ask');
  } else {
    workspaceTabs.open({ name: 'ask', title: '知识问答', fullPath: route.fullPath });
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
  <aside class="sidebar"><RouterLink class="brand" to="/ask"><span class="brand-mark"><ProductLogo /></span><span>代码知识平台</span></RouterLink>
    <nav class="nav-list" aria-label="主导航"><RouterLink v-for="item in navItems" :key="item.to" class="nav-link" :to="item.to"><component :is="item.icon" :size="16" /><span>{{ item.label }}</span></RouterLink></nav>
  </aside>
  <main class="workspace"><header class="topbar"><span class="repository-label">当前仓库</span><el-select :model-value="repositoryStore.selectedRepositoryId" class="global-repository-switcher" placeholder="请选择仓库" clearable @change="changeRepository"><el-option v-for="repository in repositoryStore.repositories" :key="repository.id" :label="repository.name" :value="repository.id" /></el-select><div class="topbar-spacer" /><span class="context-chip">{{ auth.account?.displayName }} · {{ auth.isAdmin ? '管理员' : '普通用户' }}</span><el-button link title="退出登录" @click="logout"><LogOut :size="16" /></el-button></header>
    <div class="page-frame">
      <WorkspaceTabs
        :tabs="workspaceTabs.tabs"
        :active-name="activeRouteName"
        @activate="activateTab"
        @close="closeTab"
        @close-others="closeOtherTabs"
        @close-all="closeAllTabs"
      />
      <div class="route-view">
        <RouterView v-slot="{ Component, route: viewRoute }">
          <KeepAlive :max="12">
            <component :is="Component" :key="String(viewRoute.name)" />
          </KeepAlive>
        </RouterView>
      </div>
    </div>
  </main>
</div></template>
