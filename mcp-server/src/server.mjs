import { randomUUID } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { McpServer } from '@modelcontextprotocol/server';
import { serveStdio } from '@modelcontextprotocol/server/stdio';
import * as z from 'zod/v4';
import { AnalyzerApiError, clientFromEnvironment } from './api-client.mjs';

const uuid = z.string().uuid();
const repositoryId = uuid.describe('已认证的代码知识平台账户可见的仓库 UUID');

export function createAnalyzerMcpServer(api = clientFromEnvironment()) {
  const server = new McpServer(
    { name: 'analyzer-coder', version: '1.0.0' },
    {
      instructions:
        'Call review_change before get_task_context when deterministic rules are required. '
        + 'RETRIEVAL_CANDIDATE is a search lead, never a mandatory rule. '
        + 'Use get_evidence with a returned source ID for full provenance.',
    },
  );

  server.registerTool(
    'review_change',
    {
      title: 'Review a real code change',
      description: 'Runs the same immutable Git change review as the HTTP API. Requires repository READ permission.',
      inputSchema: z.object({
        repositoryId,
        task: z.string().max(1000).nullable().optional(),
        changeSource: z.enum(['WORKTREE', 'SINGLE_COMMIT', 'COMMIT_RANGE']),
        baseRef: z.string().max(200).nullable().optional(),
        headRef: z.string().max(200).nullable().optional(),
        clientRequestId: uuid.optional(),
        modelConfigId: uuid.nullable().optional(),
        includeEvidence: z.boolean().default(false),
      }),
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true },
    },
    async input => toolCall(async () => {
      const review = await api.request(`/api/repositories/${input.repositoryId}/task-reviews`, {
        method: 'POST',
        body: JSON.stringify({
          clientRequestId: input.clientRequestId ?? randomUUID(),
          task: input.task ?? null,
          changeSource: input.changeSource,
          baseRef: input.baseRef ?? null,
          headRef: input.headRef ?? null,
          modelConfigId: input.modelConfigId ?? null,
        }),
      });
      return response(
        input.includeEvidence ? review : compactReview(review),
        `Review ${review.reviewId} ${review.status}: ${review.changedSymbols?.length ?? 0} symbols, `
          + `${review.requiredTests?.length ?? 0} tests, ${review.unknowns?.length ?? 0} unknowns.`,
      );
    }),
  );

  server.registerTool(
    'get_task_context',
    {
      title: 'Get Agent task context',
      description: 'Returns version-bound rules, obligations, unknowns, code facts, and retrieval leads.',
      inputSchema: z.object({
        repositoryId,
        task: z.string().min(1).max(1000),
        taskReviewId: uuid.nullable().optional(),
        maxItems: z.number().int().min(5).max(40).default(12),
        maxChars: z.number().int().min(4000).max(60000).default(12000),
        maxTokens: z.number().int().min(500).max(15000).default(3000),
        includeContent: z.boolean().default(false),
      }),
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true },
    },
    async input => toolCall(async () => {
      const context = await taskContext(api, input);
      const data = input.includeContent ? context : compactContext(context);
      return response(
        data,
        `Context at ${context.commitSha ?? 'no-commit'} / ${context.snapshotId}: `
          + `${context.entries.length} entries, ${context.requiredTests.length} required tests, `
          + `${context.unknowns.length} unknowns.`,
      );
    }),
  );

  server.registerTool(
    'get_rules_for_symbol',
    {
      title: 'Get deterministic rules for a changed symbol',
      description: 'Projects only verified knowledge from an immutable task review; it never promotes retrieval matches.',
      inputSchema: z.object({
        repositoryId,
        reviewId: uuid,
        symbol: z.string().min(1).max(300),
        includeEvidence: z.boolean().default(false),
      }),
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true },
    },
    async input => toolCall(async () => {
      const review = await getReview(api, input.repositoryId, input.reviewId);
      const rules = (review.applicableKnowledge ?? []).filter(match =>
        (match.reasons ?? []).some(reason =>
          reason.target === input.symbol
          || reason.rule === input.symbol
          || reason.evidence?.symbolName === input.symbol,
        ),
      ).map(match => input.includeEvidence ? match : compactKnowledge(match));
      return response(
        { reviewId: review.reviewId, symbol: input.symbol, rules },
        `${rules.length} deterministic rules matched symbol ${input.symbol}.`,
      );
    }),
  );

  server.registerTool(
    'get_required_tests',
    {
      title: 'Get required tests',
      description: 'Returns required tests exactly as persisted by an immutable task review.',
      inputSchema: z.object({ repositoryId, reviewId: uuid, includeEvidence: z.boolean().default(false) }),
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true },
    },
    async input => toolCall(async () => {
      const review = await getReview(api, input.repositoryId, input.reviewId);
      const tests = (review.requiredTests ?? []).map(item => input.includeEvidence ? item : ({
        key: item.key,
        title: item.title,
        status: item.status,
        knowledgeIds: item.knowledgeIds,
        sourceIds: (item.sources ?? []).map(source => source.id),
      }));
      return response({ reviewId: review.reviewId, tests }, `${tests.length} required tests.`);
    }),
  );

  server.registerTool(
    'get_stale_knowledge',
    {
      title: 'Get stale knowledge for a review',
      description: 'Returns SUSPECT/STALE knowledge already isolated by the HTTP review service.',
      inputSchema: z.object({ repositoryId, reviewId: uuid, includeEvidence: z.boolean().default(false) }),
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true },
    },
    async input => toolCall(async () => {
      const review = await getReview(api, input.repositoryId, input.reviewId);
      const staleKnowledge = (review.staleKnowledge ?? [])
        .map(item => input.includeEvidence ? item : compactKnowledge(item));
      return response(
        { reviewId: review.reviewId, staleKnowledge },
        `${staleKnowledge.length} stale or suspect knowledge cards.`,
      );
    }),
  );

  server.registerTool(
    'get_evidence',
    {
      title: 'Get provenance by ID',
      description: 'Loads one full provenance record from a review or regenerated task context.',
      inputSchema: z.object({
        repositoryId,
        reviewId: uuid,
        evidenceId: uuid,
        task: z.string().min(1).max(1000).optional(),
      }),
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true },
    },
    async input => toolCall(async () => {
      const review = await getReview(api, input.repositoryId, input.reviewId);
      let sources = reviewSources(review);
      if (input.task) {
        const context = await taskContext(api, {
          repositoryId: input.repositoryId,
          task: input.task,
          taskReviewId: input.reviewId,
          maxItems: 40,
          maxChars: 60000,
          maxTokens: 15000,
        });
        sources = [...sources, ...context.entries.flatMap(entry => entry.sources ?? [])];
      }
      const evidence = sources.find(source => source.id === input.evidenceId);
      if (!evidence) throw new AnalyzerApiError(404, 'EVIDENCE_NOT_FOUND', 'Evidence ID was not found in this review context');
      return response({ reviewId: review.reviewId, evidence }, `Evidence ${evidence.id}: ${evidence.sourceType}.`);
    }),
  );

  server.registerTool(
    'report_task_outcome',
    {
      title: 'Report task outcome',
      description: 'Appends an immutable, attributed delivery result and human feedback to a completed task review.',
      inputSchema: z.object({
        repositoryId,
        reviewId: uuid,
        clientRequestId: uuid.optional(),
        finalCommit: z.string().regex(/^[0-9a-fA-F]{40,64}$/),
        summary: z.string().min(1).max(4000),
        tests: z.array(z.object({
          key: z.string().min(1).max(500),
          status: z.enum(['PASSED', 'FAILED', 'SKIPPED']),
          evidenceUrl: z.url().nullable().optional(),
        })).max(200).default([]),
        approvals: z.array(z.object({
          accountId: uuid,
          status: z.enum(['APPROVED', 'REJECTED']),
          evidenceUrl: z.url().nullable().optional(),
        })).max(100).default([]),
        feedback: z.array(z.object({
          kind: z.enum(['FALSE_POSITIVE', 'FALSE_NEGATIVE', 'KNOWLEDGE_UPDATE']),
          targetType: z.enum([
            'KNOWLEDGE', 'REQUIRED_TEST', 'REQUIRED_APPROVAL', 'STALE_KNOWLEDGE',
            'UNKNOWN', 'FILE', 'SYMBOL', 'OTHER',
          ]),
          targetKey: z.string().min(1).max(500),
          knowledgeId: uuid.nullable().optional(),
          knowledgeUpdateAssessment: z.enum(['NEEDED', 'NOT_NEEDED', 'UNKNOWN']).nullable().optional(),
          comment: z.string().min(1).max(2000),
          evidenceUrls: z.array(z.url()).max(20).default([]),
        })).max(200).default([]),
      }),
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true },
    },
    async input => toolCall(async () => {
      const outcome = await api.request(
        `/api/repositories/${input.repositoryId}/task-reviews/${input.reviewId}/outcomes`,
        {
          method: 'POST',
          body: JSON.stringify({
            clientRequestId: input.clientRequestId ?? randomUUID(),
            finalCommit: input.finalCommit.toLowerCase(),
            summary: input.summary,
            tests: input.tests.map(item => ({ ...item, evidenceUrl: item.evidenceUrl ?? null })),
            approvals: input.approvals.map(item => ({ ...item, evidenceUrl: item.evidenceUrl ?? null })),
            feedback: input.feedback.map(item => ({
              ...item,
              knowledgeId: item.knowledgeId ?? null,
              knowledgeUpdateAssessment: item.knowledgeUpdateAssessment ?? null,
            })),
          }),
        },
      );
      return response(
        outcome,
        `Outcome ${outcome.id} recorded at ${outcome.finalCommit}: `
          + `${outcome.tests?.length ?? 0} tests, ${outcome.approvals?.length ?? 0} approvals, `
          + `${outcome.feedback?.length ?? 0} feedback items.`,
      );
    }),
  );

  return server;
}

