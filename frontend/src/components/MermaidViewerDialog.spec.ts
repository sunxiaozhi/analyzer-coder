import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import MermaidViewerDialog from './MermaidViewerDialog.vue';
import { nextTick } from 'vue';

function viewer() {
  return mount(MermaidViewerDialog, {
    props: { modelValue: true, svg: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 400" width="100%"><text>示例</text></svg>' },
    global: { stubs: {
      ElDialog: { props: ['modelValue'], emits: ['opened', 'update:modelValue'], template: '<section v-if="modelValue"><slot /></section>' },
      ElButton: { template: '<button><slot /></button>' },
    } },
  });
}

describe('Mermaid enlarged viewer', () => {
  it('uses SVG intrinsic dimensions and supports zoom buttons and reset', async () => {
    const wrapper = viewer();
    expect(wrapper.get('img').attributes('style')).toContain('width: 800px');
    await wrapper.get('[aria-label="放大图表"]').trigger('click');
    expect(wrapper.get('output').text()).toBe('120%');
    await wrapper.get('[aria-label="缩小图表"]').trigger('click');
    expect(wrapper.get('output').text()).toBe('100%');
    await wrapper.get('[aria-label="放大图表"]').trigger('click');
    await wrapper.findAll('button').find(button => button.text() === '复位')!.trigger('click');
    expect(wrapper.get('output').text()).toBe('100%');
    wrapper.unmount();
  });

  it('zooms with the wheel and moves only while dragging', async () => {
    const wrapper = viewer();
    const viewport = wrapper.get('.diagram-viewport');
    viewport.element.dispatchEvent(new WheelEvent('wheel', { deltaY: -100, clientX: 0, clientY: 0, cancelable: true }));
    await nextTick();
    expect(wrapper.get('output').text()).toBe('116%');
    async function pointer(type: string, clientX: number, clientY: number) {
      const event = new MouseEvent(type, { button: 0, clientX, clientY, cancelable: true });
      Object.defineProperty(event, 'pointerId', { value: 1 });
      viewport.element.dispatchEvent(event);
      await nextTick();
    }
    await pointer('pointerdown', 20, 30);
    await pointer('pointermove', 70, 80);
    expect(wrapper.get('img').attributes('style')).toContain('translate(50px, 50px)');
    await pointer('pointerup', 70, 80);
    await pointer('pointermove', 90, 100);
    expect(wrapper.get('img').attributes('style')).toContain('translate(50px, 50px)');
    wrapper.unmount();
  });
});
