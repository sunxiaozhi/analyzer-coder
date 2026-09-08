<script setup lang="ts">
import { computed, onDeactivated, shallowRef } from 'vue';
import { useAuthStore } from '@/stores/authStore';
import AccountAccessTokens from '@/features/accounts/AccountAccessTokens.vue';
import { RouterLink } from 'vue-router';
import { useRepositoryStore } from '@/stores/repositoryStore';
import McpConfigPanel from './McpConfigPanel.vue';

const auth = useAuthStore();
const showTokens = shallowRef(false);
onDeactivated(() => { showTokens.value = false; });
const repositories = useRepositoryStore();
const contextExample = computed(() => JSON.stringify({
  repositoryId: repositories.selectedRepositoryId || '<先选择仓库，再复制其 UUID>',
  task: '说明订单结算相关代码，并列出证据来源与未知项',
  includeContent: true,
}, null, 2));
const tools = [
  { name: 'get_task_context', purpose: '获取版本化任务上下文', input: 'repositoryId、task；已有审查时传 taskReviewId' },
  { name: 'review_change', purpose: '审查真实 Git 改动，创建审查记录', input: 'repositoryId、changeSource；按来源填写 baseRef / headRef' },
  { name: 'get_rules_for_symbol', purpose: '读取审查中已命中的符号规则', input: 'repositoryId、reviewId、symbol' },
  { name: 'get_required_tests', purpose: '读取必须执行的测试，不执行测试', input: 'repositoryId、reviewId' },
  { name: 'get_stale_knowledge', purpose: '读取审查隔离的过期或疑似过期知识', input: 'repositoryId、reviewId' },
  { name: 'get_evidence', purpose: '读取来源的完整证据记录', input: 'repositoryId、reviewId、evidenceId；上下文证据另传原 task' },
  { name: 'report_task_outcome', purpose: '追加实际交付结果，不自动修改知识', input: 'repositoryId、reviewId、finalCommit、summary；按实际填写 tests / approvals / feedback' },
];
const errors = [
  ['401 / ACCESS_TOKEN_INVALID', '令牌无效、过期、已撤销或账户已停用。请登录平台查看状态，必要时创建新令牌。'],
  ['403 / PASSWORD_CHANGE_REQUIRED 或 ACCOUNT_LOCKED', '账户需先完成改密或解锁，再创建或使用令牌。'],
  ['403 / 无仓库权限', '令牌沿用绑定账户的当前权限，请管理员或仓库所有者在仓库治理中分配成员权限。'],
  ['403 / MCP_ORIGIN_FORBIDDEN', '检查客户端 Origin 是否与服务地址一致；反向代理需正确配置受信任的转发头。'],
  ['无法连接 / 未识别工具', '确认 Java 后端已启动，地址以 /api/mcp 结尾，客户端支持 Streamable HTTP 与 Bearer 请求头。'],
  ['无证据 / 无法审查 Git 改动', '先准备内容索引。ZIP 可读取上下文，Git 变更审查还需后端可访问的 Git 历史或工作区。'],
];
</script>

