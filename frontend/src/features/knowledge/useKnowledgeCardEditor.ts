import { reactive } from 'vue';
import type { CardInput, CodeReference, KnowledgeCard } from '@/api/intelligence';

interface PayloadOptions {
  attachmentIds: string[];
  codeReferences: CodeReference[];
}

function emptyForm(ownerAccountId: string | null): CardInput {
  return {
    title: '',
    cardType: '知识卡片',
    content: '',
    tags: [],
    knowledgeKind: 'REFERENCE',
    severity: 'INFO',
    enforcement: 'REFERENCE',
    ownerAccountId,
    scope: {
      pathPatterns: [], symbols: [], modules: [], repositoryIds: [], serviceNames: [], contractIds: [],
    },
    obligations: {
      requiredTests: [], requiredApproverAccountIds: [], instructions: [],
      prohibitedPathPatterns: [], knowledgeUpdateRequired: false,
    },
    attachmentIds: [],
    codeReferences: [],
  };
}

function cardForm(card: KnowledgeCard): CardInput {
  return {
    title: card.title,
    cardType: card.cardType || '知识卡片',
    content: card.content,
    tags: [...card.tags],
    knowledgeKind: card.knowledgeKind,
    severity: card.severity,
    enforcement: card.enforcement,
    ownerAccountId: card.ownerAccountId,
    scope: {
      pathPatterns: [...card.scope.pathPatterns],
      symbols: [...card.scope.symbols],
      modules: [...card.scope.modules],
      repositoryIds: [...(card.scope.repositoryIds ?? [])],
      serviceNames: [...(card.scope.serviceNames ?? [])],
      contractIds: [...(card.scope.contractIds ?? [])],
    },
    obligations: {
      requiredTests: [...card.obligations.requiredTests],
      requiredApproverAccountIds: [...card.obligations.requiredApproverAccountIds],
      instructions: [...card.obligations.instructions],
      prohibitedPathPatterns: [...(card.obligations.prohibitedPathPatterns ?? [])],
      knowledgeUpdateRequired: card.obligations.knowledgeUpdateRequired ?? false,
    },
    attachmentIds: card.attachments.map(item => item.id),
    codeReferences: card.codeReferences
      .filter(reference => reference.chunkId)
      .map(reference => ({ chunkId: reference.chunkId! })),
  };
}

function lines(value: string) {
  return [...new Set(value.split(/\r?\n/).map(item => item.trim()).filter(Boolean))];
}

export function useKnowledgeCardEditor(currentAccountId: () => string | null) {
  const form = reactive<CardInput>(emptyForm(currentAccountId()));
  const scopeText = reactive({ paths: '', symbols: '' });

  function reset(card: KnowledgeCard | null) {
    const next = card ? cardForm(card) : emptyForm(currentAccountId());
    Object.assign(form, next);
    scopeText.paths = next.scope.pathPatterns.join('\n');
    scopeText.symbols = next.scope.symbols.join('\n');
  }

  function toPayload(options: PayloadOptions): CardInput {
    return {
      ...form,
      title: form.title.trim(),
      cardType: form.cardType || '知识卡片',
      tags: [...new Set(form.tags.map(value => value.trim()).filter(Boolean))],
      ownerAccountId: form.ownerAccountId?.trim() || null,
      scope: {
        ...form.scope,
        pathPatterns: lines(scopeText.paths),
        symbols: lines(scopeText.symbols),
        modules: [...form.scope.modules],
        repositoryIds: [...form.scope.repositoryIds],
        serviceNames: [...form.scope.serviceNames],
        contractIds: [...form.scope.contractIds],
      },
      obligations: {
        requiredTests: [...form.obligations.requiredTests],
        requiredApproverAccountIds: [...form.obligations.requiredApproverAccountIds],
        instructions: [...form.obligations.instructions],
        prohibitedPathPatterns: [...form.obligations.prohibitedPathPatterns],
        knowledgeUpdateRequired: form.obligations.knowledgeUpdateRequired,
      },
      attachmentIds: [...options.attachmentIds],
      codeReferences: options.codeReferences
        .filter(reference => reference.chunkId)
        .map(reference => ({ chunkId: reference.chunkId! })),
    };
  }

  return { form, scopeText, reset, toPayload };
}
