import { writeFileSync } from 'node:fs';
import * as z from 'zod/v4';
import { createAnalyzerMcpServer } from '../src/server.mjs';

// SDK v2 keeps registered definitions here; no credentials or backend calls are needed.
const server = createAnalyzerMcpServer({});
const tools = Object.entries(server._registeredTools).map(([name, tool]) => ({
  name, title: tool.title, description: tool.description,
  inputSchema: z.toJSONSchema(tool.inputSchema, { io: 'input' }), annotations: tool.annotations,
}));
writeFileSync(new URL('../../backend/src/main/resources/mcp-tools.json', import.meta.url), `${JSON.stringify(tools, null, 2)}\n`);
