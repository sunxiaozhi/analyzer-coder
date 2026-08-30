import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import PullRequestReviewForm from './PullRequestReviewForm.vue';

describe('PullRequestReviewForm', () => {
  it('emits a normalized GitLab merge request without inventing an API host', async () => {
    const wrapper = mount(PullRequestReviewForm, {
      props: { loading: false, defaultProvider: 'GITLAB', models: [] },
      global: { stubs: { 'el-button': { template: '<button class="el-button"><slot /></button>' } } },
    });

    await wrapper.get('input[type="number"]').setValue('19');
    await wrapper.get('textarea').setValue('  核对支付变更  ');
    await wrapper.get('.el-button').trigger('click');

    expect(wrapper.emitted('submit')).toEqual([[
      {
        provider: 'GITLAB',
        number: 19,
        task: '核对支付变更',
        modelConfigId: null,
        apiBaseUrl: null,
      },
    ]]);
    expect(wrapper.text()).toContain('评论只提示、不阻断合并');
  });
});