<template>
  <article class="mcp-guide">
    <header class="guide-header">
      <span class="eyebrow">开发工具接入 · HTTP</span>
      <h1>MCP 接入</h1>
      <p>让你的 AI 编程工具读取项目上下文、核对改动规则，并回报实际测试结果。</p>
      <div class="flow">AI 客户端 → Java MCP 接口 → 账户与仓库权限校验 → 仓库证据与审查记录</div>
    </header>
    <section class="guide-section">
      <h2>1. 准备账户与项目</h2>
      <p>MCP 随 Java 后端启动。先在平台登录并完成首次改密，确认账户已被分配目标仓库的访问权限。</p>
      <p>在 <RouterLink to="/repositories">项目管理</RouterLink> 的仓库治理中维护成员权限，再到 <RouterLink to="/overview">项目总览</RouterLink> 准备内容索引。</p>
      <p class="note">访问令牌直接继承绑定账户的当前仓库权限。调整成员权限后，下一次工具调用按新权限校验，无需重新分配令牌。</p>
    </section>
    <section class="guide-section">
      <h2>2. 获取账户访问令牌</h2>
      <p>管理员可在“账号权限 → 访问令牌”为账户创建和撤销令牌。你也可以在这里管理自己的令牌。</p>
      <el-button type="primary" @click="showTokens = true">管理我的访问令牌</el-button>
      <p class="note">创建时填写名称和有效期，完整令牌只显示一次。复制后保存到客户端本地配置，不要提交到代码仓库。关闭弹窗后无法再次查看明文，可撤销并重新创建。</p>
      <el-dialog v-model="showTokens" title="我的访问令牌" width="min(960px, 95vw)" destroy-on-close>
        <AccountAccessTokens v-if="showTokens && auth.account" :account-id="auth.account.id" />
      </el-dialog>
    </section>
    <McpConfigPanel />
    <section class="guide-section">
      <h2>4. 验证并开始使用</h2>
      <p>确认客户端识别到下方 7 个工具。先调用 <code>get_task_context</code>，参数示例：</p>
      <pre tabindex="0" aria-label="当前仓库调用参数"><code>{{ contextExample }}</code></pre>
      <p v-if="!repositories.selectedRepositoryId" class="note">尚未选择仓库，请先在顶部选择一个可访问的仓库；repositoryId 必须为真实仓库 UUID。</p>
      <p>返回 snapshotId、上下文条目或明确的未知项，说明工具已能访问后端。空结果需继续检查索引与提问内容，不能视为证据齐全。</p>
      <p><strong>实战顺序：</strong>调用 review_change → 把返回的 reviewId 作为 taskReviewId 传给 get_task_context → 按需读取规则与证据 → 自行执行测试 → report_task_outcome 回报结果。</p>
      <p class="note">changeSource 支持 WORKTREE、SINGLE_COMMIT、COMMIT_RANGE。WORKTREE 读取后端已接入的工作区，不会自动上传客户端电脑上的未提交改动。检索候选不等于已确认规则；需要确定性规则时先创建审查。</p>
    </section>
    <section class="guide-section">
      <h2>7 个工具，各做什么</h2>
      <div class="table-scroll"><table><thead><tr><th>工具</th><th>用途</th><th>主要参数</th></tr></thead><tbody><tr v-for="tool in tools" :key="tool.name"><td><code>{{ tool.name }}</code></td><td>{{ tool.purpose }}</td><td>{{ tool.input }}</td></tr></tbody></table></div>
      <p class="note">默认返回精简结构和来源 ID。需要正文或完整证据时，按工具支持情况使用 includeContent / includeEvidence；完整参数约束以客户端展示的工具 schema 为准。</p>
    </section>
    <section class="guide-section">
      <h2>遇到问题</h2>
      <dl class="troubleshooting"><div v-for="[title, solution] in errors" :key="title"><dt>{{ title }}</dt><dd>{{ solution }}</dd></div></dl>
    </section>
  </article>
</template>

<style scoped>
.mcp-guide { max-width: 1040px; margin: 0 auto; padding: 12px 0 32px; display: grid; gap: 20px; color: #243247; }
.guide-header { padding: 12px 4px; }
.eyebrow { color: #64748b; font-size: 12px; letter-spacing: .08em; }
h1 { margin: 10px 0; font-size: 28px; }
h2 { margin: 0 0 12px; font-size: 18px; }
p, li { color: #526071; line-height: 1.85; }
li + li { margin-top: 10px; }
ol { padding-left: 22px; }
a { color: #2563eb; }
.flow { margin-top: 18px; padding: 14px 18px; border-left: 3px solid #3b82f6; background: #eff6ff; font-size: 13px; line-height: 1.8; }
.guide-section { padding: 24px; border: 1px solid #e4e7ed; border-radius: 12px; background: #fff; min-width: 0; }
pre { padding: 16px; overflow-x: auto; border-radius: 8px; background: #f1f5f9; font-size: 12px; line-height: 1.8; }
.note { font-size: 13px; color: #64748b; }
.table-scroll { overflow-x: auto; }
table { border-collapse: collapse; width: 100%; font-size: 13px; text-align: left; }
th { background: #f8fafc; color: #64748b; white-space: nowrap; }
th, td { padding: 12px; border-bottom: 1px solid #e4e7ed; line-height: 1.7; vertical-align: top; }
.troubleshooting { margin-bottom: 0; }
.troubleshooting > div + div { margin-top: 18px; }
dt { font-size: 14px; font-weight: 600; }
dd { margin: 6px 0 0; color: #64748b; font-size: 13px; line-height: 1.8; }
@media (max-width: 700px) { .guide-section { padding: 18px; } h1 { font-size: 24px; } }
</style>
