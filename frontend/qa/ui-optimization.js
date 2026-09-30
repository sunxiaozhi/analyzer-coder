// Local, in-memory fetch fixture. Browser runners also stub native attachment requests.
import { createApp, h } from 'vue';
import { createPinia } from 'pinia';
import { createRouter, createWebHashHistory, RouterView } from 'vue-router';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import '../src/styles/main.css';
import '../src/styles/design-alignment.css';
import WorkspaceShell from '../src/components/WorkspaceShell.vue';
import KnowledgeView from '../src/views/KnowledgeView.vue';
import ChunksM0View from '../src/views/ChunksM0View.vue';
import AskView from '../src/views/AskView.vue';
import ProjectOverviewView from '../src/views/ProjectOverviewView.vue';
import FeatureGuideView from '../src/views/FeatureGuideView.vue';
import McpGuideView from '../src/views/McpGuideView.vue';
import RepositoriesM0View from '../src/views/RepositoriesM0View.vue';
import UnifiedIndexJobsView from '../src/views/UnifiedIndexJobsView.vue';
import SystemSettingsView from '../src/views/SystemSettingsView.vue';
import AccountsView from '../src/views/AccountsView.vue';
import AuditLogsView from '../src/views/AuditLogsView.vue';
import CodeAtlasView from '../src/views/CodeAtlasView.vue';
import LoginView from '../src/views/LoginView.vue';
import { useAuthStore } from '../src/stores/authStore';
import { renderMarkdown } from '../src/features/knowledge/markdown';

const now = '2026-09-30T00:00:00Z';
const repo = { id:'qa-repo', name:'交易服务 · UI 验收', description:'本地模拟数据，操作仅保留在当前页面。', sourceType:'LOCAL_GIT', branch:'main', commit:'abcd1234567890',
  contentVersion:'version-main', ownerAccountId:'qa-user', ownerDisplayName:'验收账号', relationship:'OWNER', repositoryStatus:'ACTIVE', ownershipVersion:1, version:1,
  capabilities:{canRead:true,canUpdate:true,canIndex:true,canConfigure:true,canGrant:true,canManageCredential:true,canTransferOwnership:true,canDelete:true,canBuildCodeGraph:true} };
const branches = ['main','legacy'].map(id=>({id,name:id,contentVersion:'version-'+id,commitSha:id==='main'?'abcd1234567890':'9876fedc123456',status:'READY',trackingStatus:'ACTIVE',generation:1,error:null}));
const reference = {branchId:'legacy',branchName:'legacy',repositoryId:repo.id,chunkId:'chunk-1',contentVersion:'version-legacy',filePath:'src/payment/RefundService.ts',symbolName:'approveRefund',startLine:4,endLine:8,contentHash:'abc',stale:false};
const base = {repositoryId:repo.id,cardType:'知识卡片',knowledgeKind:'REFERENCE',severity:'INFO',enforcement:'REFERENCE',ownerAccountId:'qa-user',
  scope:{pathPatterns:[],symbols:[],modules:[]},obligations:{requiredTests:[],requiredApproverAccountIds:[],instructions:[],prohibitedPathPatterns:[],knowledgeUpdateRequired:false},
  publicationStatus:'PUBLISHED',revision:1,createdAt:now,updatedAt:now,sourceVersionStatus:'CURRENT',reviewStatus:'APPROVED',attachments:[],codeReferences:[],branchScope:{mode:'ALL_BRANCHES',branchIds:[]}};
