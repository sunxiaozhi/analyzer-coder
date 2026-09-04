import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import TaskReviewForm from './TaskReviewForm.vue';

describe('TaskReviewForm', () => {
  it('defaults to a worktree review and explains version stability', async () => {
    const wrapper = mount(TaskReviewForm, { props: { loading: false } });

    expect(wrapper.text()).toContain('暂存、未暂存和未跟踪文件');
    expect(wrapper.text()).toContain('分析期间不要修改文件');

    await wrapper.get('textarea').setValue('核对退款改动');
    await wrapper.get('textarea').trigger('keydown', { key: 'Enter', ctrlKey: true });

    expect(wrapper.emitted('submit')).toEqual([[
      {
        task: '核对退款改动',
        changeSource: 'WORKTREE',
        baseRef: 'HEAD',
        headRef: null,
        modelConfigId: null,
      },
    ]]);
  });

  it('collects both refs for a commit range', async () => {
    const wrapper = mount(TaskReviewForm, { props: { loading: false } });
    await wrapper.findAll('.source-field button')[2].trigger('click');

    const inputs = wrapper.findAll('.ref-fields input');
    expect(inputs).toHaveLength(2);
    expect((inputs[0].element as HTMLInputElement).value).toBe('HEAD~1');
    expect((inputs[1].element as HTMLInputElement).value).toBe('HEAD');

    await inputs[0].setValue('main');
    await inputs[1].setValue('feature/review');
    await wrapper.get('.review-form > footer el-button').trigger('click');

    expect(wrapper.emitted('submit')?.[0]?.[0]).toMatchObject({
      changeSource: 'COMMIT_RANGE',
      baseRef: 'main',
      headRef: 'feature/review',
    });
  });

  it('submits an optional available model only for cited summary', async () => {
    const wrapper = mount(TaskReviewForm, {
      props: {
        loading: false,
        models: [{
          id: 'model-1', name: '审查总结', providerType: 'OPENAI_COMPATIBLE', model: 'reviewer',
          availability: 'AVAILABLE', breakerState: 'CLOSED', available: true,
        }],
      },
    });

    await wrapper.get('.model-field select').setValue('model-1');
    await wrapper.get('.review-form > footer el-button').trigger('click');

    expect(wrapper.emitted('submit')?.[0]?.[0]).toMatchObject({ modelConfigId: 'model-1' });
    expect(wrapper.text()).toContain('每条建议必须引用现有证据标识');
  });
});
