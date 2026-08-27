<script setup lang="ts">
import { computed } from 'vue';
import { FolderTree } from 'lucide-vue-next';
import { useRouter } from 'vue-router';
import ProjectOverviewSheet from '@/features/overview/ProjectOverviewSheet.vue';
import { useProjectOverview } from '@/features/overview/useProjectOverview';
import { useRepositoryStore } from '@/stores/repositoryStore';

const router = useRouter();
const repositories = useRepositoryStore();
const { preparation, codeFacts, loading, preparing, error, reload, prepare } = useProjectOverview();
const repository = computed(() => repositories.selectedRepository);
const profile = computed(() => preparation.value?.profile ?? null);

function openFile(path: string) {
  void router.push({ path: '/search', query: { path } });
}
</script>

<template>
  <section class="overview-page">
    <div v-if="!repositories.selectedRepositoryId" class="overview-empty">
      <span><FolderTree :size="26" /></span>
      <h1>选择一个项目</h1>
      <p>项目总览会集中展示 CodeGraph、向量、知识、代码类型和技术栈。</p>
      <el-button type="primary" @click="router.push('/repositories')">前往仓库管理</el-button>
    </div>

    <el-alert v-else-if="error" :title="error" type="error" :closable="false">
      <el-button size="small" @click="reload">重新加载</el-button>
    </el-alert>

    <ProjectOverviewSheet
      v-else-if="repository"
      v-loading="loading"
      :repository="repository"
      :preparation="preparation"
      :profile="profile"
      :code-facts="codeFacts"
      :loading="loading"
      :preparing="preparing"
      @refresh="reload"
      @prepare="prepare"
      @open-file="openFile"
    />
  </section>
</template>

<style scoped>
.overview-page { min-height: 100%; overflow: auto; padding: 2px; color: #1f2b35; background: #eef3f6; }
.overview-empty { display: grid; min-height: 480px; place-content: center; justify-items: center; padding: 36px; text-align: center; border: 1px dashed #cad2d8; border-radius: 9px; background: #fff; }
.overview-empty > span { display: grid; width: 52px; height: 52px; place-items: center; color: #175d86; border-radius: 10px; background: #e9f2f7; }
.overview-empty h1 { margin: 14px 0 5px; font-size: 22px; }
.overview-empty p { max-width: 430px; margin: 0 0 18px; color: #7d8992; font-size: 11px; line-height: 1.6; }
</style>