let cards = [
  {...base,id:'card-1',title:'退款审批：金额边界与二次确认规则',content:'# 退款审批\n\n退款金额超过 **5000 元**时，需要负责人二次确认。相同请求号必须保持幂等，避免重复退款。\n\n## 关联说明\n这条规则由 main 与 legacy 分支共享，各分支分别确认适用性。',tags:['支付','退款','审批','幂等','审计'],knowledgeKind:'BUSINESS_RULE',enforcement:'ADVISORY',codeReferences:[reference],branchScope:{mode:'SELECTED_BRANCHES',branchIds:['main','legacy']}},
  {...base,id:'card-2',title:'服务调用与项目术语说明',content:'项目共享的参考说明。这里记录交易服务的术语、设计背景与常见排查步骤，后续新增分支也会看到同一份知识。',tags:['项目约定']},
  {...base,id:'card-3',title:'草稿：迁移支付网关之前需要检查的事项与测试边界',content:'- 检查请求幂等键\n- 验证失败回滚\n- 观察退款事件\n![附件](knowledge-attachment://12345678-0000-0000-0000-000000000000)',tags:[],publicationStatus:'DRAFT'},
  ...Array.from({length:9},(_,i)=>({...base,id:'more-'+i,title:'支付知识 '+(i+4),content:'保留长列表中的阅读位置。返回后继续核对当前业务边界与项目知识。',tags:['支付']}))
].map(c=>({...c,renderedContent:renderMarkdown(c.content,repo.id)}));
const sourceCode = 'export class RefundService {\n  // 幂等与金额边界\n  async approveRefund(amount: number) {\n    if (amount > 5000) {\n      return this.requireApproval();\n    }\n    return this.refund(amount);\n  }\n}\n'+'// 更多上下文\n'.repeat(80);
const diagnostics={contentVersion:'version-main',vectorModel:null,retrievalCapability:'CHARACTER_HASH',enabledChannels:['CODE_KEYWORD'],unavailableChannels:[],channelMetrics:[],recalledCount:2,durationMs:12,degraded:false,degradationReasons:[]};
const citation={id:'citation-1',repositoryId:repo.id,sourceType:'CODE',chunkId:'chunk-1',knowledgeCardId:null,contentVersion:'version-main',title:'退款金额边界',filePath:reference.filePath,symbolName:'approveRefund',startLine:4,endLine:8,content:sourceCode.slice(0,220),rank:1,score:1,channels:['CODE_KEYWORD'],sourceScope:null,codeReferences:[]};
let turns = [];
const validationRecords = new Map();
const markdownContent = '# 退款操作说明\n\n先核对金额，再确认审批记录。';
const generatedSources = new Map();
const histories = new Map(cards.map(card=>[card.id,[structuredClone(card)]]));
const uploaded = [];
const qa = window.__uiQa = { calls: [], failNextAsk: false, failNextSearch: false, emptySearch: false, delayMs: 0, failNextModelSave: false };

const pageResult = items => ({items,total:items.length,pageNum:1,pageSize:15});
const accountRows = ['qa','reader'].map((username,i)=>({id:i?'qa-reader':'qa-user',username,displayName:i?'只读测试账号':'模拟验收',role:i?'NORMAL':'SUPER_ADMIN',status:'ENABLED',repositoryPermissionCount:1,lastLoginAt:now,lastLoginIp:'127.0.0.1',createdAt:now,updatedAt:now,version:1}));
const branchJobs = [{id:'job-main',branchId:'main',status:'SUCCEEDED',stage:'READY',kind:'PREPARE',contentVersion:'version-main',error:null},{id:'job-legacy',branchId:'legacy',status:'FAILED',stage:'GRAPH',kind:'GRAPH',contentVersion:'version-legacy',error:'模拟图谱构建失败，请检查任务详情。'}];
let providers = [{id:'provider-qa',version:1,name:'验收问答模型',providerType:'OPENAI_COMPATIBLE',baseUrl:'https://example.invalid/v1',model:'qa-model',connectTimeoutMs:5000,requestTimeoutMs:60000,maxOutputTokens:2048,temperature:0.2,streamingEnabled:true,secretConfigured:false,fingerprint:null,availability:'AVAILABLE',latestCheckId:null,lastSuccessAt:now,lastFailureAt:null,lastErrorCode:null,breakerState:'CLOSED',createdAt:now}];
const vectors = [{id:'vector-qa',name:'本地字符向量',providerType:'LOCAL_HASH',baseUrl:null,model:'local-hash-64',dimension:64,requestTimeoutMs:30000,retrievalCapability:'CHARACTER_HASH',capabilityLabel:'字符相似度',limitations:['不理解同义词或业务语义'],secretConfigured:false,active:true,activationVersion:1,createdAt:now,activatedAt:now}];

