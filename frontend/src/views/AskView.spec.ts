import { shallowMount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, expect, it, vi } from 'vitest';
import AskView from './AskView.vue';
import AskConversationPanel from '@/features/ask/AskConversationPanel.vue';
import { branchesApi } from '@/api/branches';
import { intelligenceApi, type Answer, type AskModel } from '@/api/intelligence';
import { useBranchContextStore } from '@/stores/branchContextStore';
import { usePageMemoryStore } from '@/stores/pageMemory';
const confirmDialog = vi.hoisted(() => vi.fn<(...args: unknown[]) => Promise<'confirm'>>());
let repository: any;
vi.mock('@/stores/repositoryStore', () => ({ useRepositoryStore: () => repository }));
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/api/branches', () => ({ branchesApi: { contentVersionIndexStatus: vi.fn() } }));
vi.mock('@/api/intelligence', () => ({ intelligenceApi: { history: vi.fn(), askModels: vi.fn(), ask: vi.fn() } }));
vi.mock('element-plus', async importOriginal => ({
  ...await importOriginal<typeof import('element-plus')>(),
  ElMessageBox: { confirm: confirmDialog },
  ElMessage: { warning: vi.fn(), error: vi.fn() },
}));
const model: AskModel = { id: 'model-1', name: '知识问答', model: 'qa-model', availability: 'AVAILABLE', breakerState: 'CLOSED', available: true };
function mountAsk() {
  return shallowMount(AskView, { global: { stubs: { ElTag: { template: '<span><slot /></span>' }, ElButton: true, ElOption: true, ElSelect: true } } });
}
async function sendQuestion(wrapper: ReturnType<typeof mountAsk>) {
  const panel = wrapper.getComponent(AskConversationPanel);
  panel.vm.$emit('update:modelValue', '退款流程是什么？');
  panel.vm.$emit('send');
  await flushPromises();
}
beforeEach(() => {
  vi.resetAllMocks();
  setActivePinia(createPinia());
  repository = { selectedRepositoryId: 'knowledge', selectedRepository: { id: 'knowledge', name: '知识库', sourceType: 'KNOWLEDGE' } };
  repository.repositories = [repository.selectedRepository];
  const branches = useBranchContextStore();
  branches.selectedBranchId = 'workspace';
  branches.context = { contextId: 'context', repositoryId: 'knowledge', branchId: 'workspace', branchName: 'WORKSPACE', contentVersion: null, commitSha: null, expiresAt: '' };
  vi.mocked(intelligenceApi.history).mockResolvedValue([]);
  vi.mocked(intelligenceApi.askModels).mockResolvedValue([]);
  vi.mocked(intelligenceApi.ask).mockResolvedValue({
    repositoryId: 'knowledge', conversationId: 'answer-1', threadId: 'thread-1', turnNo: 1, citations: [],
  } as unknown as Answer);
  confirmDialog.mockResolvedValue('confirm');
});
it('enables knowledge questions without requesting any code index status', async () => {
  const wrapper = shallowMount(AskView, { global: { stubs: { ElTag: { template: '<span><slot /></span>' }, ElButton: true, ElOption: true, ElSelect: true } } });
  await flushPromises();
  expect(branchesApi.contentVersionIndexStatus).not.toHaveBeenCalled();
  expect(wrapper.getComponent(AskConversationPanel).props('disabled')).toBe(false);
  expect(wrapper.getComponent(AskConversationPanel).props('knowledgeOnly')).toBe(true);
  expect(wrapper.text()).toContain('知识问答可用');
  expect(wrapper.text()).toContain('WORKSPACE 虚拟分支');
  expect(wrapper.text()).not.toContain('去准备项目');
  wrapper.unmount();
});

it('waits for recommendation confirmation and uses the available model for this and subsequent questions', async () => {
  vi.mocked(intelligenceApi.askModels).mockResolvedValue([{ ...model, id: 'unavailable', available: false }, model]);
  let confirm!: () => void;
  confirmDialog.mockImplementationOnce(() => new Promise(resolve => { confirm = () => resolve('confirm'); }));
  const wrapper = mountAsk();
  await flushPromises();
  await sendQuestion(wrapper);
  expect(confirmDialog).toHaveBeenCalledWith(expect.stringContaining('知识问答'), '推荐使用问答模型', expect.any(Object));
  expect(intelligenceApi.ask).not.toHaveBeenCalled();
  wrapper.getComponent(AskConversationPanel).vm.$emit('send');
  await flushPromises();
  expect(confirmDialog).toHaveBeenCalledTimes(1);
  confirm();
  await flushPromises();
  expect(intelligenceApi.ask).toHaveBeenCalledWith('knowledge', '退款流程是什么？', expect.any(String), null, model.id, 'context');
  await sendQuestion(wrapper);
  expect(confirmDialog).toHaveBeenCalledTimes(1);
  expect(intelligenceApi.ask).toHaveBeenLastCalledWith('knowledge', '退款流程是什么？', expect.any(String), 'thread-1', model.id, 'context');
  wrapper.unmount();
});

