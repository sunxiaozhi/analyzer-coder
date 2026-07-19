import { createRouter, createWebHistory } from 'vue-router';
import AppShell from '@/components/AppShell.vue';
import AccountsView from '@/views/AccountsView.vue';
import AskView from '@/views/AskView.vue';
import ChunksView from '@/views/ChunksView.vue';
import GraphView from '@/views/GraphView.vue';
import IndexJobsView from '@/views/IndexJobsView.vue';
import KnowledgeView from '@/views/KnowledgeView.vue';
import LoginView from '@/views/LoginView.vue';
import RepositoriesView from '@/views/RepositoriesView.vue';
import SystemSettingsView from '@/views/SystemSettingsView.vue';

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView },
    {
      path: '/',
      component: AppShell,
      children: [
        { path: '', redirect: '/repositories' },
        { path: 'repositories', name: 'repositories', component: RepositoriesView },
        { path: 'indexing', name: 'indexing', component: IndexJobsView },
        { path: 'search', name: 'search', component: ChunksView },
        { path: 'ask', name: 'ask', component: AskView },
        { path: 'graph', name: 'graph', component: GraphView },
        { path: 'knowledge', name: 'knowledge', component: KnowledgeView },
        { path: 'accounts', name: 'accounts', component: AccountsView },
        { path: 'settings', name: 'settings', component: SystemSettingsView },
      ],
    },
  ],
});

