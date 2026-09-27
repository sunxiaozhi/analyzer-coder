import { shallowMount } from '@vue/test-utils';
import { ElMessage } from 'element-plus';
import { expect, it, vi } from 'vitest';
import RepositoryFormDialog from './RepositoryFormDialog.vue';

vi.mock('element-plus', async importOriginal => ({
  ...await importOriginal<typeof import('element-plus')>(),
  ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
}));

it('shows project details and source together and requires both before starting an import', async () => {
  const wrapper = shallowMount(RepositoryFormDialog, {
    props: { modelValue: true },
    global: { stubs: {
      ElDialog: { template: '<div><slot /><slot name="footer" /></div>' },
      ElForm: { template: '<form><slot /></form>' },
      ElFormItem: { template: '<div><slot /></div>' },
      ElInput: { props: ['modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
      ElButton: { template: '<button type="button"><slot /></button>' },
      ElRadioGroup: { template: '<div><slot /></div>' },
      ElRadioButton: true,
      ElSelect: true,
      ElOption: true,
      ElAlert: true,
      RepositoryCredentialManagerDialog: true,
    } },
  });

  expect(wrapper.text()).toContain('项目资料');
  expect(wrapper.text()).toContain('代码来源');
  expect(wrapper.text()).not.toContain('下一步');
  const start = wrapper.findAll('button').find(button => button.text() === '开始接入');
  expect(start).toBeDefined();
  await start!.trigger('click');
  expect(ElMessage.warning).toHaveBeenCalledWith('请先填写项目名称');
  expect(wrapper.emitted('submit')).toBeUndefined();

  await wrapper.findAll('input')[0]!.setValue('Example');
  await start!.trigger('click');
  expect(ElMessage.warning).toHaveBeenCalledWith('请填写 HTTPS Git 地址');
  expect(wrapper.emitted('submit')).toBeUndefined();

  await wrapper.findAll('input')[2]!.setValue('https://git.example.com/example.git');
  await start!.trigger('click');
  expect(wrapper.emitted('submit')?.[0]?.[0]).toMatchObject({
    name: 'Example',
    sourceType: 'GITLAB',
    url: 'https://git.example.com/example.git',
  });
  wrapper.unmount();
});
