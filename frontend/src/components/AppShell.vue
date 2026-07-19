<script setup lang="ts">
import {
  Bell,
  BookOpen,
  Boxes,
  CircleHelp,
  GitBranch,
  Network,
  Search,
  Settings,
  Sparkles,
  UserRound,
  Users,
  ListChecks,
} from 'lucide-vue-next';
import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const activeRepository = ref('ai-platform-service');

const navItems = [
  { to: '/repositories', label: '仓库管理', icon: GitBranch },
  { to: '/indexing', label: '索引任务', icon: ListChecks },
  { to: '/search', label: '代码检索', icon: Search },
  { to: '/ask', label: '代码问答', icon: Sparkles },
  { to: '/graph', label: '调用图谱', icon: Network },
  { to: '/knowledge', label: '知识卡片', icon: BookOpen },
  { to: '/accounts', label: '账号管理', icon: Users },
  { to: '/settings', label: '系统设置', icon: Settings },
];

const repositoryRoutes = new Set(['search', 'ask', 'graph', 'knowledge']);
const hasRepositoryContext = computed(() => repositoryRoutes.has(String(route.name)));
const breadcrumbLabel = computed(() => {
  if (route.name === 'repositories') return '仓库管理';
  if (route.name === 'indexing') return '索引任务';
  if (route.name === 'accounts') return '账号管理';
  if (route.name === 'settings') return '系统设置';
  return pageMeta.value.title;
});
const pageMeta = computed(() => ({
  repositories: { title: '仓库', subtitle: '管理本地代码仓库与知识索引', secondary: '批量检测', primary: '添加仓库' },
  indexing: { title: '索引任务', subtitle: '追踪每个阶段，失败时保留上一版可用索引', secondary: '查看产物', primary: '开始全量索引' },
  search: { title: '代码检索', subtitle: '综合符号、语义与调用关系定位实现', secondary: '', primary: '检索说明' },
  ask: { title: '代码问答', subtitle: '回答中的每个仓库事实都可回到代码证据', secondary: '', primary: '新建会话' },
  graph: { title: '调用图与影响分析', subtitle: '查看修改 OrderService.createOrder 的潜在影响', secondary: '导出清单', primary: '重新分析' },
  knowledge: { title: '知识卡片', subtitle: '保存经过人工确认的团队知识', secondary: '导入', primary: '新建卡片' },
  accounts: { title: '账号管理', subtitle: '管理平台账号、角色与访问状态', secondary: '角色说明', primary: '新增账号' },
  settings: { title: '系统设置', subtitle: '配置代码访问边界、模型连接与索引策略', secondary: '', primary: '保存更改' },
}[String(route.name)] ?? { title: '', subtitle: '', secondary: '', primary: '' }));
function headerAction(label: string) {
  window.dispatchEvent(new CustomEvent('page-primary-action', { detail: { route: route.name, label } }));
  ElMessage.info(label + '操作已由页面 mock');
}
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <RouterLink class="brand" to="/repositories">
        <span class="brand-mark"><Boxes :size="16" /></span>
        <span>代码知识平台</span>
      </RouterLink>
      <nav class="nav-list" aria-label="主导航">
        <RouterLink v-for="item in navItems" :key="item.to" class="nav-link" :to="item.to">
          <component :is="item.icon" :size="16" />
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>
      <div class="sidebar-footer">本地部署 · v0.1</div>
    </aside>

    <main class="workspace">
      <header class="topbar">
        <span class="repository-label">当前仓库</span>
        <el-select v-model="activeRepository" class="global-repository-switcher" aria-label="切换当前仓库">
          <el-option label="ai-platform-service" value="ai-platform-service" />
          <el-option label="order-service" value="order-service" />
          <el-option label="gateway" value="gateway" />
          <el-option label="frontend-web" value="frontend-web" />
          <el-option label="common-lib" value="common-lib" />
        </el-select>
        <div class="topbar-spacer" />
        <button class="global-search" type="button" @click="router.push('/search')">
          <Search :size="14" /><span>全局搜索</span><kbd>Ctrl K</kbd>
        </button>
        <button class="top-icon" type="button" title="帮助"><CircleHelp :size="16" /></button>
        <button class="top-icon" type="button" title="通知"><Bell :size="16" /></button>
        <button class="avatar-button" type="button" title="账号"><UserRound :size="15" /></button>
      </header>
      <div class="page-frame">
        <div class="page-context">
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/repositories' }">代码知识平台</el-breadcrumb-item>
            <el-breadcrumb-item>{{ breadcrumbLabel }}</el-breadcrumb-item>
          </el-breadcrumb>
          <template v-if="hasRepositoryContext">
            <span class="context-divider"></span>
            <strong class="active-repository-name">{{ activeRepository }}</strong>
            <span class="context-chip mono">main</span>
            <span class="context-chip mono">a1b2c3d</span>
            <span class="fresh-state">● 索引已更新</span>
          </template>
        </div>
        <div class="page-masthead">
          <div>
            <h1>{{ pageMeta.title }}</h1>
            <p>{{ pageMeta.subtitle }}</p>
          </div>
          <div class="masthead-actions">
            <el-button v-if="pageMeta.secondary" @click="headerAction(pageMeta.secondary)">{{ pageMeta.secondary }}</el-button>
            <el-button type="primary" @click="headerAction(pageMeta.primary)">
              {{ pageMeta.primary.startsWith('添加') || pageMeta.primary.startsWith('新增') || pageMeta.primary.startsWith('新建') ? '+ ' : '' }}{{ pageMeta.primary }}
            </el-button>
          </div>
        </div>
        <div class="route-view"><RouterView /></div>
      </div>
    </main>
  </div>
</template>
