
import { reactive } from 'vue';
import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ImpactEstimatePanel from './ImpactEstimatePanel.vue';
const api = vi.hoisted(() => ({ analyze: vi.fn(), models: vi.fn() }));
let store: { selectedRepositoryId: string; selectedRepository: { snapshotId: string } };
vi.mock('vue-router', () => ({ useRoute: () => ({ query: { task: '检查登录流程' } }), useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/stores/repositoryStore', () => ({ useRepositoryStore: () => store }));
vi.mock('@/api/changeAnalysis', () => ({ createChangeAnalysis: api.analyze }));
vi.mock('@/api/intelligence', () => ({ intelligenceApi: { askModels: api.models } }));
describe('impact estimate repository isolation', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    store = reactive({ selectedRepositoryId: 'a', selectedRepository: { snapshotId: 's1' } });
    api.models.mockResolvedValue([]);
  });
  it('ignores a slow analysis after switching away and back', async () => {
    let resolve!: (value: object) => void;
    api.analyze.mockReturnValue(new Promise(done => { resolve = done; }));
    const wrapper = shallowMount(ImpactEstimatePanel, { global: { stubs: {
      ElButton: { template: '<button @click="$emit(\'click\')"><slot /></button>' },
      ElInput: true, ElSelect: true, ElOption: true,
    } } });
    await flushPromises();
    await wrapper.find('.input-actions button').trigger('click');
    expect(api.analyze).toHaveBeenCalled();
    store.selectedRepositoryId = 'b';
    await flushPromises();
    store.selectedRepositoryId = 'a';
    await flushPromises();
    resolve({ intent: { parserMode: 'RULES' } });
    await flushPromises();
    expect(wrapper.find('.analysis-result').exists()).toBe(false);
    wrapper.unmount();
  });
});
