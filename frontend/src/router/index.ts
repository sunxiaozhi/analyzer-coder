import { createRouter, createWebHistory } from 'vue-router';
import WorkspaceShell from '@/components/WorkspaceShell.vue';
import AccountsView from '@/views/AccountsView.vue';
import AuditLogsView from '@/views/AuditLogsView.vue';
import AskView from '@/views/AskView.vue';
import ChangeImpactView from '@/views/ChangeImpactView.vue';
import ChunksView from '@/views/ChunksM0View.vue';
import IndexJobsView from '@/views/UnifiedIndexJobsView.vue';
import ProjectOverviewView from '@/views/ProjectOverviewView.vue';
import KnowledgeView from '@/views/KnowledgeView.vue';
import LoginView from '@/views/LoginView.vue';
import RepositoriesView from '@/views/RepositoriesM0View.vue';
import SystemSettingsView from '@/views/SystemSettingsView.vue';

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
    { path: '/', component: WorkspaceShell, children: [
      { path: '', redirect: '/overview' },
      { path: 'overview', name: 'overview', component: ProjectOverviewView, meta: { title: '项目总览' } },
      { path: 'change-impact', name: 'change-impact', component: ChangeImpactView, meta: { title: '变更审查' } },
      { path: 'repositories', name: 'repositories', component: RepositoriesView, meta: { title: '项目管理', projectManage: true } },
      { path: 'indexing', name: 'indexing', component: IndexJobsView, meta: { admin: true, title: '索引任务' } },
      { path: 'search', name: 'search', component: ChunksView, meta: { title: '代码与证据' } },
      { path: 'ask', name: 'ask', component: AskView, meta: { title: '问项目' } },
      {
        path: 'graph',
        name: 'graph',
        redirect: to => ({
          name: 'search',
          query: { ...to.query, relation: to.query.analyze === '1' ? '1' : to.query.relation ?? '1' },
        }),
        meta: { title: '代码与证据' },
      },
      { path: 'knowledge', name: 'knowledge', component: KnowledgeView, meta: { title: '知识治理', repositoryMaintain: true } },
      { path: 'accounts', name: 'accounts', component: AccountsView, meta: { admin: true, title: '账号权限' } },
      { path: 'audit', name: 'audit', component: AuditLogsView, meta: { admin: true, title: '审计日志' } },
      { path: 'settings', name: 'settings', component: SystemSettingsView, meta: { admin: true, title: '模型配置' } },
    ] },
  ],
});

router.beforeEach(async (to) => {
  const { useAuthStore } = await import('@/stores/authStore');
  const auth = useAuthStore();
  await auth.restore();
  if (to.meta.public) {
    if (auth.authenticated && !auth.account?.mustChangePassword) return '/overview';
    return true;
  }
  if (!auth.authenticated) return { path: '/login', query: { redirect: to.fullPath } };
  if (auth.account?.mustChangePassword) return '/login';
  if (to.meta.admin && !auth.isAdmin) return '/overview';
  if (to.meta.repositoryMaintain || to.meta.projectManage) {
    const { useRepositoryStore } = await import('@/stores/repositoryStore');
    const repositories = useRepositoryStore();
    if (!repositories.initialized) await repositories.loadRepositories();
    if (
      to.meta.repositoryMaintain
      && !auth.isAdmin
      && !repositories.selectedRepository?.capabilities.canUpdate
    ) return '/overview';
    if (to.meta.projectManage && !auth.isAdmin) {
      const canManage = repositories.repositories.some(
        repository => repository.capabilities.canUpdate,
      ) || (repositories.repositories.length === 0 && !repositories.error);
      if (!canManage) return '/overview';
    }
  }
  return true;
});
