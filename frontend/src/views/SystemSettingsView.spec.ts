import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import SystemSettingsView from './SystemSettingsView.vue';
import { llmSettingsApi } from '@/api/llmSettings';
vi.mock('@/api/llmSettings', () => ({ llmSettingsApi: {
  providers: vi.fn(), vectorModels: vi.fn(), createProvider: vi.fn(), createVectorModel: vi.fn(),
} }));
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } }));
const slot = { template: '<div><slot /></div>' };
function mountView() {
  return shallowMount(SystemSettingsView, { global: {
    directives: { loading: () => {} },
    stubs: {
      ElButton: { template: '<button><slot /></button>' },
      ElDialog: { props: ['modelValue'], template: '<section v-if="modelValue" role="dialog"><slot /><slot name="footer" /></section>' },
      ElInput: { props: ['modelValue', 'placeholder'], emits: ['update:modelValue'], template: '<input :placeholder="placeholder" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
      ElSelect: { props: ['modelValue'], emits: ['update:modelValue'], template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>' },
      ElOption: { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' },
      ElAlert: { props: ['title', 'type'], template: '<p :data-alert="type">{{ title }}</p>' },
      ElForm: slot, ElFormItem: slot, ElTag: slot, ElInputNumber: true, ElSwitch: true,
    },
  } });
}
describe('model configuration failure recovery', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(llmSettingsApi.providers).mockResolvedValue([]);
    vi.mocked(llmSettingsApi.vectorModels).mockResolvedValue([]);
  });
  it('retains provider fields and a newly entered key on failure, and clears the old error when reopened', async () => {
    vi.mocked(llmSettingsApi.createProvider).mockRejectedValueOnce(new Error('暂时不可用')).mockResolvedValueOnce({} as never);
    const wrapper = mountView(); await flushPromises();
    const click = async (text: string) => { await wrapper.findAll('button').find(button => button.text() === text)!.trigger('click'); await flushPromises(); };
    await click('新增模型');
    await wrapper.get('input[placeholder="例如：生产问答模型"]').setValue('验收模型');
    await wrapper.get('input[placeholder="https://llm.example.com/v1"]').setValue('https://example.invalid/v1');
    await wrapper.get('input[placeholder="model-name"]').setValue('qa-model');
    await wrapper.get('input[placeholder="可选"]').setValue('test-only-key');
    await click('保存备案');
    expect(wrapper.get('[data-alert="error"]').text()).toBe('暂时不可用');
    expect((wrapper.get('input[placeholder="可选"]').element as HTMLInputElement).value).toBe('test-only-key');
    await click('保存备案');
    expect(llmSettingsApi.createProvider).toHaveBeenLastCalledWith(expect.objectContaining({ name: '验收模型', model: 'qa-model', apiKey: 'test-only-key', secretAction: 'REPLACE' }));
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false);
    await click('新增模型');
    expect(wrapper.find('[data-alert="error"]').exists()).toBe(false);
    expect((wrapper.get('input[placeholder="可选"]').element as HTMLInputElement).value).toBe('');
    wrapper.unmount();
  });
  it('retains the vector key for retry and clears errors for a fresh form', async () => {
    vi.mocked(llmSettingsApi.createVectorModel).mockRejectedValueOnce(new Error('向量保存失败')).mockResolvedValueOnce({} as never);
    const wrapper = mountView(); await flushPromises();
    const click = async (text: string) => { await wrapper.findAll('button').find(button => button.text().startsWith(text))!.trigger('click'); await flushPromises(); };
    await click('向量模型'); await click('新增向量模型');
    await wrapper.get('input[placeholder="例如：默认代码向量"]').setValue('验收向量');
    await wrapper.get('input[placeholder="local-hash-64"]').setValue('qa-vector');
    await wrapper.get('select').setValue('OPENAI_COMPATIBLE');
    await wrapper.get('input[placeholder="https://api.example.com/v1"]').setValue('https://example.invalid/v1');
    await wrapper.get('input[placeholder="请输入接口密钥"]').setValue('test-only-vector-key');
    await click('保存备案');
    expect(wrapper.get('[data-alert="error"]').text()).toBe('向量保存失败');
    await click('保存备案');
    expect(llmSettingsApi.createVectorModel).toHaveBeenLastCalledWith(expect.objectContaining({ apiKey: 'test-only-vector-key', secretAction: 'REPLACE' }));
    await click('新增向量模型');
    expect(wrapper.find('[data-alert="error"]').exists()).toBe(false);
    wrapper.unmount();
  });
});
