<script setup lang="ts">
import { computed, onDeactivated, shallowRef } from 'vue';
import { RouterLink } from 'vue-router';
import { useAuthStore } from '@/stores/authStore';
import { useRepositoryStore } from '@/stores/repositoryStore';
import AccountAccessTokens from '@/features/accounts/AccountAccessTokens.vue';
import McpConfigPanel from './McpConfigPanel.vue';

const auth = useAuthStore();
const repositories = useRepositoryStore();
const showTokens = shallowRef(false);

onDeactivated(() => { showTokens.value = false; });

const searchExample = computed(() => JSON.stringify({
  repositoryId: repositories.selectedRepositoryId || '<仓库 UUID>',
  query: '订单超时在哪里处理，有哪些相关约束？',
  limit: 20,
}, null, 2));

const scopesExample = JSON.stringify({ page: 1, pageSize: 20 }, null, 2);
const graphExample = computed(() => JSON.stringify({
  repositoryId: repositories.selectedRepositoryId || '<仓库 UUID>',
  branchId: '<从 list_codegraph_scopes 选择的分支 UUID>',
  query: '订单超时由哪些函数处理？',
}, null, 2));

const errors = [
  ['401 / ACCESS_TOKEN_INVALID', '令牌无效、过期或已撤销。登录平台后创建新令牌。'],
  ['403 / 无仓库权限', '访问令牌沿用绑定账户的实时仓库权限，请先分配 READ 权限。'],
  ['连接失败', '确认 Java 后端已启动，地址以 /api/mcp 结尾，客户端携带 Bearer 令牌。'],
  ['CODEGRAPH_ARTIFACT_NOT_AVAILABLE', '所选分支还没有已发布图谱。到项目管理准备该分支并构建 CodeGraph，确认 codegraphReady 为 true。'],
  ['CONTEXT_EXPIRED / CONTEXT_MISMATCH', 'contextId 已过期或与分支不匹配；重新选择分支并获取新的上下文。'],
  ['结果为空', '确认项目和分支正确、版本已准备，再用类名、函数名或业务术语查询。'],
];
</script>

