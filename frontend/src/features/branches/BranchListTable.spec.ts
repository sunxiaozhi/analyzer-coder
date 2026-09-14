import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import BranchListTable from './BranchListTable.vue';

it('shows separate branch commits and lets users select an unprepared branch', async () => {
  const wrapper = mount(BranchListTable, { props: { disabled: false, selectedId: 'main', branches: [
    { id: 'main', name: 'main', snapshotId: 's1', commitSha: 'abcdef123456789', status: 'READY', error: null, generation: 1 },
    { id: 'release', name: 'release', snapshotId: null, commitSha: null, status: 'PENDING', error: null, generation: 0 },
  ] } });
  expect(wrapper.text()).toContain('abcdef123456');
  expect(wrapper.text()).toContain('未准备');
  await wrapper.findAll('button')[1].trigger('click');
  expect(wrapper.emitted('select')).toEqual([['release']]);
  await wrapper.setProps({ disabled: true });
  expect(wrapper.findAll('button').every(button => button.attributes('disabled') !== undefined)).toBe(true);
});
