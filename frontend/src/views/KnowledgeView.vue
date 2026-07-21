<script setup lang="ts">
import { Plus,Search } from '@element-plus/icons-vue';
import { computed,onMounted,reactive,shallowRef,watch } from 'vue';
import { ElMessage,ElMessageBox } from 'element-plus';
import { intelligenceApi,type CardInput,type CardRevision,type KnowledgeCard } from '@/api/intelligence';
import { useRepositoryStore } from '@/stores/repositoryStore';

const repositories=useRepositoryStore();
const cards=shallowRef<KnowledgeCard[]>([]),query=shallowRef(''),dialog=shallowRef(false),historyDialog=shallowRef(false),busy=shallowRef(false);
const editingId=shallowRef<string|null>(null),historyCard=shallowRef<KnowledgeCard|null>(null),revisions=shallowRef<CardRevision[]>([]);
const form=reactive<CardInput>({title:'',cardType:'业务规则',content:'',tags:[],status:'DRAFT'}),tagText=shallowRef('');
const rows=computed(()=>cards.value.filter(x=>!query.value||`${x.title} ${x.content} ${x.tags.join(' ')}`.toLowerCase().includes(query.value.toLowerCase())));

async function load(){if(repositories.selectedRepositoryId)cards.value=await intelligenceApi.cards(repositories.selectedRepositoryId);else cards.value=[];}
function openCreate(){editingId.value=null;Object.assign(form,{title:'',cardType:'业务规则',content:'',tags:[],status:'DRAFT'});tagText.value='';dialog.value=true;}
function openEdit(card:KnowledgeCard){editingId.value=card.id;Object.assign(form,{title:card.title,cardType:card.cardType,content:card.content,tags:[...card.tags],status:card.status});tagText.value=card.tags.join(', ');dialog.value=true;}
async function save(){const repo=repositories.selectedRepositoryId;if(!repo)return;if(!form.title.trim()||!form.content.trim())return ElMessage.warning('请输入标题和正文');busy.value=true;try{form.tags=tagText.value.split(',').map(x=>x.trim()).filter(Boolean);if(editingId.value)await intelligenceApi.updateCard(repo,editingId.value,{...form});else await intelligenceApi.createCard(repo,{...form});dialog.value=false;await load();ElMessage.success(editingId.value?'新修订已保存':'知识卡片已创建');}catch(e){ElMessage.error(e instanceof Error?e.message:'保存失败');}finally{busy.value=false;}}
async function showHistory(card:KnowledgeCard){const repo=repositories.selectedRepositoryId;if(!repo)return;historyCard.value=card;revisions.value=await intelligenceApi.cardHistory(repo,card.id);historyDialog.value=true;}
async function restore(revision:number){const repo=repositories.selectedRepositoryId,card=historyCard.value;if(!repo||!card)return;await ElMessageBox.confirm(`把 v${revision} 恢复为新的草稿修订？当前历史不会被覆盖。`,'恢复历史修订',{type:'warning'});await intelligenceApi.restoreCardRevision(repo,card.id,revision);revisions.value=await intelligenceApi.cardHistory(repo,card.id);await load();ElMessage.success('历史内容已恢复为新草稿');}
watch(()=>repositories.selectedRepositoryId,()=>void load());onMounted(()=>void load());
</script>
<template>
  <section class="page"><div class="summary-strip"><div><b>{{cards.length}}</b><span>知识卡片</span></div><div><b>{{cards.filter(x=>x.status==='PUBLISHED').length}}</b><span>已发布</span></div><div><b>{{cards.filter(x=>x.status==='DRAFT').length}}</b><span>草稿</span></div><div><b>{{cards.reduce((n,x)=>n+x.revision,0)}}</b><span>修订总数</span></div></div>
    <div class="surface"><div class="toolbar"><el-input v-model="query" :prefix-icon="Search" placeholder="搜索标题、正文或标签"/><span class="spacer"/><el-button type="primary" :icon="Plus" @click="openCreate">新建卡片</el-button></div><el-empty v-if="!rows.length" description="当前仓库暂无知识卡片"/>
      <div class="knowledge-grid"><article v-for="card in rows" :key="card.id" class="knowledge-card"><header><el-tag effect="plain">{{card.cardType}}</el-tag><el-tag :type="card.status==='PUBLISHED'?'success':card.status==='NEEDS_REVIEW'?'warning':'info'">{{card.status}}</el-tag></header><h3>{{card.title}}</h3><p>{{card.content}}</p><div class="tags"><span v-for="tag in card.tags" :key="tag"># {{tag}}</span></div><footer><span>修订 v{{card.revision}}</span><time>{{new Date(card.updatedAt).toLocaleString()}}</time></footer><div class="toolbar"><el-button link @click="openEdit(card)">编辑为新修订</el-button><el-button link @click="showHistory(card)">历史</el-button></div></article></div>
    </div>
    <el-dialog v-model="dialog" :title="editingId?'编辑知识卡片':'新建知识卡片'" width="560"><el-form label-position="top"><el-form-item label="标题" required><el-input v-model="form.title"/></el-form-item><el-form-item label="类型"><el-select v-model="form.cardType" style="width:100%"><el-option v-for="x in ['业务规则','技术决策','接口约定','模块说明']" :key="x" :label="x" :value="x"/></el-select></el-form-item><el-form-item label="正文" required><el-input v-model="form.content" type="textarea" :rows="6"/></el-form-item><el-form-item label="标签（逗号分隔）"><el-input v-model="tagText"/></el-form-item><el-form-item label="状态"><el-select v-model="form.status" style="width:100%"><el-option label="草稿" value="DRAFT"/><el-option label="已发布" value="PUBLISHED"/><el-option label="需要复核" value="NEEDS_REVIEW"/><el-option label="已归档" value="ARCHIVED"/></el-select></el-form-item></el-form><template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" :loading="busy" @click="save">{{editingId?'保存新修订':'创建'}}</el-button></template></el-dialog>
    <el-dialog v-model="historyDialog" :title="`${historyCard?.title??''} · 修订历史`" width="720"><el-timeline><el-timeline-item v-for="item in revisions" :key="item.revision" :timestamp="new Date(item.changedAt).toLocaleString()" placement="top"><el-card shadow="never"><template #header><div class="toolbar"><b>v{{item.revision}} · {{item.status}}</b><span class="spacer"/><el-button link type="primary" @click="restore(item.revision)">恢复为新草稿</el-button></div></template><p>{{item.content}}</p><small>{{item.cardType}} · {{item.tags.join(', ')||'无标签'}}</small></el-card></el-timeline-item></el-timeline></el-dialog>
  </section>
</template>
