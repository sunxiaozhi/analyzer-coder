<script setup lang="ts">
import { shallowRef, toRef } from 'vue';
import AccessTokenTable from './AccessTokenTable.vue';
import { useAccessTokens } from './useAccessTokens';
const props = defineProps<{ accountId: string; canCreate?: boolean }>();
const name = shallowRef('MCP 客户端');
const days = shallowRef(90);
const { tokens, rawToken, loading, busy, error, create, revoke, copy, load } = useAccessTokens(toRef(props, 'accountId'));
</script>
<template>
  <section class="account-tokens">
    <p class="token-description">令牌直接使用此账户的当前仓库权限。请在项目管理的仓库治理中分配成员权限，无需为令牌重复授权。管理员账户的令牌继承管理员权限。</p>
    <p class="token-description">停用账户、修改或重置密码、变更角色会撤销旧令牌。待改密或已锁定的账户需先恢复正常状态。</p>
    <form class="token-form" @submit.prevent="create(name, days)">
      <label>令牌名称<input v-model="name" maxlength="80" required :disabled="busy || loading" /></label>
      <label>有效天数<input v-model.number="days" type="number" min="1" max="365" required :disabled="busy || loading" /></label>
      <el-button native-type="submit" type="primary" :loading="busy" :disabled="canCreate === false || loading || !name.trim()">创建令牌</el-button>
    </form>
    <p v-if="error" role="alert" class="token-error">{{ error }} <el-button link @click="load">重新加载</el-button></p>
    <div v-if="rawToken" class="issued-token" role="status">
      <strong>请立即保存，完整令牌仅本次显示。</strong>
      <code tabindex="0">{{ rawToken }}</code>
      <el-button @click="copy">复制令牌</el-button>
      <el-button @click="rawToken = ''">隐藏令牌</el-button>
    </div>
    <AccessTokenTable v-loading="loading" :tokens="tokens" :busy="busy" @revoke="revoke" />
  </section>
</template>
<style scoped>
.account-tokens { min-width: 0; }
.token-description { color: #64748b; font-size: 13px; line-height: 1.8; }
.token-form { display: flex; flex-wrap: wrap; align-items: end; gap: 16px; margin: 20px 0; }
.token-form label { display: grid; gap: 8px; font-size: 13px; }
.token-form input { padding: 8px 10px; border: 1px solid #cbd5e1; border-radius: 6px; font: inherit; }
.token-form input[type='number'] { width: 95px; }
.token-error { color: #b91c1c; }
.issued-token { padding: 16px; margin: 16px 0; background: #eff6ff; border: 1px solid #bfdbfe; border-radius: 8px; }
.issued-token code { display: block; overflow-wrap: anywhere; margin: 12px 0; user-select: all; }
</style>
