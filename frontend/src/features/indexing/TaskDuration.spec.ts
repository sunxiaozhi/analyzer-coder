import { mount } from '@vue/test-utils';
import { defineComponent, nextTick, shallowRef } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import TaskDuration from './TaskDuration.vue';
import { formatDuration, taskDuration } from './taskDuration';

const createdAt = '2026-10-15T00:00:00Z';
const startedAt = '2026-10-15T00:01:00Z';
const finishedAt = '2026-10-15T00:02:05Z';
const now = Date.parse('2026-10-15T00:03:00Z');
beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(now); });
afterEach(() => { vi.useRealTimers(); });

describe('task timing semantics', () => {
  it('separates waiting time from execution and total time', () => {
    expect(taskDuration({ status: 'QUEUED', createdAt }, 'execution', now).text).toBe('排队 3分');
    expect(taskDuration({ status: 'RUNNING', createdAt, startedAt }, 'execution', now).text).toBe('2分');
    expect(taskDuration({ status: 'RUNNING', createdAt }, 'total', now).text).toBe('3分');
  });
  it.each(['SUCCEEDED', 'FAILED', 'CANCELED'])('freezes %s at the recorded finish time', status => {
    expect(taskDuration({ status, createdAt, startedAt, finishedAt }, 'execution', now + 600000).text).toBe('1分5秒');
    expect(taskDuration({ status, createdAt, updatedAt: finishedAt }, 'total', now + 600000).text).toBe('2分5秒');
  });
  it('keeps cancellation requests live until execution ends', () => {
    expect(taskDuration({ status: 'CANCEL_REQUESTED', startedAt }, 'execution', now)).toMatchObject({ text: '2分', live: true });
  });
  it('handles missing, invalid and reversed timestamps without inventing durations', () => {
    expect(taskDuration({ status: 'SUCCEEDED', createdAt }, 'total', now).text).toBe('—');
    expect(taskDuration({ status: 'CANCELED', createdAt }, 'execution', now).text).toBe('—');
    expect(taskDuration({ status: 'RUNNING', startedAt: 'invalid' }, 'execution', now)).toMatchObject({ text: '—', live: false });
    expect(taskDuration({ status: 'FAILED', startedAt: finishedAt, finishedAt: startedAt }, 'execution', now).text).toBe('—');
    expect(formatDuration(500)).toBe('不足1秒');
    expect(formatDuration(90061000)).toBe('1天1小时1分1秒');
  });
});

it('ticks without requests, stops on completion and disposes on unmount', async () => {
  const wrapper = mount(TaskDuration, { props: { task: { status: 'RUNNING', startedAt } } });
  expect(wrapper.text()).toBe('2分');
  await vi.advanceTimersByTimeAsync(2000);
  expect(wrapper.text()).toBe('2分2秒');
  await wrapper.setProps({ task: { status: 'FAILED', startedAt, finishedAt } });
  expect(wrapper.text()).toBe('1分5秒');
  expect(vi.getTimerCount()).toBe(0);
  await vi.advanceTimersByTimeAsync(5000);
  expect(wrapper.text()).toBe('1分5秒');
  await wrapper.setProps({ task: { status: 'RUNNING', startedAt } });
  expect(vi.getTimerCount()).toBe(1);
  wrapper.unmount();
  expect(vi.getTimerCount()).toBe(0);
});

it('pauses cached pages and catches up when they become visible', async () => {
  const visible = shallowRef(true);
  const host = mount(defineComponent({
    components: { TaskDuration },
    setup: () => ({ visible, task: { status: 'RUNNING', startedAt } }),
    template: '<KeepAlive><TaskDuration v-if="visible" :task="task" /></KeepAlive>',
  }));
  expect(host.text()).toBe('2分');
  visible.value = false; await nextTick();
  expect(vi.getTimerCount()).toBe(0);
  await vi.advanceTimersByTimeAsync(10000);
  visible.value = true; await nextTick();
  expect(host.text()).toBe('2分10秒');
  expect(vi.getTimerCount()).toBe(1);
  host.unmount();
  expect(vi.getTimerCount()).toBe(0);
});