function respond(value,status=200) { return new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json'}}); }
window.fetch = async (input, init={}) => {
  const url = new URL(String(input), location.origin), path=url.pathname, body=typeof init.body==='string'?JSON.parse(init.body):{};
  qa.calls.push({path, method:init.method||'GET', body});
  if(qa.delayMs && path.includes('/knowledge')) await new Promise(resolve=>setTimeout(resolve,qa.delayMs));
  const ctx = new Headers(init.headers).get('X-Branch-Context') || url.searchParams.get('contextId') || '';
  const branch=ctx.includes('legacy')?'legacy':'main', version='version-'+branch;
  const visible=cards.filter(c=>c.branchScope.mode==='ALL_BRANCHES'||c.branchScope.branchIds.includes(branch));
  if(path.endsWith('/preferences/current-repository'))return respond({repositoryId:repo.id});
  if(path==='/api/repositories/page')return respond(pageResult([repo]));
  if(path==='/api/repository-project-drafts')return respond([]);
  if(path.endsWith('/branch-index-statuses'))return respond(branches.map(b=>({branchId:b.id,contentVersion:b.contentVersion,syncedAt:now,contentReady:true,graphReady:true,vectorsReady:true})));
  if(path.endsWith('/branch-preparation-jobs'))return respond(branchJobs);
  if(path.endsWith('/branch-preparation-jobs/history'))return respond(pageResult(branchJobs.filter(j=>!url.searchParams.get('branchId')||j.branchId===url.searchParams.get('branchId'))));
  if(path==='/api/index-jobs/page')return respond(pageResult([]));
  if(path==='/api/accounts/page')return respond(pageResult(accountRows));
  if(path==='/api/accounts/audit')return respond(accountRows.map((a,i)=>({id:'audit-'+i,eventType:'LOGIN_SUCCEEDED',result:'SUCCESS',actorUsername:a.username,targetUsername:a.username,repositoryName:repo.name,createdAt:now,sourceIp:'127.0.0.1',requestId:'qa-request-'+i})));
  if(path.startsWith('/api/settings/llm/providers')){
    if(!init.method||init.method==='GET')return respond(providers);
    if(qa.failNextModelSave){qa.failNextModelSave=false;return respond({code:'QA_SAVE_FAILED',message:'模拟模型保存失败'},503);}
    const id=path.split('/providers/')[1]||'provider-created';const provider={...providers[0],...body,id,version:2};delete provider.apiKey;providers=[provider,...providers.filter(p=>p.id!==id)];return respond(provider);
  }
  if(path.startsWith('/api/settings/llm/connectivity-checks'))return respond({id:'check-qa',configId:'provider-qa',status:'SUCCEEDED',availability:'AVAILABLE',stages:[],totalDurationMs:12});
  if(path==='/api/settings/llm/vector-models')return respond(vectors);
  if(path.endsWith('/codegraph/explore'))return respond({repositoryId:repo.id,contentVersion:version,level:'SYMBOL',nodes:[{id:'node-1',label:'approveRefund',kind:'method',filePath:reference.filePath,module:'src/payment',startLine:4,endLine:8,count:1},{id:'node-2',label:'requireApproval',kind:'method',filePath:reference.filePath,module:'src/payment',startLine:5,endLine:6,count:1}],edges:[{id:'edge-1',source:'node-1',target:'node-2',kind:'CALLS',count:1}],totalNodes:2,totalEdges:1,partial:false});
  if(path==='/api/repositories')return respond([repo]);
  if(path.endsWith('/branches'))return respond(branches);
  if(path.endsWith('/contexts'))return respond({contextId:'ctx-'+body.branchId,repositoryId:repo.id,branchId:body.branchId,branchName:body.branchId,contentVersion:'version-'+body.branchId,commitSha:branches.find(b=>b.id===body.branchId).commitSha,expiresAt:'2099-01-01T00:00:00Z'});
  if(path.endsWith('/index-status'))return respond({branchId:branch,contentVersion:version,contentReady:true,graphReady:true,vectorsReady:true});
  if(path.endsWith('/branch-validation')){validationRecords.set(`${body.contextId}:${path.split('/knowledge/')[1].split('/')[0]}`,{state:body.state,note:body.note});return respond(null);}
  if(path.endsWith('/knowledge/attachments') && init.method==='POST'){
    const file=init.body.get('file');const attachment={id:crypto.randomUUID(),originalName:file.name,mediaType:file.type||'text/plain',sizeBytes:file.size,scanStatus:'CLEAN'};uploaded.push(attachment);return respond(attachment);
  }
  if(path.endsWith('/branch-validations'))return respond(visible.map(c=>({cardId:c.id,revision:c.revision,title:c.title,content:c.content,state:c.id==='card-1'?(branch==='main'?'CURRENT':'INVALID'):'UNVERIFIED',note:branch==='legacy'?'旧版需要使用独立金额阈值。':'',...validationRecords.get(`${ctx}:${c.id}`)})));
  if(path.endsWith('/source-drift'))return respond(null);
  if(path.endsWith('/markdown-sources')){
    const generated=generatedSources.get(branch);
    return respond({contentVersion:version,counts:{total:1,pending:generated?0:1,current:generated?1:0,stale:0},items:[{sourceId:'markdown-'+branch,branchId:branch,sourcePath:'docs/refund.md',sourceContentVersion:version,sourceContentHash:'md-hash',title:'退款操作说明',assetType:'MARKDOWN',lineCount:3,byteSize:80,excerpt:'先核对金额，再确认审批记录。',updatedAt:now,status:generated?'CURRENT':'PENDING',cardId:generated?.id||null,cardRevision:generated?.revision||null,cardTitle:generated?.title||null,cardStatus:generated?.publicationStatus||null,generatedContentVersion:generated?version:null,generatedContentHash:generated?'md-hash':null}]});
  }
  if(path.endsWith('/markdown-sources/generate')){
    const card={...base,id:'markdown-card-'+branch,title:'退款操作说明',content:markdownContent,renderedContent:renderMarkdown(markdownContent,repo.id),tags:[],publicationStatus:'DRAFT',sourcePath:'docs/refund.md',branchScope:{mode:'SELECTED_BRANCHES',branchIds:[branch]}};
    cards=[card,...cards.filter(c=>c.id!==card.id)];histories.set(card.id,[structuredClone(card)]);generatedSources.set(branch,card);return respond(card);
  }
  if(path.endsWith('/governance/members'))return respond([{accountId:'qa-user',displayName:'验收账号',username:'qa',enabled:true,relationship:'OWNER'}]);
  if(path.endsWith('/knowledge') && (!init.method || init.method==='GET'))return respond(visible);
  if(path.includes('/knowledge/') || (path.endsWith('/knowledge') && init.method==='POST')) {
    if(body.title?.includes('冲突'))return respond({code:'KNOWLEDGE_REVISION_CONFLICT',message:'模拟修订冲突'},409);
    const id=path.split('/knowledge/')[1]?.split('/')[0], existing=cards.find(c=>c.id===id);
    if(path.endsWith('/history'))return respond((histories.get(id)||[]).map(card=>({...card,changedAt:now})));
    let card;
    if(path.endsWith('/publish'))card={...existing,publicationStatus:'PUBLISHED'};
    else if(path.endsWith('/restore'))card={...histories.get(id).find(card=>card.revision===Number(path.split('/history/')[1].split('/')[0])),revision:existing.revision+1,publicationStatus:'DRAFT'};
    else if(path.endsWith('/publication'))card={...existing,publicationStatus:body.publicationStatus};
    else card={...base,...existing,...body,id:id||'created-'+cards.length,revision:(existing?.revision||0)+1,publicationStatus:'DRAFT',attachments:body.attachmentIds ? [...uploaded,...(existing?.attachments||[])].filter((a,i,all)=>body.attachmentIds.includes(a.id)&&all.findIndex(b=>b.id===a.id)===i) : existing?.attachments||[],codeReferences:body.codeReferences?.map(item=>existing?.codeReferences.find(ref=>ref.chunkId===item.chunkId)||{...reference,...item})||existing?.codeReferences||[]};
    card.renderedContent=renderMarkdown(card.content,repo.id); if(!histories.has(card.id))histories.set(card.id,[]);if(!histories.get(card.id).some(c=>c.revision===card.revision))histories.get(card.id).push(structuredClone(card));cards=[card,...cards.filter(c=>c.id!==card.id)];return respond(card);
  }
  if(path.endsWith('/files'))return respond({contentVersion:version,branch,commit:branches.find(b=>b.id===branch).commitSha,files:[{path:reference.filePath,name:'RefundService.ts',language:'typescript',sizeBytes:1200},{path:'docs/refund.md',name:'refund.md',language:'markdown',sizeBytes:80}]});
  if(path.endsWith('/files/content') && url.searchParams.get('path')==='docs/refund.md')return respond({contentVersion:version,path:'docs/refund.md',name:'refund.md',language:'markdown',sizeBytes:80,lineCount:3,content:markdownContent});
  if(path.endsWith('/files/content'))return respond({contentVersion:version,path:reference.filePath,name:'RefundService.ts',language:'typescript',sizeBytes:1200,lineCount:90,content:sourceCode});
  if(path.endsWith('/evidence-search') && qa.failNextSearch){qa.failNextSearch=false;return respond({code:'QA_SEARCH_FAILED',message:'模拟检索失败'},503);}
  if(path.endsWith('/evidence-search') && qa.emptySearch)return respond({evidence:[],retrieval:{...diagnostics,contentVersion:version}});
  if(path.endsWith('/evidence-search'))return respond({evidence:[{...citation,contentVersion:version,sourceType:'CODE',contentHash:'abc',symbolKind:'方法'}, {...citation,chunkId:null,knowledgeCardId:'card-1',sourceType:'KNOWLEDGE',title:cards[0].title,content:cards[0].content,codeReferences:[reference],sourceScope:'main、legacy 共 2 个分支共享'}],retrieval:{...diagnostics,contentVersion:version}});
  if(path.endsWith('/code-evidence-context'))return respond({repositoryId:repo.id,filePath:reference.filePath,contentVersion:version,commitSha:repo.commit,knowledgeReferences:[],limitations:[],generatedAt:now});
  if(path.endsWith('/ask/models'))return respond([]);
  if(path.endsWith('/ask') && qa.failNextAsk){qa.failNextAsk=false;return respond({code:'QA_ASK_FAILED',message:'模拟请求失败，请重试'},503);}
  if(path.endsWith('/ask')){const turn={conversationId:'answer-'+(turns.length+1),threadId:'thread-1',turnNo:turns.length+1,repositoryId:repo.id,title:body.question,question:body.question,answer:'## 本地证据摘录\n退款逻辑位于 RefundService.ts。\n\n> '+sourceCode.slice(0,140)+'\n\n[S1]',branchId:branch,branchName:branch,contentVersion:version,commitSha:repo.commit,citations:[{...citation,contentVersion:version}],provider:'local',evidenceStatus:'DEGRADED',citationAssessment:null,retrieval:diagnostics,createdAt:now};turns.push(turn);return respond(turn);}
  if(path.endsWith('/qa/records'))return respond(turns.length?[{...turns.at(-1),updatedAt:now,turnCount:turns.length,citationCount:1}]:[]);
  if(path.endsWith('/qa/records/thread-1'))return respond({threadId:'thread-1',repositoryId:repo.id,turns});
  if(path.endsWith('/branch-overview'))return respond({preparation:{branch,contentVersion:version,commitSha:branches.find(b=>b.id===branch).commitSha,state:'READY',message:'当前分支内容与派生索引已就绪',generatedAt:now,stages:[{key:'CONTENT',label:'内容索引',state:'READY',detail:'当前版本可检索'},{key:'GRAPH',label:'代码图谱',state:'READY',detail:'已生成当前版本图谱'}],profile:{graphNodes:24,graphEdges:35,vectorizedChunks:20,chunkCount:20,missingChunks:0,fileCount:5,modules:[],languages:[],entryPoints:[],assets:[],keyAssets:[],retrievalCapabilityLabel:'字符相似度'}},codeFacts:{contentVersion:version,commitSha:branches.find(b=>b.id===branch).commitSha,codeFileCount:5,codeTypes:[],fileCategories:[],projectType:'交易服务',technologies:[],graph:{codeGraphReady:true,symbolNodes:24,symbolEdges:35,modules:0,moduleHotspots:[]},suggestions:[],evidenceNotes:[]},health:{contentVersion:version,commitSha:branches.find(b=>b.id===branch).commitSha,state:'READY',readyForSearch:true,knowledge:{total:12,trusted:1,current:1,suspect:0,stale:0,unverified:11,unreviewed:0,requiredWithoutOwner:0},issues:[]}});
  return respond({code:'QA_UNHANDLED',message:'验收页面尚未配置 '+path},404);
};
const baseline=new URLSearchParams(location.search).has('baseline');
const views=baseline ? await Promise.all(['KnowledgeView','ChunksM0View','AskView'].map(name=>import(/* @vite-ignore */ `./baseline/views/${name}.vue`).then(module=>module.default))) : [KnowledgeView,ChunksM0View,AskView];
if(baseline){for(const name of ['main','design-alignment']) await import(/* @vite-ignore */ `./baseline/styles/${name}.css`);}
const routes=[['knowledge',views[0]],['search',views[1]],['ask',views[2]],['overview',ProjectOverviewView],['help',FeatureGuideView],['mcp',McpGuideView],['repositories',RepositoriesM0View],['indexing',UnifiedIndexJobsView],['settings',SystemSettingsView],['accounts',AccountsView],['audit',AuditLogsView],['atlas',CodeAtlasView]].map(([name,component])=>({path:'/'+name,name,component,meta:{title:{knowledge:'知识管理',search:'联合检索',ask:'项目问答',overview:'项目总览',repositories:'项目管理',indexing:'任务中心',settings:'模型配置',accounts:'账号权限',audit:'审计日志',atlas:'代码图谱',help:'功能导航',mcp:'MCP 接入'}[name]||name}}));
const router=createRouter({history:createWebHashHistory(),routes:[{path:'/',component:WorkspaceShell,children:routes},{path:'/login',component:LoginView}]});
if(!location.hash)location.hash='/knowledge';
const pinia=createPinia();
const app=createApp({render:()=>h(RouterView)});
app.use(pinia).use(router).use(ElementPlus);
const auth=useAuthStore();auth.account={id:'qa-user',username:'qa',displayName:'模拟验收',role:new URLSearchParams(location.search).has('admin')?'SUPER_ADMIN':'NORMAL',mustChangePassword:false,csrfToken:'fixture'};auth.initialized=true;
if(new URLSearchParams(location.search).has('readonly')){repo.capabilities.canUpdate=false;repo.capabilities.canConfigure=false;repo.capabilities.canIndex=false;}
app.mount('#app');