<template>
  <article class="mcp-guide">
    <header class="guide-header">
      <span class="eyebrow">只读代码、知识与图谱 · HTTP</span>
      <h1>MCP 接入</h1>
      <p>让 AI 编程工具在同一服务中检索代码与知识，并按项目、分支查询 CodeGraph。结果保留版本与来源信息。</p>
      <div class="flow">AI 客户端 → 账户权限校验 → 选择项目和分支 → 固定版本 → 代码、知识与图谱结果</div>
    </header>

    <section class="guide-section">
      <h2>1. 准备仓库</h2>
      <p>先在 <RouterLink to="/repositories">项目管理</RouterLink> 接入仓库。联合检索需要已准备的代码版本；图谱查询还需要将目标 Git 分支准备好并发布 CodeGraph 产物。可在 <RouterLink to="/overview">项目总览</RouterLink> 查看准备状态。调用账户至少需要目标仓库的 READ 权限。</p>
    </section>

    <section class="guide-section">
      <h2>2. 创建访问令牌</h2>
      <p>令牌只显示一次，请保存到客户端本地配置，不要提交到代码仓库。</p>
      <el-button type="primary" @click="showTokens = true">管理我的访问令牌</el-button>
      <el-dialog v-model="showTokens" title="我的访问令牌" width="min(960px, 95vw)" destroy-on-close>
        <AccountAccessTokens v-if="showTokens && auth.account" :account-id="auth.account.id" />
      </el-dialog>
    </section>

    <McpConfigPanel />

    <section class="guide-section">
      <h2>4. 搜索代码与知识</h2>
      <p><code>search_project</code> 接受项目、检索词和可选的结果数量。只传 <code>repositoryId</code> 时读取项目默认版本；传入 <code>branchId</code> 或 <code>contextId</code> 时读取固定分支的代码和适用知识。</p>
      <pre tabindex="0" aria-label="联合检索参数"><code>{{ searchExample }}</code></pre>
      <p v-if="!repositories.selectedRepositoryId" class="note">请先在顶部选择可访问的项目，示例中的 repositoryId 需要替换为真实 UUID。</p>
      <div class="tool-row"><code>search_project</code><span>repositoryId、query；可选 limit（1–50）、branchId 或 contextId</span></div>
    </section>

    <section class="guide-section">
      <h2>5. 选择分支并查询图谱</h2>
      <p>先调用 <code>list_codegraph_scopes</code>，从当前账户可访问的项目中选择 <code>codegraphReady: true</code> 的分支。首次图谱查询传 <code>repositoryId + branchId</code>，返回的 <code>context.contextId</code> 可用于后续调用，保持同一版本。</p>
      <pre tabindex="0" aria-label="项目与分支发现参数"><code>{{ scopesExample }}</code></pre>
      <p class="example-label">选定分支后调用 <code>codegraph_explore</code>：</p>
      <pre tabindex="0" aria-label="代码图谱探索参数"><code>{{ graphExample }}</code></pre>
      <p class="note">后续将 <code>branchId</code> 换成返回的 <code>contextId</code>。上下文有效期一小时；过期后重新选择分支。未准备或未发布图谱的分支不会自动回退到其他分支。</p>
      <div class="tool-row"><code>codegraph_explore · codegraph_node · codegraph_search</code><span>源码、符号和调用路径</span></div>
      <div class="tool-row"><code>codegraph_callers · codegraph_callees · codegraph_impact</code><span>调用关系与影响分析</span></div>
      <div class="tool-row"><code>codegraph_files · codegraph_status · codegraph_affected</code><span>文件、状态和受影响测试</span></div>
      <p class="note">这些工具只读受管分支，不同步代码、不构建图谱，也不修改项目。ZIP 项目通过固定的 WORKSPACE 分支使用相同能力。</p>
    </section>

    <section class="guide-section">
      <h2>遇到问题</h2>
      <dl class="troubleshooting"><div v-for="[title, solution] in errors" :key="title"><dt>{{ title }}</dt><dd>{{ solution }}</dd></div></dl>
    </section>
  </article>
</template>

<style scoped>
.mcp-guide { max-width: 920px; margin: 0 auto; padding: 12px 0 32px; display: grid; gap: 20px; color: #243247; }
.guide-header { padding: 12px 4px; }
.eyebrow { color: #64748b; font-size: 12px; letter-spacing: .08em; }
h1 { margin: 10px 0; font-size: 28px; }
h2 { margin: 0 0 12px; font-size: 18px; }
p { color: #526071; line-height: 1.85; }
a { color: #2563eb; }
.flow { margin-top: 18px; padding: 14px 18px; border-left: 3px solid #b7791f; background: #fffbeb; font-size: 13px; line-height: 1.8; }
.guide-section { padding: 24px; border: 1px solid #e4e7ed; border-radius: 12px; background: #fff; min-width: 0; }
pre { padding: 16px; overflow-x: auto; border-radius: 8px; background: #f1f5f9; font-size: 12px; line-height: 1.8; }
.tool-row { display: flex; justify-content: space-between; gap: 20px; padding: 14px 0; border-top: 1px solid #e4e7ed; border-bottom: 1px solid #e4e7ed; color: #526071; font-size: 13px; }
.note { font-size: 13px; color: #64748b; }
.tool-row code { overflow-wrap: anywhere; }
.example-label { margin-bottom: 8px; }
.troubleshooting { margin-bottom: 0; }
.troubleshooting > div + div { margin-top: 18px; }
dt { font-size: 14px; font-weight: 600; }
dd { margin: 6px 0 0; color: #64748b; font-size: 13px; line-height: 1.8; }
@media (max-width: 700px) { .guide-section { padding: 18px; } h1 { font-size: 24px; } .tool-row { flex-direction: column; gap: 6px; } }
</style>
