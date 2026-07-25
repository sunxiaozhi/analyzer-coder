<script setup lang="ts">
import { BookOpen, Boxes, GitBranch, ListChecks, LogOut, Search, Settings, Users } from 'lucide-vue-next';
import { computed, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/authStore';
import { useRepositoryStore } from '@/stores/repositoryStore';

const route = useRoute(); const router = useRouter(); const auth = useAuthStore(); const repositoryStore = useRepositoryStore();
const navItems = computed(() => [
  { to: '/ask', label: '代码问答', icon: BookOpen }, { to: '/knowledge', label: '知识卡片', icon: BookOpen },
  { to: '/graph', label: '调用图谱', icon: Boxes }, { to: '/search', label: '代码工作台', icon: Search },
  { to: '/repositories', label: '仓库管理', icon: GitBranch }, { to: '/indexing', label: '索引任务', icon: ListChecks },
  ...(auth.isAdmin ? [{ to: '/accounts', label: '账号管理', icon: Users }, { to: '/settings', label: '系统设置', icon: Settings }] : []),
]);
const titles: Record<string, string> = { repositories: '仓库管理', indexing: '索引任务', search: '代码工作台', ask: '代码问答', graph: '调用图谱', knowledge: '知识卡片', accounts: '账号管理', settings: '系统设置' };
const pageTitle = computed(() => titles[String(route.name)] ?? '代码知识平台');
async function logout() { await auth.logout(); await router.replace('/login'); }
async function changeRepository(repositoryId: string | null) {
  try {
    await repositoryStore.selectRepository(repositoryId);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存当前仓库失败');
  }
}
onMounted(() => void repositoryStore.loadRepositories());
</script>

<template><div class="app-shell">
  <aside class="sidebar"><RouterLink class="brand" to="/repositories"><span class="brand-mark"><Boxes :size="16" /></span><span>代码知识平台</span></RouterLink>
    <nav class="nav-list" aria-label="主导航"><RouterLink v-for="item in navItems" :key="item.to" class="nav-link" :to="item.to"><component :is="item.icon" :size="16" /><span>{{ item.label }}</span></RouterLink></nav>
    <div class="sidebar-footer">完整功能版 · PostgreSQL / pgvector</div>
  </aside>
  <main class="workspace"><header class="topbar"><span class="repository-label">当前仓库</span><el-select :model-value="repositoryStore.selectedRepositoryId" class="global-repository-switcher" placeholder="请选择仓库" clearable @change="changeRepository"><el-option v-for="repository in repositoryStore.repositories" :key="repository.id" :label="repository.name" :value="repository.id" /></el-select><div class="topbar-spacer" /><span class="context-chip">{{ auth.account?.displayName }} · {{ auth.isAdmin ? '管理员' : '普通用户' }}</span><el-button link title="退出登录" @click="logout"><LogOut :size="16" /></el-button></header>
    <div class="page-frame"><div class="page-context"><el-breadcrumb separator="/"><el-breadcrumb-item :to="{ path: '/repositories' }">代码知识平台</el-breadcrumb-item><el-breadcrumb-item>{{ pageTitle }}</el-breadcrumb-item></el-breadcrumb></div><div class="route-view"><RouterView /></div></div>
  </main>
</div></template>
