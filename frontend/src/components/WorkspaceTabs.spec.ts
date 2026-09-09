import { nextTick } from 'vue';
import { shallowMount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import WorkspaceTabs from './WorkspaceTabs.vue';

const tabs = [
  { name: 'overview', title: '项目总览', fullPath: '/overview' },
  { name: 'knowledge', title: '知识治理', fullPath: '/knowledge' },
  { name: 'audit', title: '审计日志', fullPath: '/audit' },
];

function setMetric(
  element: HTMLElement,
  key: 'clientWidth' | 'scrollWidth' | 'scrollLeft' | 'offsetLeft' | 'offsetWidth',
  value: number,
) {
  Object.defineProperty(element, key, { configurable: true, writable: true, value });
}

describe('WorkspaceTabs', () => {
  it('shows bounded scroll controls and reveals the active tab', async () => {
    const wrapper = shallowMount(WorkspaceTabs, {
      props: { tabs, activeName: 'overview' },
      global: {
        stubs: {
          teleport: true,
          ElDropdown: { template: '<div><slot /></div>' },
          ElDropdownMenu: { template: '<div><slot /></div>' },
          ElDropdownItem: { template: '<button><slot /></button>' },
        },
      },
    });
    await nextTick();
    await nextTick();
    const viewport = wrapper.get('.tab-viewport').element as HTMLElement;
    const track = wrapper.get('.tab-track').element as HTMLElement;
    const scrollBy = vi.fn();
    const scrollTo = vi.fn();
    Object.defineProperty(track, 'scrollBy', { configurable: true, value: scrollBy });
    Object.defineProperty(track, 'scrollTo', { configurable: true, value: scrollTo });
    setMetric(viewport, 'clientWidth', 460);
    setMetric(track, 'clientWidth', 400);
    setMetric(track, 'scrollWidth', 900);
    setMetric(track, 'scrollLeft', 0);

    window.dispatchEvent(new Event('resize'));
    await nextTick();

    const left = wrapper.get('[aria-label="向左滚动页签"]');
    const right = wrapper.get('[aria-label="向右滚动页签"]');
    expect(left.attributes('disabled')).toBeDefined();
    expect(right.attributes('disabled')).toBeUndefined();

    await right.trigger('click');
    expect(scrollBy).toHaveBeenCalledWith({ left: 288, behavior: 'smooth' });

    setMetric(track, 'scrollLeft', 500);
    await wrapper.get('.tab-track').trigger('scroll');
    expect(wrapper.get('[aria-label="向左滚动页签"]').attributes('disabled')).toBeUndefined();
    expect(wrapper.get('[aria-label="向右滚动页签"]').attributes('disabled')).toBeDefined();

    setMetric(track, 'scrollLeft', 0);
    const auditTab = wrapper.findAll('.workspace-tab')[2].element as HTMLElement;
    setMetric(auditTab, 'offsetLeft', 760);
    setMetric(auditTab, 'offsetWidth', 120);
    await wrapper.setProps({ activeName: 'audit' });
    await nextTick();
    await nextTick();

    expect(scrollTo).toHaveBeenCalledWith({ left: 486, behavior: 'smooth' });
    wrapper.unmount();
  });
});