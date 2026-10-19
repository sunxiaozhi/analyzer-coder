// @vitest-environment node
import { expect, it } from 'vitest';
import { auditEventLabel, auditEventLabels } from './auditEventLabels';

const backendSources = import.meta.glob<string>('../../../../backend/src/main/java/**/*.java', { query: '?raw', import: 'default', eager: true });

it('provides Chinese labels for every event emitted by current backend audit writers', () => {
  const events = new Set<string>();
  for (const source of Object.values(backendSources)) {
    const calls = [...source.matchAll(/\baudit\(([\s\S]*?)\);/g)].map(match => match[1]);
    // Account changes select the event using a local conditional expression.
    const selections = [...source.matchAll(/String event\s*=([\s\S]*?);/g)].map(match => match[1]);
    for (const text of [...calls, ...selections]) {
      for (const match of text.matchAll(/"([A-Z][A-Z_]+)"/g)) {
        if (!['SUCCESS', 'DENIED'].includes(match[1])) events.add(match[1]);
      }
    }
  }
  expect(events.size).toBeGreaterThan(20);
  const missing = [...events].filter(event => !auditEventLabels[event]);
  expect(missing, 'Backend audit event types missing Chinese labels').toEqual([]);
  for (const event of events) expect(auditEventLabel(event)).toMatch(/[\u4e00-\u9fff]/);
});

it('keeps ownership transfer and deletion requests distinct, and preserves unknown event identity', () => {
  expect(auditEventLabel('REPOSITORY_OWNERSHIP_TRANSFERRED')).toBe('转移项目所有权');
  expect(auditEventLabel('REPOSITORY_DELETION_REQUESTED')).toBe('申请删除项目');
  expect(auditEventLabel('FUTURE_EVENT')).toBe('FUTURE_EVENT');
});
