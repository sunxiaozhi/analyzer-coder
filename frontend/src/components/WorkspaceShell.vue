<script setup lang="ts">
import {
  BookOpenCheck,
  ChevronDown,
  CircleHelp,
  Cpu,
  FolderCog,
  GitBranch,
  LayoutDashboard,
  Orbit,
  ListChecks,
  LogOut,
  Plug,
  MessageSquareText,
  ScrollText,
  Search,
  Settings,
  Users,
  RefreshCw,
} from 'lucide-vue-next';
import { computed, nextTick, onMounted, reactive, shallowRef, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/authStore';
import { useRepositoryStore } from '@/stores/repositoryStore';
import { useBranchContextStore } from '@/stores/branchContextStore';
import { useWorkspaceTabsStore, type WorkspaceTab } from '@/stores/workspaceTabs';
import WorkspaceTabs from '@/components/WorkspaceTabs.vue';
import ProductLogo from '@/components/ProductLogo.vue';
import {
  workspaceNavigation,
  type WorkspaceNavIcon,
} from '@/components/workspaceNavigation';

const route = useRoute(); const router = useRouter(); const auth = useAuthStore(); const repositoryStore = useRepositoryStore();
const branchContext = useBranchContextStore();
const workspaceTabs = useWorkspaceTabsStore();
const refreshVersions = reactive<Record<string, number>>({});
const systemOpen = shallowRef(true);
const iconComponents: Record<WorkspaceNavIcon, object> = {
  overview: LayoutDashboard,
  atlas: Orbit,
  code: Search,
  ask: MessageSquareText,
  knowledge: BookOpenCheck,
  projects: FolderCog,
  tasks: ListChecks,
  models: Cpu,
  accounts: Users,
  audit: ScrollText,
};

// Every authenticated account can import its own repository; row actions remain capability-gated.
const canManageProjects = computed(() => auth.authenticated);
const navGroups = computed(() => workspaceNavigation({
  isAdmin: auth.isAdmin,
  canReadSelectedRepository: Boolean(repositoryStore.selectedRepository),
  canManageProjects: canManageProjects.value,
}));
const visibleNavItems = computed(() => navGroups.value.flatMap(group => group.items));
const branchAwareRepository = computed(() => {
  const sourceType = repositoryStore.selectedRepository?.sourceType;
  return sourceType === 'LOCAL_GIT' || sourceType === 'REMOTE_GIT' || sourceType === 'GITLAB';
});
const titles: Record<string, string> = { help: '功能导航', mcp: 'MCP 接入', overview: '项目总览', repositories: '项目管理', indexing: '任务中心', search: '联合检索', ask: '项目问答', graph: '联合检索', knowledge: '知识管理', accounts: '账号权限', audit: '审计日志', settings: '模型配置' };
const pageTitle = computed(() => route.name === 'atlas' ? '代码图谱' : titles[String(route.name)] ?? '代码知识平台');
const activeRouteName = computed(() => String(route.name ?? ''));
async function logout() { await auth.logout(); workspaceTabs.closeAll(); await router.replace('/login'); }
async function changeRepository(repositoryId: string | null) {
  try {
    await repositoryStore.selectRepository(repositoryId);
    const query = { ...route.query };
    delete query.branchId;
    delete query.contextId;
    await router.replace({ query });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存当前仓库失败');
  }
}
async function changeBranch(branchId: string) {
  try {
    await branchContext.select(branchId);
    const query = {
      ...route.query,
      branchId,
      contextId: branchContext.context?.contextId ?? undefined,
    };
    await router.replace({ query });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '切换分支失败');
  }
}
async function refreshBranchContext() {
  try {
    await branchContext.refresh();
    if (branchContext.context) {
      await router.replace({
        query: {
          ...route.query,
          branchId: branchContext.context.branchId,
          contextId: branchContext.context.contextId,
        },
      });
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '刷新分支版本失败');
  }
}
function branchStatusLabel(status: string) {
  return ({ READY: '可用', BUILDING: '构建中', PENDING: '待准备', FAILED: '失败' } as Record<string, string>)[status] ?? status;
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
  workspaceTabs.retain(new Set(['help', 'mcp', ...items.map(item => item.to.slice(1))]));
});
watch(
  () => [repositoryStore.selectedRepositoryId, repositoryStore.selectedRepository?.sourceType] as const,
  ([repositoryId]) => {
    if (!branchAwareRepository.value) {
      branchContext.clear();
      if (route.query.branchId || route.query.contextId) {
        const query = { ...route.query };
        delete query.branchId;
        delete query.contextId;
        void router.replace({ query });
      }
      return;
    }
    const preferred = typeof route.query.branchId === 'string' ? route.query.branchId : null;
    void branchContext.load(repositoryId, preferred, repositoryStore.selectedRepository?.branch).then(async () => {
      if (!branchContext.context || route.query.branchId === branchContext.context.branchId) return;
      await router.replace({
        query: {
          ...route.query,
          branchId: branchContext.context.branchId,
          contextId: branchContext.context.contextId,
        },
      });
    });
  },
  { immediate: true },
);
watch(
  () => route.query.branchId,
  branchId => {
    if (typeof branchId !== 'string' || branchId === branchContext.selectedBranchId) return;
    if (branchContext.activeBranches.some(item => item.id === branchId)) void changeBranch(branchId);
  },
);
onMounted(() => {
  workspaceTabs.retain(new Set(['help', 'mcp', ...visibleNavItems.value.map(item => item.to.slice(1))]));
  void repositoryStore.loadRepositories();
});
</script>