async function taskContext(api, input) {
  return api.request(`/api/repositories/${input.repositoryId}/task-context`, {
    method: 'POST',
    body: JSON.stringify({
      task: input.task,
      taskReviewId: input.taskReviewId ?? null,
      maxItems: input.maxItems,
      maxChars: input.maxChars,
      maxTokens: input.maxTokens,
    }),
  });
}

async function getReview(api, repositoryIdValue, reviewId) {
  return api.request(`/api/repositories/${repositoryIdValue}/task-reviews/${reviewId}`);
}

function compactReview(review) {
  return {
    reviewId: review.reviewId,
    status: review.status,
    repositoryId: review.repositoryId,
    snapshotId: review.snapshotId,
    task: review.task,
    changeSource: review.changeSource,
    baseCommit: review.change?.baseCommit ?? null,
    headCommit: review.change?.headCommit ?? null,
    changedFiles: (review.change?.changes ?? []).map(change => change.newPath ?? change.oldPath),
    changedSymbols: (review.changedSymbols ?? []).map(symbol => ({
      name: symbol.name, kind: symbol.kind, filePath: symbol.filePath,
      startLine: symbol.declarationStartLine, endLine: symbol.declarationEndLine,
    })),
    applicableKnowledge: (review.applicableKnowledge ?? []).map(compactKnowledge),
    requiredTests: (review.requiredTests ?? []).map(item => item.key),
    requiredApprovals: (review.requiredApprovals ?? []).map(item => item.key),
    staleKnowledge: (review.staleKnowledge ?? []).map(compactKnowledge),
    unknowns: (review.unknowns ?? []).map(item => item.unknownReason),
  };
}

