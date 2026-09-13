import { pathToFileURL } from 'node:url';
import { McpServer } from '@modelcontextprotocol/server';
import { serveStdio } from '@modelcontextprotocol/server/stdio';
import * as z from 'zod/v4';
import { AnalyzerApiError, clientFromEnvironment } from './api-client.mjs';

const uuid = z.string().uuid();
const repositoryId = uuid.describe('已认证账户可读取的仓库 UUID');

export function createAnalyzerMcpServer(api = clientFromEnvironment()) {
  const server = new McpServer(
    { name: 'analyzer-coder', version: '1.0.0' },
    { instructions: 'Use resolve_project_context with a tracked branchId to pin a branch snapshot, then reuse contextId in search_project. Without contextId, search_project reads the legacy default snapshot.' },
  );

  server.registerTool(
    'search_project',
    {
      title: 'Search project code and knowledge',
      description: 'Returns one ranked result set containing current code and published project knowledge.',
      inputSchema: z.object({
        repositoryId,
        contextId: uuid.optional().describe('由 resolve_project_context 返回的阅读上下文；过期时重新解析'),
        branchId: uuid.optional().describe('可选的已跟踪分支 UUID；不传 contextId 时解析该分支'),
        query: z.string().min(1).max(1000),
        limit: z.number().int().min(1).max(50).default(20),
      }),
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true },
    },
    async input => toolCall(async () => {
      const search = new URLSearchParams({ query: input.query, limit: String(input.limit) });
      const context = input.contextId || input.branchId ? await api.request(
        `/api/repositories/${input.repositoryId}/contexts`,
        { method: 'POST', body: JSON.stringify({ branchId: input.branchId, contextId: input.contextId }) },
      ) : null;
      const result = await api.request(
        `/api/repositories/${input.repositoryId}/evidence-search?${search}`,
        context ? { headers: { 'X-Branch-Context': context.contextId } } : undefined,
      );
      const codeCount = (result.evidence ?? []).filter(item => item.sourceType === 'CODE').length;
      const knowledgeCount = (result.evidence ?? []).filter(item => item.sourceType === 'KNOWLEDGE').length;
      return response(context ? { context, result } : result, `${codeCount} code results and ${knowledgeCount} knowledge results.`);
    }),
  );

  server.registerTool('resolve_project_context', {
    title: 'Resolve project branch context',
    description: 'Pin a prepared branch snapshot for consistent subsequent searches. An unavailable branch never falls back to another branch.',
    inputSchema: z.object({ repositoryId, branchId: uuid.optional(), contextId: uuid.optional() })
      .refine(input => Boolean(input.branchId || input.contextId), 'branchId or contextId is required'),
    annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: false },
  }, async input => toolCall(async () => {
    const context = await api.request(`/api/repositories/${input.repositoryId}/contexts`, {
      method: 'POST', body: JSON.stringify({ branchId: input.branchId, contextId: input.contextId }),
    });
    return response(context, `Pinned branch ${context.branchName} at ${context.commitSha}.`);
  }));
  return server;
}

function response(data, text) {
  return { content: [{ type: 'text', text }], structuredContent: data };
}

async function toolCall(action) {
  try { return await action(); }
  catch (error) {
    const code = error instanceof AnalyzerApiError ? error.code : 'MCP_ADAPTER_ERROR';
    const message = error instanceof Error ? error.message : String(error);
    return {
      content: [{ type: 'text', text: `${code}: ${message}` }],
      structuredContent: { code, message },
      isError: true,
    };
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  void serveStdio(() => createAnalyzerMcpServer());
  console.error('代码与知识联合检索 MCP 服务正在监听 stdio');
}