<template><div class="app-shell">
  <aside class="sidebar"><RouterLink class="brand" to="/overview"><span class="brand-mark"><ProductLogo /></span><span>代码知识平台</span></RouterLink>
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
  <main class="workspace"><header class="topbar"><span class="repository-label">当前仓库</span><el-select :model-value="repositoryStore.selectedRepositoryId" class="global-repository-switcher" placeholder="请选择仓库" filterable @change="changeRepository"><el-option v-for="repository in repositoryStore.repositories" :key="repository.id" :label="repository.name" :value="repository.id" /></el-select>
    <div v-if="repositoryStore.selectedRepositoryId && branchAwareRepository" class="branch-lock" :data-ready="branchContext.ready">
      <GitBranch :size="15" />
      <el-select
        :model-value="branchContext.selectedBranchId"
        class="global-branch-switcher"
        placeholder="选择分支"
        :loading="branchContext.loading"
        @change="changeBranch"
      >
        <el-option
          v-for="branch in branchContext.activeBranches"
          :key="branch.id"
          :label="`${branch.name} · ${branchStatusLabel(branch.status)}`"
          :value="branch.id"
          :disabled="branch.status !== 'READY'"
        />
      </el-select>
      <span v-if="branchContext.context" class="locked-commit mono" title="当前页面数据锁定到该提交">
        锁定 {{ branchContext.context.commitSha.slice(0, 8) }}
      </span>
      <span v-else class="locked-commit">{{ branchContext.error ?? '快照未就绪' }}</span>
      <button class="branch-refresh" type="button" title="刷新分支和锁定版本" @click="refreshBranchContext"><RefreshCw :size="14" /></button>
    </div>
    <div class="topbar-spacer" /><RouterLink class="help-entry" to="/help" title="查看功能导航和数据来源"><CircleHelp :size="16" /><span>帮助说明</span></RouterLink><RouterLink class="mcp-entry" to="/mcp" title="查看 MCP 接入指导"><Plug :size="16" /><span>MCP 接入</span></RouterLink><span class="context-chip">{{ auth.account?.displayName }} · {{ auth.isAdmin ? '管理员' : '普通用户' }}</span><el-button link title="退出登录" @click="logout"><LogOut :size="16" /></el-button></header>
    <div class="page-frame">
      <div class="workspace-tab-row">
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
      </div>

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
.page-frame { grid-template-rows: 44px minmax(0, 1fr); }
.workspace-tab-row { display: flex; min-width: 0; align-items: center; gap: 8px; }
.workspace-tab-row > :first-child { flex: 1; min-width: 0; }
.branch-lock { display: flex; min-width: 0; align-items: center; gap: 6px; margin-left: 8px; padding: 3px 5px 3px 8px; color: #8a5a20; border: 1px solid #e3d0b5; border-radius: 6px; background: #fff9ef; }
.branch-lock[data-ready='true'] { color: #275f4a; border-color: #bad8ca; background: #f1f8f5; }
.global-branch-switcher { width: 176px; }
.locked-commit { max-width: 180px; overflow: hidden; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.branch-refresh { display: inline-grid; width: 28px; height: 28px; place-content: center; color: inherit; border: 0; border-radius: 4px; background: transparent; cursor: pointer; }
.branch-refresh:hover { background: rgb(255 255 255 / 80%); }
.help-entry, .mcp-entry { display: inline-flex; align-items: center; gap: 6px; flex-shrink: 0; padding: 7px 10px; border-radius: 6px; color: #526071; font-size: 13px; text-decoration: none; }
.help-entry:hover, .help-entry.router-link-active, .mcp-entry:hover, .mcp-entry.router-link-active { color: #2563eb; background: #eff6ff; }
.help-entry:focus-visible, .mcp-entry:focus-visible { outline: 2px solid #93c5fd; outline-offset: 2px; }
.sidebar { min-height: 0; overflow: hidden; }
.brand { flex: none; }
.nav-list {
  flex: 1 1 auto;
  min-height: 0;
  align-content: start;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}
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
  .locked-commit { display: none; }
  .global-branch-switcher { width: 140px; }
  .nav-section-label, .system-toggle span, .system-chevron { display: none; }
  .system-toggle { display: flex; justify-content: center; width: 100%; padding: 0; }
  .nav-section[data-group='system'] .nav-link { padding-left: 0; }
}
@media (max-width: 760px) {
  .branch-lock { display: none; }
  .sidebar { overflow-x: auto; overflow-y: hidden; }
  .nav-list, .nav-section, .nav-section-links { display: flex; flex: none; }
  .nav-list { min-height: auto; overflow: visible; scrollbar-gutter: auto; }
  .nav-section { align-items: center; }
  .nav-section + .nav-section { margin: 0 0 0 4px; padding: 0 0 0 4px; border-top: 0; border-left: 1px solid #ededf0; }
  .system-toggle { width: 38px; height: 38px; }
}
@media (max-width: 620px) {
  .topbar { gap: 6px; padding: 0 10px; }
  .topbar .global-repository-switcher { width: min(180px, calc(100vw - 166px)); }
  .help-entry, .mcp-entry { justify-content: center; width: 34px; height: 34px; padding: 0; }
  .help-entry span, .mcp-entry span, .context-chip { display: none; }
}
</style>
