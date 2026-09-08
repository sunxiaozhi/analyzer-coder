import { onScopeDispose, shallowRef, watch, type Ref } from 'vue';
import { ElMessage } from 'element-plus';
import { accessTokensApi, type AccessToken } from '@/api/accessTokens';

export function useAccessTokens(accountId: Ref<string>) {
  const tokens = shallowRef<AccessToken[]>([]);
  const rawToken = shallowRef('');
  const loading = shallowRef(false);
  const busy = shallowRef(false);
  const error = shallowRef('');
  let generation = 0;
  function report(failure: unknown) { error.value = failure instanceof Error ? failure.message : '令牌操作失败'; }
  async function load() {
    const current = ++generation;
    loading.value = true;
    error.value = '';
    try { const rows = await accessTokensApi.list(accountId.value); if (current === generation) tokens.value = rows; }
    catch (failure) { if (current === generation) report(failure); }
    finally { if (current === generation) loading.value = false; }
  }
  async function create(name: string, days: number) {
    const current = generation;
    const target = accountId.value;
    busy.value = true;
    rawToken.value = '';
    error.value = '';
    try {
      const issued = await accessTokensApi.create(target, name, days);
      if (current !== generation) return;
      rawToken.value = issued.rawToken;
      tokens.value = [issued.token, ...tokens.value];
    } catch (failure) { if (current === generation) report(failure); }
    finally { if (current === generation) busy.value = false; }
  }
  async function revoke(tokenId: string) {
    const current = generation;
    busy.value = true;
    error.value = '';
    try {
      await accessTokensApi.revoke(accountId.value, tokenId);
      if (current !== generation) return;
      rawToken.value = '';
      tokens.value = tokens.value.map(token => token.id === tokenId ? { ...token, revokedAt: new Date().toISOString() } : token);
    } catch (failure) { if (current === generation) report(failure); }
    finally { if (current === generation) busy.value = false; }
  }
  async function copy() {
    try { await navigator.clipboard.writeText(rawToken.value); ElMessage.success('令牌已复制'); }
    catch { ElMessage.warning('剪贴板不可用，请选中令牌手动复制'); }
  }
  watch(accountId, () => { tokens.value = []; rawToken.value = ''; busy.value = false; void load(); }, { immediate: true });
  onScopeDispose(() => { generation++; rawToken.value = ''; });
  return { tokens, rawToken, loading, busy, error, create, revoke, copy, load };
}
