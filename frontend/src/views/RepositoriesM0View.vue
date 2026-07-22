<script setup lang="ts">
import{Plus,Search}from'@element-plus/icons-vue';
import{computed,onMounted,shallowRef}from'vue';
import{ElMessage,ElMessageBox}from'element-plus';
import RepositoryFormDialog from'@/features/repositories/RepositoryFormDialog.vue';
import RepositoryGovernanceDialog from'@/features/repositories/RepositoryGovernanceDialog.vue';
import RepositoryTable from'@/features/repositories/RepositoryTable.vue';
import{sourceImportsApi}from'@/api/sourceImports';
import{intelligenceApi}from'@/api/intelligence';
import{useRepositoryStore}from'@/stores/repositoryStore';
import type{Repository}from'@/types/api';
const store=useRepositoryStore(),query=shallowRef(''),dialogOpen=shallowRef(false),governanceOpen=shallowRef(false),governedRepository=shallowRef<Repository|null>(null),rescanningId=shallowRef<string|null>(null),buildingId=shallowRef<string|null>(null),importing=shallowRef(false);
const rows=computed(()=>{const q=query.value.trim().toLowerCase();return q?store.repositories.filter(item=>`${item.name} ${item.ownerDisplayName} ${item.path} ${item.sourceType} ${item.branch??''} ${item.commit??''}`.toLowerCase().includes(q)):store.repositories});
type Input={sourceType:'LOCAL_GIT'|'REMOTE_GIT'|'GITLAB'|'ZIP';name:string;path:string;url:string;branch:string;file:File|null};
async function create(input:Input){importing.value=true;try{if(input.sourceType==='LOCAL_GIT')await store.createRepository({name:input.name,path:input.path});else if(input.sourceType==='ZIP'){if(!input.file)throw new Error('请选择 ZIP 文件');await sourceImportsApi.zip(input.name,input.file);await store.loadRepositories()}else{await sourceImportsApi.remote({name:input.name,url:input.url,branch:input.branch,sourceType:input.sourceType});await store.loadRepositories()}dialogOpen.value=false;ElMessage.success('仓库快照已验证并发布')}catch(error){ElMessage.error(error instanceof Error?error.message:'导入失败')}finally{importing.value=false}}
async function rescan(id:string){rescanningId.value=id;try{const result=await store.rescanRepository(id);ElMessage.success(result.changed?'检测到代码变化，已发布新快照':'代码版本无变化')}finally{rescanningId.value=null}}
async function startIndex(id:string){await store.createIndexJob(id,'FULL');ElMessage.success('全量内容索引任务已进入队列')}
async function buildCodeGraph(repository:Repository){buildingId.value=repository.id;try{await intelligenceApi.buildGraph(repository.id);await store.loadRepositories();ElMessage.success('CodeGraph 产物已发布')}finally{buildingId.value=null}}
function govern(repository:Repository){governedRepository.value=repository;governanceOpen.value=true}
async function governanceChanged(){await store.loadRepositories();governedRepository.value=store.repositories.find(item=>item.id===governedRepository.value?.id)??null}
async function remove(id:string,name:string){await ElMessageBox.confirm(`删除平台中的“${name}”及其派生数据；本地原目录不会被修改。`,'删除仓库',{type:'warning'});await store.removeRepository(id)}
onMounted(()=>void store.loadRepositories());
</script>
<template><section class="page repository-design"><div class="summary-strip"><div><span>授权仓库</span><b>{{store.repositories.length}}</b></div><div><span>本人所有</span><b>{{store.repositories.filter(item=>item.relationship==='OWNER').length}}</b></div><div><span>CodeGraph 已发布</span><b>{{store.repositories.filter(item=>item.codeGraphDetected).length}}</b></div><div><span>待构建</span><b>{{store.repositories.filter(item=>!item.codeGraphDetected).length}}</b></div></div><div class="surface"><div class="toolbar"><el-input v-model="query" :prefix-icon="Search" placeholder="搜索名称、所有者、来源、路径或版本" clearable/><span class="spacer"/><el-button type="primary" :icon="Plus" :loading="importing" @click="dialogOpen=true">接入仓库</el-button></div><el-alert v-if="store.error" :title="store.error" type="error" :closable="false"/><RepositoryTable :rows="rows" :loading="store.loading" :rescanning-id="rescanningId" :building-id="buildingId" @index="startIndex" @rescan="rescan" @codegraph="buildCodeGraph" @govern="govern" @remove="remove"/></div><RepositoryFormDialog v-model="dialogOpen" @submit="create"/><RepositoryGovernanceDialog v-model="governanceOpen" :repository="governedRepository" @changed="governanceChanged"/></section></template>