function compactKnowledge(match) {
  return {
    knowledgeId: match.knowledgeId,
    title: match.title,
    kind: match.kind,
    severity: match.severity,
    enforcement: match.enforcement,
    revision: match.revision,
    sourceVersionStatus: match.sourceVersionStatus,
    sourceIds: (match.sources ?? []).map(source => source.id),
  };
}

function compactContext(context) {
  return {
    repositoryId: context.repositoryId,
    repositoryName: context.repositoryName,
    snapshotId: context.snapshotId,
    commitSha: context.commitSha,
    task: context.task,
    taskReviewId: context.taskReviewId,
    entries: context.entries.map(entry => ({
      id: entry.id,
      type: entry.type,
      title: entry.title,
      severity: entry.severity,
      enforcement: entry.enforcement,
      knowledgeId: entry.knowledgeId,
      knowledgeRevision: entry.knowledgeRevision,
      filePath: entry.filePath,
      symbolName: entry.symbolName,
      startLine: entry.startLine,
      endLine: entry.endLine,
      requiredTests: entry.requiredTests,
      requiredApproverAccountIds: entry.requiredApproverAccountIds,
      sourceIds: entry.sources.map(source => source.id),
      unknownCode: entry.unknownCode,
    })),
    requiredTests: context.requiredTests,
    requiredApprovals: context.requiredApprovals,
    unknowns: context.unknowns.map(item => ({ code: item.code, detail: item.detail })),
    budget: context.budget,
  };
}

function reviewSources(review) {
  return [
    ...(review.applicableKnowledge ?? []).flatMap(item => item.sources ?? []),
    ...(review.referenceCandidates ?? []).flatMap(item => item.provenance ? [item.provenance] : []),
    ...(review.requiredTests ?? []).flatMap(item => item.sources ?? []),
    ...(review.requiredApprovals ?? []).flatMap(item => item.sources ?? []),
    ...(review.staleKnowledge ?? []).flatMap(item => item.sources ?? []),
    ...(review.unknowns ?? []).flatMap(item => item.sources ?? []),
  ].filter((source, index, values) => values.findIndex(item => item.id === source.id) === index);
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
  console.error('代码知识平台 MCP 服务正在监听 stdio');
}
