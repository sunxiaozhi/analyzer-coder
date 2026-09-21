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
    { instructions: 'Use list_codegraph_scopes to select a visible repository and branch. Reuse contextId from a graph query for a pinned contentVersion. search_project also remains available.' },
  );

  server.registerTool(
    'search_project',
    {
      title: 'Search project code and knowledge',
      description: 'Returns one ranked result set containing code and published knowledge from one explicit branch.',
      inputSchema: z.object({
        repositoryId,
        contextId: uuid.optional().describe('由 resolve_project_context 返回的阅读上下文；过期时重新解析'),
        branchId: uuid.optional().describe('可选的已跟踪分支 UUID；不传 contextId 时解析该分支'),
        query: z.string().min(1).max(1000),
        limit: z.number().int().min(1).max(50).default(20),
      }).refine(input => Boolean(input.branchId || input.contextId), 'branchId or contextId is required'),
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true },
    },
    async input => toolCall(async () => {
      const search = new URLSearchParams({ query: input.query, limit: String(input.limit) });
      const context = await api.request(
        `/api/repositories/${input.repositoryId}/contexts`,
        { method: 'POST', body: JSON.stringify({ branchId: input.branchId, contextId: input.contextId }) },
      );
      const result = await api.request(
        `/api/repositories/${input.repositoryId}/evidence-search?${search}`,
        { headers: { 'X-Branch-Context': context.contextId } },
      );
      const codeCount = (result.evidence ?? []).filter(item => item.sourceType === 'CODE').length;
      const knowledgeCount = (result.evidence ?? []).filter(item => item.sourceType === 'KNOWLEDGE').length;
      return response({ context, result }, `${codeCount} code results and ${knowledgeCount} knowledge results.`);
    }),
  );

  server.registerTool('resolve_project_context', {
    title: 'Resolve project branch context',
    description: 'Pin a prepared branch contentVersion for consistent subsequent searches. An unavailable branch never falls back to another branch.',
    inputSchema: z.object({ repositoryId, branchId: uuid.optional(), contextId: uuid.optional() })
      .refine(input => Boolean(input.branchId || input.contextId), 'branchId or contextId is required'),
    annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: false },
  }, async input => toolCall(async () => {
    const context = await api.request(`/api/repositories/${input.repositoryId}/contexts`, {
      method: 'POST', body: JSON.stringify({ branchId: input.branchId, contextId: input.contextId }),
    });
    return response(context, `Pinned branch ${context.branchName} at ${context.commitSha}.`);
  }));
  const graphScope = {
    repositoryId,
    branchId: uuid.optional(),
    contextId: uuid.optional(),
  };
  const graphTools = [
    ['list_codegraph_scopes', 'List CodeGraph projects and branches', { page: z.number().int().min(1).max(100000).default(1), pageSize: z.number().int().min(1).max(50).default(20) }],
    ['codegraph_explore', 'Explore branch code graph', { ...graphScope, query: z.string().min(1).max(500), maxFiles: z.number().int().min(1).max(20).default(8) }],
    ['codegraph_node', 'Read graph node', { ...graphScope, name: z.string().min(1).max(500).optional(), file: z.string().min(1).max(500).optional(), offset: z.number().int().min(0).max(10000).default(0), limit: z.number().int().min(1).max(100).default(50) }],
    ['codegraph_search', 'Search graph symbols', { ...graphScope, query: z.string().min(1).max(500), limit: z.number().int().min(1).max(50).default(20) }],
    ['codegraph_callers', 'Find callers', { ...graphScope, symbol: z.string().min(1).max(500), limit: z.number().int().min(1).max(50).default(20) }],
    ['codegraph_callees', 'Find callees', { ...graphScope, symbol: z.string().min(1).max(500), limit: z.number().int().min(1).max(50).default(20) }],
    ['codegraph_impact', 'Analyze symbol impact', { ...graphScope, symbol: z.string().min(1).max(500), depth: z.number().int().min(1).max(5).default(2) }],
    ['codegraph_files', 'List graph files', { ...graphScope, filter: z.string().min(1).max(500).optional(), pattern: z.string().min(1).max(500).optional() }],
    ['codegraph_status', 'Graph status', graphScope],
    ['codegraph_affected', 'Analyze affected tests', { ...graphScope, files: z.array(z.string().min(1).max(500)).min(1).max(20), depth: z.number().int().min(1).max(5).default(2) }],
  ];
  for (const [name, title, schema] of graphTools) {
    server.registerTool(name, {
      title,
      description: `${title} for the token account. Branch queries require branchId or contextId and never switch branches implicitly.`,
      inputSchema: z.object(schema),
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true },
    }, async input => toolCall(async () => {
      if (!api.accessToken) throw new AnalyzerApiError(401, 'ACCESS_TOKEN_REQUIRED', 'CodeGraph MCP tools require ANALYZER_ACCESS_TOKEN');
      const reply = await api.request('/api/mcp', {
        method: 'POST',
        body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name, arguments: input } }),
      });
      if (reply.error) throw new AnalyzerApiError(400, 'MCP_BACKEND_ERROR', reply.error.message);
      return reply.result;
    }));
  }
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
