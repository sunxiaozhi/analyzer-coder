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
    { instructions: 'Use search_project to retrieve current code and published project knowledge with source evidence.' },
  );

  server.registerTool(
    'search_project',
    {
      title: 'Search project code and knowledge',
      description: 'Returns one ranked result set containing current code and published project knowledge.',
      inputSchema: z.object({
        repositoryId,
        query: z.string().min(1).max(1000),
        limit: z.number().int().min(1).max(50).default(20),
      }),
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true },
    },
    async input => toolCall(async () => {
      const search = new URLSearchParams({ query: input.query, limit: String(input.limit) });
      const result = await api.request(
        `/api/repositories/${input.repositoryId}/evidence-search?${search}`,
      );
      const codeCount = (result.evidence ?? []).filter(item => item.sourceType === 'CODE').length;
      const knowledgeCount = (result.evidence ?? []).filter(item => item.sourceType === 'KNOWLEDGE').length;
      return response(result, `${codeCount} code results and ${knowledgeCount} knowledge results.`);
    }),
  );

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
