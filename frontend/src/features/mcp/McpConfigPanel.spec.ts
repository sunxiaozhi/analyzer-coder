import { mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import McpConfigPanel from './McpConfigPanel.vue';

vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), warning: vi.fn() } }));
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks(); });
const mountPanel = () => mount(McpConfigPanel, {
  global: { stubs: { ElButton: { template: '<button><slot /></button>' } } },
});

describe('MCP configuration template', () => {
  it('generates remote HTTP config and keeps credentials as placeholders', async () => {
    const wrapper = mountPanel();
    await wrapper.get('#mcp-api-base').setValue('https://example.test/');
    const config = JSON.parse(wrapper.get('pre').text()).mcpServers['analyzer-coder'];
    expect(config.url).toBe('https://example.test/api/mcp');
    expect(config.headers.Authorization).toBe('Bearer <填写账户访问令牌>');
    expect(config.command).toBeUndefined();
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', { clipboard: { writeText } });
    await wrapper.get('button').trigger('click');
    expect(JSON.parse(writeText.mock.calls[0][0])).toEqual(JSON.parse(wrapper.get('pre').text()));
    wrapper.unmount();
  });

  it('keeps the template selectable when clipboard access is denied', async () => {
    vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } });
    const wrapper = mountPanel();
    await wrapper.get('button').trigger('click');
    expect(ElMessage.warning).toHaveBeenCalledWith(expect.stringContaining('手动复制'));
    expect(wrapper.get('pre').attributes('tabindex')).toBe('0');
    wrapper.unmount();
  });
});
