import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import BranchListTable from './BranchListTable.vue';

it('shows separate branch commits and lets users select an unprepared branch', async () => {
  const wrapper = mount(BranchListTable, { props: { disabled: false, selectedId: 'main', branches: [
    { id: 'main', name: 'main', snapshotId: 's1', commitSha: 'abcdef123456789', status: 'READY', error: null, generation: 1, trackingStatus: 'ACTIVE', archivedAt: null },
    { id: 'release', name: 'release', snapshotId: null, commitSha: null, status: 'PENDING', error: null, generation: 0, trackingStatus: 'ACTIVE', archivedAt: null },
  ] } });
  expect(wrapper.text()).toContain('abcdef123456');
  expect(wrapper.text()).toContain('未准备');
  await wrapper.findAll('.branch-select')[1].trigger('click');
  expect(wrapper.emitted('select')).toEqual([['release']]);
  await wrapper.setProps({ disabled: true });
  expect(wrapper.findAll('button').every(button => button.attributes('disabled') !== undefined)).toBe(true);
});


it('hides archived branches by default and keeps recovery accessible', async () => {
  const wrapper = mount(BranchListTable, { props: { disabled: false, selectedId: 'main', canManage: true, readingBranchId: 'main', branches: [
    { id: 'main', name: 'main', snapshotId: 's1', commitSha: 'abcdef', status: 'READY', error: null, generation: 1, trackingStatus: 'ACTIVE', archivedAt: null },
    { id: 'old', name: 'old-release', snapshotId: 's0', commitSha: '012345', status: 'READY', error: null, generation: 1, trackingStatus: 'ARCHIVED', archivedAt: '' },
  ] } });
  expect(wrapper.text()).toContain('阅读中');
  expect(wrapper.text()).not.toContain('old-release');
  await wrapper.get('input[type="checkbox"]').setValue(true);
  expect(wrapper.text()).toContain('old-release');
  await wrapper.findAll('.branch-lifecycle')[1].trigger('click');
  expect(wrapper.emitted('restore')).toEqual([['old']]);
  wrapper.unmount();
});
