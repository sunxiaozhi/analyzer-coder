import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import WorkspaceJourneyBar from './WorkspaceJourneyBar.vue';

describe('WorkspaceJourneyBar', () => {
  it('routes unavailable downstream work back to preparation', async () => {
    const wrapper = mount(WorkspaceJourneyBar, {
      props: {
        activeRoute: 'overview',
        hasRepository: true,
        hasSnapshot: false,
        canManageProjects: true,
        canMaintainKnowledge: true,
      },
    });

    const review = wrapper.findAll('button').find(button => button.text().includes('变更审查'))!;
    expect(review.attributes('title')).toContain('完成证据准备');
    await review.trigger('click');
    expect(wrapper.emitted('navigate')?.[0]).toEqual(['/overview']);
  });

  it('links every available daily step when the snapshot is ready', () => {
    const wrapper = mount(WorkspaceJourneyBar, {
      props: {
        activeRoute: 'search',
        hasRepository: true,
        hasSnapshot: true,
        canManageProjects: true,
        canMaintainKnowledge: true,
      },
    });

    expect(wrapper.text()).toContain('选择项目');
    expect(wrapper.text()).toContain('代码证据');
    expect(wrapper.text()).toContain('问项目');
    expect(wrapper.text()).toContain('变更审查');
    expect(wrapper.text()).toContain('知识回写');
    expect(wrapper.find('button.active').text()).toContain('代码证据');
  });
});
