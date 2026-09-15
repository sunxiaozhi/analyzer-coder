<script setup lang="ts">
import { shallowRef } from 'vue';
import { ElMessage } from 'element-plus';
import { useRepositoryStore } from '@/stores/repositoryStore';
import { intelligenceApi } from '@/api/intelligence';
import type { Repository } from '@/types/api';
const props=defineProps<{ repository: Repository }>();
const emit=defineEmits<{ changed: [] }>();
const store=useRepositoryStore();
const busy=shallowRef(false);
async function execute(kind:'sync'|'content'|'graph'){
  if(busy.value)return;
  busy.value=true;
  try{
    if(kind==='sync') await store.rescanRepository(props.repository.id);
    else if(kind==='content') await store.createIndexJob(props.repository.id,'FULL');
    else await intelligenceApi.buildGraph(props.repository.id);
    emit('changed'); ElMessage.success('单版本代码源操作已提交');
  }catch(error){ElMessage.error(error instanceof Error?error.message:'操作失败');}
  finally{busy.value=false;}
}
</script>
<template>
  <section class="single-version">
    <p>非 Git 项目使用单版本代码源，不提供 Git 分支切换。</p>
    <el-button v-if="repository.capabilities.canUpdate" :disabled="busy" @click="execute('sync')">更新代码版本</el-button>
    <el-button v-if="repository.capabilities.canIndex" :disabled="busy" @click="execute('content')">构建内容索引</el-button>
    <el-button v-if="repository.capabilities.canBuildCodeGraph" :disabled="busy" @click="execute('graph')">构建代码图谱</el-button>
  </section>
</template>
<style scoped>.single-version{padding:18px;background:#f5f7fa;border:1px solid #dbe3ec;border-radius:6px}.single-version p{color:#68778a;font-size:13px}</style>