it('sends directly with the already selected model without recommending another model', async () => {
  vi.mocked(intelligenceApi.askModels).mockResolvedValue([model, { ...model, id: 'chosen-model' }]);
  usePageMemoryStore().write('ask:knowledge:workspace', { model: 'chosen-model' });
  const wrapper = mountAsk();
  await flushPromises();
  await sendQuestion(wrapper);
  expect(confirmDialog).not.toHaveBeenCalled();
  expect(intelligenceApi.ask).toHaveBeenCalledWith('knowledge', expect.any(String), expect.any(String), null, 'chosen-model', 'context');
  wrapper.unmount();
});

it('uses local evidence only after an explicit choice when a model is available', async () => {
  vi.mocked(intelligenceApi.askModels).mockResolvedValue([model]);
  confirmDialog.mockRejectedValue('cancel');
  const wrapper = mountAsk();
  await flushPromises();
  await sendQuestion(wrapper);
  expect(confirmDialog).toHaveBeenCalledWith(expect.any(String), expect.any(String), expect.objectContaining({ cancelButtonText: '仅用本地证据', distinguishCancelAndClose: true }));
  expect(intelligenceApi.ask).toHaveBeenCalledWith('knowledge', expect.any(String), expect.any(String), null, null, 'context');
  wrapper.unmount();
});

it('preserves the question without sending when the recommendation is dismissed', async () => {
  vi.mocked(intelligenceApi.askModels).mockResolvedValue([model]);
  confirmDialog.mockRejectedValue('close');
  const wrapper = mountAsk();
  await flushPromises();
  await sendQuestion(wrapper);
  expect(intelligenceApi.ask).not.toHaveBeenCalled();
  expect(wrapper.getComponent(AskConversationPanel).props('modelValue')).toBe('退款流程是什么？');
  expect(wrapper.getComponent(AskConversationPanel).props('disabled')).toBe(false);
  wrapper.unmount();
});

it('keeps local evidence available without a prompt when no models are configured', async () => {
  const wrapper = mountAsk();
  await flushPromises();
  await sendQuestion(wrapper);
  expect(confirmDialog).not.toHaveBeenCalled();
  expect(intelligenceApi.ask).toHaveBeenCalledWith('knowledge', expect.any(String), expect.any(String), null, null, 'context');
  wrapper.unmount();
});

it('does not silently use local evidence while the model list is loading or after loading fails', async () => {
  let reject!: (error: Error) => void;
  vi.mocked(intelligenceApi.askModels).mockImplementationOnce(() => new Promise((_, fail) => { reject = fail; }));
  const wrapper = mountAsk();
  await flushPromises();
  expect(wrapper.getComponent(AskConversationPanel).props('disabled')).toBe(true);
  await sendQuestion(wrapper);
  expect(intelligenceApi.ask).not.toHaveBeenCalled();
  reject(new Error('模型列表加载失败'));
  await flushPromises();
  await sendQuestion(wrapper);
  expect(intelligenceApi.ask).not.toHaveBeenCalled();
  expect(wrapper.text()).toContain('问答模型加载失败');
  wrapper.unmount();
});

it('requires an explicit local evidence choice if all configured models are unavailable', async () => {
  vi.mocked(intelligenceApi.askModels).mockResolvedValue([{ ...model, available: false }]);
  confirmDialog.mockRejectedValueOnce('cancel');
  const wrapper = mountAsk();
  await flushPromises();
  await sendQuestion(wrapper);
  expect(intelligenceApi.ask).not.toHaveBeenCalled();
  expect(confirmDialog).toHaveBeenCalledWith(expect.any(String), '问答模型暂不可用', expect.objectContaining({ confirmButtonText: '仅用本地证据' }));
  await sendQuestion(wrapper);
  expect(intelligenceApi.ask).toHaveBeenCalledWith('knowledge', expect.any(String), expect.any(String), null, null, 'context');
  wrapper.unmount();
});

it('does not send a stale question when the project context changes during recommendation', async () => {
  vi.mocked(intelligenceApi.askModels).mockResolvedValue([model]);
  let confirm!: () => void;
  confirmDialog.mockImplementationOnce(() => new Promise(resolve => { confirm = () => resolve('confirm'); }));
  const wrapper = mountAsk();
  await flushPromises();
  await sendQuestion(wrapper);
  useBranchContextStore().context = null;
  await flushPromises();
  confirm();
  await flushPromises();
  expect(intelligenceApi.ask).not.toHaveBeenCalled();
  wrapper.unmount();
});

it('retries with the confirmed model and the original request id without recommending again', async () => {
  vi.mocked(intelligenceApi.askModels).mockResolvedValue([model]);
  vi.mocked(intelligenceApi.ask).mockRejectedValueOnce(new Error('temporary failure'));
  const wrapper = mountAsk();
  await flushPromises();
  await sendQuestion(wrapper);
  expect(wrapper.getComponent(AskConversationPanel).props('requestState')).toBe('failed');
  wrapper.getComponent(AskConversationPanel).vm.$emit('retry');
  await flushPromises();
  expect(confirmDialog).toHaveBeenCalledTimes(1);
  expect(vi.mocked(intelligenceApi.ask).mock.calls[1]).toEqual(vi.mocked(intelligenceApi.ask).mock.calls[0]);
  expect(wrapper.getComponent(AskConversationPanel).props('requestState')).toBe('succeeded');
  wrapper.unmount();
});
