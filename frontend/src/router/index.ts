import { createRouter, createWebHistory } from 'vue-router';
import WorkspaceShell from '@/components/WorkspaceShell.vue';
import AccountsView from '@/views/AccountsView.vue';
import AskView from '@/views/AskView.vue';
import ChunksView from '@/views/ChunksM0View.vue';
import GraphView from '@/views/GraphView.vue';
import IndexJobsView from '@/views/UnifiedIndexJobsView.vue';
import KnowledgeView from '@/views/KnowledgeView.vue';
import LoginView from '@/views/LoginView.vue';
import RepositoriesView from '@/views/RepositoriesM0View.vue';
import SystemSettingsView from '@/views/SystemSettingsView.vue';

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
    { path: '/', component: WorkspaceShell, children: [
      { path: '', redirect: '/repositories' },
      { path: 'repositories', name: 'repositories', component: RepositoriesView, meta: { title: '仓库管理' } },
      { path: 'indexing', name: 'indexing', component: IndexJobsView, meta: { title: '索引任务' } },
      { path: 'search', name: 'search', component: ChunksView, meta: { title: '代码工作台' } },
      { path: 'ask', name: 'ask', component: AskView, meta: { title: '代码问答' } },
      { path: 'graph', name: 'graph', component: GraphView, meta: { title: '调用图谱' } },
      { path: 'knowledge', name: 'knowledge', component: KnowledgeView, meta: { title: '知识卡片' } },
      { path: 'accounts', name: 'accounts', component: AccountsView, meta: { admin: true, title: '账号管理' } },
      { path: 'settings', name: 'settings', component: SystemSettingsView, meta: { admin: true, title: '系统设置' } },
    ] },
  ],
});

router.beforeEach(async (to) => {
  const { useAuthStore } = await import('@/stores/authStore');
  const auth = useAuthStore();
  await auth.restore();
  if (to.meta.public) {
    if (auth.authenticated && !auth.account?.mustChangePassword) return '/repositories';
    return true;
  }
  if (!auth.authenticated) return { path: '/login', query: { redirect: to.fullPath } };
  if (auth.account?.mustChangePassword) return '/login';
  if (to.meta.admin && !auth.isAdmin) return '/repositories';
  return true;
});
