import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import KnowledgeAccountSelect from './KnowledgeAccountSelect.vue';
import type { RepositoryMember } from '@/api/repositoryGovernance';

const members: RepositoryMember[] = [
  {
    accountId: 'owner-id', username: 'alice', displayName: 'Alice', accountRole: 'NORMAL',
    enabled: true, relationship: 'OWNER', permissionLevel: null,
  },
  {
    accountId: 'maintainer-id', username: 'bob', displayName: 'Bob', accountRole: 'NORMAL',
    enabled: true, relationship: 'MAINTAIN', permissionLevel: 'MAINTAIN',
  },
];

describe('KnowledgeAccountSelect', () => {
  it('shows searchable member identities instead of exposing account UUID input', () => {
    const wrapper = mount(KnowledgeAccountSelect, {
      props: { modelValue: null, members, placeholder: '选择负责人' },
      global: {
        stubs: {
          'el-select': {
            props: ['modelValue', 'multiple', 'placeholder'],
            template: '<div class="select">{{ placeholder }}<slot /></div>',
          },
          'el-option': {
            props: ['label', 'value', 'disabled'],
            template: '<div class="option">{{ label }}<slot /></div>',
          },
        },
      },
    });

    expect(wrapper.text()).toContain('选择负责人');
    expect(wrapper.text()).toContain('Alice（alice）');
    expect(wrapper.text()).toContain('Bob（bob）');
    expect(wrapper.text()).toContain('所有者');
    expect(wrapper.text()).toContain('维护');
    expect(wrapper.text()).not.toContain('owner-id');
  });
});
