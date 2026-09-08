<script setup lang="ts">
import { computed, shallowRef } from 'vue';
import { ElMessage } from 'element-plus';
const apiBase = shallowRef((import.meta.env.VITE_API_BASE_URL || window.location.origin).replace(/\/$/, ''));
const config = computed(() => JSON.stringify({
  mcpServers: {
    'analyzer-coder': {
      url: `${apiBase.value.trim().replace(/\/$/, '')}/api/mcp`,
      headers: { Authorization: 'Bearer <填写账户访问令牌>' },
    },
  },
}, null, 2));
async function copyConfig() {
  try { await navigator.clipboard.writeText(config.value); ElMessage.success('配置模板已复制，请在客户端本地填写访问令牌'); }
  catch { ElMessage.warning('剪贴板不可用，请选中下方配置手动复制'); }
}
</script>
<template>
  <section class="config-panel" aria-labelledby="mcp-config-title">
    <h2 id="mcp-config-title">3. 配置 MCP 客户端</h2>
    <p>选择远程 Streamable HTTP 接入。所有账户使用同一个服务地址，每次请求通过自己的访问令牌认证。</p>
    <div class="config-fields"><label for="mcp-api-base">平台服务地址<input id="mcp-api-base" v-model="apiBase" spellcheck="false" placeholder="https://your-platform.example.com" /></label></div>
    <p class="hint">填写客户端可访问的平台地址，不带 /api/mcp。正式部署使用 HTTPS。下方为支持 url 和 headers 的客户端配置示例，具体字段以客户端为准；如提供表单，填写服务 URL 和 Authorization 请求头。</p>
    <div class="code-toolbar"><span>JSON · 令牌需在客户端本地填写</span><el-button size="small" @click="copyConfig">复制配置模板</el-button></div>
    <pre class="config-code" tabindex="0" aria-label="MCP JSON 配置模板"><code>{{ config }}</code></pre>
    <p class="hint">将 Authorization 的值替换为 Bearer 加一个空格再加令牌。此服务使用手动访问令牌，不提供 OAuth 登录跳转；客户端需支持自定义请求头。无需安装 Node.js 或单独启动 MCP 服务。</p>
  </section>
</template>
<style scoped>
.config-panel { border: 1px solid #e4e7ed; border-radius: 12px; padding: 24px; background: #fff; }
h2 { margin: 0 0 12px; font-size: 18px; }
p { line-height: 1.8; color: #526071; }
.config-fields { display: grid; gap: 16px; margin: 20px 0 10px; }
.config-fields label { display: grid; gap: 8px; font-size: 13px; font-weight: 600; color: #334155; }
.config-fields input { width: 100%; min-width: 0; box-sizing: border-box; padding: 10px 12px; border: 1px solid #cbd5e1; border-radius: 6px; background: #fff; color: #1e293b; font: inherit; }
.config-fields input:focus { outline: 2px solid #93c5fd; outline-offset: 2px; }
.hint { font-size: 13px; }
.code-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 14px; border: 1px solid #e4e7ed; border-radius: 8px 8px 0 0; color: #64748b; font-size: 12px; }
.config-code { margin: 0; padding: 18px; overflow-x: auto; border-radius: 0 0 8px 8px; background: #162235; color: #e2e8f0; font-size: 12px; line-height: 1.75; }
</style>
