import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import TaskProgress from './TaskProgress.vue';
import { taskProgress, taskStageLabel } from './taskProgress';

const states = (status: string, stage: string) => taskProgress({ kind: 'PREPARE', status, stage }).steps.map(step => step.state);
describe('preparation progress from persisted stages', () => {
  it('shows all waiting before the worker starts', () => {
    expect(states('QUEUED', 'QUEUED')).toEqual(['waiting', 'waiting', 'waiting']);
  });
  it.each(['RESOLVING', 'SYNC', 'PUBLISHING'])('groups %s under sync without completing later steps', stage => {
    expect(states('RUNNING', stage)).toEqual(['running', 'waiting', 'waiting']);
  });
  it('only completes earlier steps after their sequential checkpoints', () => {
    expect(states('RUNNING', 'INDEXING')).toEqual(['completed', 'running', 'waiting']);
    expect(states('RUNNING', 'GRAPH')).toEqual(['completed', 'completed', 'running']);
    expect(states('SUCCEEDED', 'COMPLETED')).toEqual(['completed', 'completed', 'completed']);
  });
  it.each([
    ['RESOLVING', ['failed', 'blocked', 'blocked']],
    ['INDEXING', ['completed', 'failed', 'blocked']],
    ['GRAPH', ['completed', 'completed', 'failed']],
  ])('preserves completed steps and stops at failure in %s', (stage, expected) => {
    expect(states('FAILED', stage)).toEqual(expected);
  });
  it('does not invent history for legacy failures or unknown stages', () => {
    const progress = taskProgress({ kind: 'PREPARE', status: 'FAILED', stage: 'FAILED' });
    expect(progress.steps.map(step => step.state)).toEqual(['unknown', 'unknown', 'unknown']);
    expect(progress.note).toContain('未记录失败阶段');
    expect(states('RUNNING', 'new-stage')).toEqual(['unknown', 'unknown', 'unknown']);
  });
  it('distinguishes a finalization failure from a failed workflow step', () => {
    expect(states('FAILED', 'COMPLETED')).toEqual(['completed', 'completed', 'completed']);
    expect(taskProgress({ kind: 'PREPARE', status: 'FAILED', stage: 'COMPLETED' }).note).toContain('收尾失败');
  });
});

it('updates steps when a polling response changes the stage and status', async () => {
  const wrapper = mount(TaskProgress, { props: { task: { kind: 'PREPARE', status: 'RUNNING', stage: 'INDEXING' } } });
  expect(wrapper.find('[aria-current="step"]').text()).toContain('内容索引');
  await wrapper.setProps({ task: { kind: 'PREPARE', status: 'FAILED', stage: 'GRAPH' } });
  expect(wrapper.findAll('[data-state="completed"]')).toHaveLength(2);
  expect(wrapper.find('[data-state="failed"]').text()).toContain('代码图谱');
  expect(wrapper.find('[aria-current]').exists()).toBe(false);
  wrapper.unmount();
});

it('shows single-task stage names without version identifiers and handles cancel/timeout', () => {
  expect(taskStageLabel('check_knowledge_drift:version-uuid', 'RUNNING')).toBe('检查知识失效');
  expect(taskStageLabel('copy_contentVersion', 'RUNNING')).toBe('准备代码文件');
  expect(taskStageLabel('timed_out', 'FAILED')).toBe('执行超时');
  expect(taskStageLabel(null, 'RUNNING')).toContain('阶段未记录');
  expect(taskProgress({ kind: 'CODEGRAPH', status: 'CANCEL_REQUESTED', stage: 'building_codegraph' }).steps[0].state).toBe('running');
  expect(taskProgress({ kind: 'CODEGRAPH', status: 'CANCELED' }).steps[0].state).toBe('canceled');
});
