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

const errors = [
  ['401 / ACCESS_TOKEN_INVALID', '令牌无效、过期或已撤销。登录平台后创建新令牌。'],
  ['403 / 无仓库权限', '访问令牌沿用绑定账户的实时仓库权限，请先分配 READ 权限。'],
  ['连接失败', '确认 Java 后端已启动，地址以 /api/mcp 结尾，客户端携带 Bearer 令牌。'],
  ['结果为空', '先在项目总览完成仓库索引，再换用代码名称、业务术语或错误信息检索。'],
];
</script>

<template>
  <article class="mcp-guide">
    <header class="guide-header">
      <span class="eyebrow">只读检索接口 · HTTP</span>
      <h1>MCP 接入</h1>
      <p>让 AI 编程工具用一个入口同时搜索代码和项目知识，并保留文件、行号与知识卡来源。</p>
      <div class="flow">AI 客户端 → 权限校验 → 当前仓库快照 → 代码与知识结果</div>
    </header>

    <section class="guide-section">
      <h2>1. 准备仓库</h2>
      <p>先在 <RouterLink to="/repositories">项目管理</RouterLink> 接入仓库，再到 <RouterLink to="/overview">项目总览</RouterLink> 完成索引。调用账户至少需要目标仓库的 READ 权限。</p>
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
      <h2>4. 调用联合检索</h2>
      <p>客户端只会看到一个工具：<code>search_project</code>。它接受仓库、检索词和结果数量，返回混排的代码与知识证据。</p>
      <pre tabindex="0" aria-label="联合检索参数"><code>{{ searchExample }}</code></pre>
      <p v-if="!repositories.selectedRepositoryId" class="note">请先在顶部选择一个可访问的仓库，repositoryId 必须使用真实 UUID。</p>
      <div class="tool-row">
        <code>search_project</code>
        <span>repositoryId、query，limit 可选（1–50）</span>
      </div>
      <p class="note">工具为只读能力，不创建审查记录，也不会修改代码或知识。所有结果绑定当前已发布快照。</p>
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
.troubleshooting { margin-bottom: 0; }
.troubleshooting > div + div { margin-top: 18px; }
dt { font-size: 14px; font-weight: 600; }
dd { margin: 6px 0 0; color: #64748b; font-size: 13px; line-height: 1.8; }
@media (max-width: 700px) { .guide-section { padding: 18px; } h1 { font-size: 24px; } .tool-row { flex-direction: column; gap: 6px; } }
</style>
