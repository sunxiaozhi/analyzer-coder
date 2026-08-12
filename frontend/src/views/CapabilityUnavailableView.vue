<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';

const route = useRoute();
const capability = computed(() => ({
  ask: { name: '知识问答', milestone: 'M1' },
  graph: { name: '调用图与影响分析', milestone: 'M2' },
  knowledge: { name: '知识卡片', milestone: 'M2' },
  accounts: { name: '账号与授权', milestone: '后续 M0' },
  settings: { name: '系统设置', milestone: '后续 M0' },
}[String(route.name)] ?? { name: '该能力', milestone: '后续里程碑' }));
</script>

<template>
  <section class="surface unavailable-view">
    <el-result icon="info" :title="capability.name + '尚未交付'" :sub-title="'规划里程碑：' + capability.milestone">
      <template #extra>
        <el-button type="primary" @click="$router.push('/repositories')">返回 M0 工作台</el-button>
      </template>
    </el-result>
  </section>
</template>

<style scoped>
.unavailable-view {
  display: grid;
  min-height: 420px;
  place-items: center;
}
</style>
