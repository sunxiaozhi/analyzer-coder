<script setup lang="ts">
import {
  BookOpenCheck,
  ChevronDown,
  Cpu,
  FolderCog,
  LayoutDashboard,
  ListChecks,
  LogOut,
  MessageSquareText,
  ScrollText,
  Search,
  Settings,
  Users,
  Workflow,
} from 'lucide-vue-next';
import { computed, nextTick, onMounted, reactive, shallowRef, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/authStore';
import { useRepositoryStore } from '@/stores/repositoryStore';
import { useWorkspaceTabsStore, type WorkspaceTab } from '@/stores/workspaceTabs';
import WorkspaceTabs from '@/components/WorkspaceTabs.vue';
import ProductLogo from '@/components/ProductLogo.vue';
import {
  workspaceNavigation,
  type WorkspaceNavIcon,
} from '@/components/workspaceNavigation';

const route = useRoute(); const router = useRouter(); const auth = useAuthStore(); const repositoryStore = useRepositoryStore();
const workspaceTabs = useWorkspaceTabsStore();
const refreshVersions = reactive<Record<string, number>>({});
const systemOpen = shallowRef(true);
const iconComponents: Record<WorkspaceNavIcon, object> = {
  overview: LayoutDashboard,
  code: Search,
  ask: MessageSquareText,
  review: Workflow,
  knowledge: BookOpenCheck,
  projects: FolderCog,
  tasks: ListChecks,
  models: Cpu,
  accounts: Users,
  audit: ScrollText,
};
const canMaintainSelectedRepository = computed(() => (
  auth.isAdmin || Boolean(repositoryStore.selectedRepository?.capabilities.canUpdate)
));
const canManageProjects = computed(() => (
  auth.isAdmin
  || repositoryStore.repositories.some(repository => repository.capabilities.canUpdate)
  || (repositoryStore.initialized && repositoryStore.repositories.length === 0)
));
const navGroups = computed(() => workspaceNavigation({
  isAdmin: auth.isAdmin,
  canMaintainSelectedRepository: canMaintainSelectedRepository.value,
  canManageProjects: canManageProjects.value,
}));
const visibleNavItems = computed(() => navGroups.value.flatMap(group => group.items));
const titles: Record<string, string> = { overview: '项目总览', 'change-impact': '变更审查', repositories: '项目管理', indexing: '索引任务', search: '代码与证据', ask: '问项目', graph: '代码与证据', knowledge: '知识治理', accounts: '账号权限', audit: '审计日志', settings: '模型配置' };
const pageTitle = computed(() => titles[String(route.name)] ?? '代码知识平台');
const activeRouteName = computed(() => String(route.name ?? ''));
async function logout() { await auth.logout(); workspaceTabs.closeAll(); await router.replace('/login'); }
async function changeRepository(repositoryId: string | null) {
  try {
    await repositoryStore.selectRepository(repositoryId);
    if (
      route.meta.repositoryMaintain
      && !auth.isAdmin
      && !repositoryStore.selectedRepository?.capabilities.canUpdate
    ) await router.replace('/overview');
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
watch(() => route.name, name => {
  if (['indexing', 'settings', 'accounts', 'audit'].includes(String(name))) systemOpen.value = true;
}, { immediate: true });
watch(visibleNavItems, items => {
  workspaceTabs.retain(new Set(items.map(item => item.to.slice(1))));
});
onMounted(() => {
  workspaceTabs.retain(new Set(visibleNavItems.value.map(item => item.to.slice(1))));
  void repositoryStore.loadRepositories();
});
</script>

<template><div class="app-shell">
  <aside class="sidebar"><RouterLink class="brand" to="/overview"><span class="brand-mark"><ProductLogo /></span><span>项目知识平台</span></RouterLink>
    <nav class="nav-list" aria-label="主导航">
      <section v-for="group in navGroups" :key="group.key" class="nav-section" :data-group="group.key">
        <button
          v-if="group.collapsible"
          type="button"
          class="system-toggle"
          :aria-expanded="systemOpen"
          @click="systemOpen = !systemOpen"
        >
          <Settings :size="14" />
          <span>{{ group.label }}</span>
          <ChevronDown :size="13" class="system-chevron" :class="{ open: systemOpen }" />
        </button>
        <span v-else class="nav-section-label">{{ group.label }}</span>
        <div v-show="!group.collapsible || systemOpen" class="nav-section-links">
          <RouterLink v-for="item in group.items" :key="item.to" class="nav-link" :to="item.to">
            <component :is="iconComponents[item.icon]" :size="16" />
            <span>{{ item.label }}</span>
          </RouterLink>
        </div>
      </section>
    </nav>
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

<style scoped>
.nav-list { align-content: start; overflow-y: auto; }
.nav-section { display: grid; gap: 3px; }
.nav-section + .nav-section { margin-top: 11px; padding-top: 11px; border-top: 1px solid #ededf0; }
.nav-section-label { padding: 0 12px 4px; color: #8a8a92; font-size: 12px; font-weight: 750; letter-spacing: .11em; }
.nav-section-links { display: grid; gap: 3px; }
.system-toggle { display: grid; grid-template-columns: 16px minmax(0, 1fr) 14px; align-items: center; gap: 10px; height: 32px; padding: 0 12px; color: #65656c; border: 0; border-radius: 6px; background: transparent; text-align: left; font-size: 13px; font-weight: 700; }
.system-toggle:hover { color: #1d1d1f; background: #f5f7fa; }
.system-chevron { transition: transform .16s ease; }
.system-chevron.open { transform: rotate(180deg); }
.nav-section[data-group='system'] .nav-link { padding-left: 16px; }
@media (max-width: 1050px) {
  .nav-section-label, .system-toggle span, .system-chevron { display: none; }
  .system-toggle { display: flex; justify-content: center; width: 100%; padding: 0; }
  .nav-section[data-group='system'] .nav-link { padding-left: 0; }
}
@media (max-width: 760px) {
  .nav-list, .nav-section, .nav-section-links { display: flex; flex: none; }
  .nav-section { align-items: center; }
  .nav-section + .nav-section { margin: 0 0 0 4px; padding: 0 0 0 4px; border-top: 0; border-left: 1px solid #ededf0; }
  .system-toggle { width: 38px; height: 38px; }
}
</style>
