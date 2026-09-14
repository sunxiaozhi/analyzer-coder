import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import RemoteBranchDiscoveryPanel from './RemoteBranchDiscoveryPanel.vue';

const ElButton = {
  props: ['disabled'],
  emits: ['click'],
  template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
};

describe('RemoteBranchDiscoveryPanel', () => {
  it('marks tracked branches and emits only explicit discovery and tracking actions', async () => {
    const wrapper = mount(RemoteBranchDiscoveryPanel, {
      props: {
        branches: [
          { name: 'main', commitSha: '1111111111111111111111111111111111111111' },
          { name: 'release/1.0', commitSha: '2222222222222222222222222222222222222222' },
        ],
        trackedNames: ['main'],
        loading: false,
        disabled: false,
      },
      global: { stubs: { ElButton } },
    });

    expect(wrapper.text()).toContain('main');
    expect(wrapper.text()).toContain('111111111111');
    expect(wrapper.text()).toContain('已添加');

    const buttons = wrapper.findAll('button');
    await buttons[0].trigger('click');
    await buttons[2].trigger('click');

    expect(wrapper.emitted('refresh')).toHaveLength(1);
    expect(wrapper.emitted('track')).toEqual([['release/1.0']]);
    expect(buttons[1].attributes('disabled')).toBeDefined();
  });
});
