// Local, in-memory visual fixture. No request from this page reaches a backend.
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
const histories = new Map(cards.map(card=>[card.id,[structuredClone(card)]]));
const uploaded = [];
const qa = window.__uiQa = { calls: [], failNextAsk: false, failNextSearch: false, emptySearch: false, delayMs: 0 };

function respond(value,status=200) { return new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json'}}); }
window.fetch = async (input, init={}) => {
  const url = new URL(String(input), location.origin), path=url.pathname, body=typeof init.body==='string'?JSON.parse(init.body):{};
  qa.calls.push({path, method:init.method||'GET', body});
  if(qa.delayMs && path.includes('/knowledge')) await new Promise(resolve=>setTimeout(resolve,qa.delayMs));
  const ctx = new Headers(init.headers).get('X-Branch-Context') || url.searchParams.get('contextId') || '';
  const branch=ctx.includes('legacy')?'legacy':'main', version='version-'+branch;
  const visible=cards.filter(c=>c.branchScope.mode==='ALL_BRANCHES'||c.branchScope.branchIds.includes(branch));
  if(path.endsWith('/preferences/current-repository'))return respond({repositoryId:repo.id});
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
  if(path.endsWith('/markdown-sources'))return respond({contentVersion:version,counts:{total:0,pending:0,current:0,stale:0},items:[]});
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
  if(path.endsWith('/files'))return respond({contentVersion:version,branch,commit:branches.find(b=>b.id===branch).commitSha,files:[{path:reference.filePath,name:'RefundService.ts',language:'typescript',sizeBytes:1200}]});
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
  if(path.endsWith('/branch-overview'))return respond({preparation:{branch,contentVersion:version,commitSha:branches.find(b=>b.id===branch).commitSha,state:'READY',generatedAt:now,stages:[],profile:{graphNodes:24,graphEdges:35,vectorizedChunks:20,chunkCount:20,missingChunks:0,fileCount:5,modules:[],languages:[],entryPoints:[],assets:[],keyAssets:[],retrievalCapabilityLabel:'字符相似度'}},codeFacts:{contentVersion:version,commitSha:branches.find(b=>b.id===branch).commitSha,codeFileCount:5,codeTypes:[],fileCategories:[],projectType:'交易服务',technologies:[],graph:{codeGraphReady:true,symbolNodes:24,symbolEdges:35,modules:0,moduleHotspots:[]},suggestions:[],evidenceNotes:[]},health:{contentVersion:version,commitSha:branches.find(b=>b.id===branch).commitSha,state:'READY',readyForSearch:true,knowledge:{total:12,trusted:1,current:1,suspect:0,stale:0,unverified:11,unreviewed:0,requiredWithoutOwner:0},issues:[]}});
  return respond({code:'QA_UNHANDLED',message:'验收页面尚未配置 '+path},404);
};
const baseline=new URLSearchParams(location.search).has('baseline');
const views=baseline ? await Promise.all(['KnowledgeView','ChunksM0View','AskView'].map(name=>import(/* @vite-ignore */ `./baseline/views/${name}.vue`).then(module=>module.default))) : [KnowledgeView,ChunksM0View,AskView];
if(baseline){for(const name of ['main','design-alignment']) await import(/* @vite-ignore */ `./baseline/styles/${name}.css`);}
const routes=[['knowledge',views[0]],['search',views[1]],['ask',views[2]],['overview',ProjectOverviewView],['help',FeatureGuideView],['mcp',McpGuideView]].map(([name,component])=>({path:'/'+name,name,component,meta:{title:{knowledge:'知识管理',search:'联合检索',ask:'项目问答',overview:'项目总览'}[name]||name}}));
const router=createRouter({history:createWebHashHistory(),routes:[{path:'/',component:WorkspaceShell,children:routes},{path:'/login',component:LoginView}]});
if(!location.hash)location.hash='/knowledge';
const pinia=createPinia();
const app=createApp({render:()=>h(RouterView)});
app.use(pinia).use(router).use(ElementPlus);
const auth=useAuthStore();auth.account={id:'qa-user',username:'qa',displayName:'模拟验收',role:'NORMAL',mustChangePassword:false,csrfToken:'fixture'};auth.initialized=true;
if(new URLSearchParams(location.search).has('readonly')){repo.capabilities.canUpdate=false;repo.capabilities.canConfigure=false;repo.capabilities.canIndex=false;}
app.mount('#app');
