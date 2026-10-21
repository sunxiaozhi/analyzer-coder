import { describe, expect, it } from 'vitest';
import type { Citation } from '@/api/intelligence';
import { renderAnswerCitations } from './answerCitations';

const citation = { rank: 3, title: '退款规则 <img src=x onerror=alert(1)>', filePath: 'knowledge://card' } as Citation;
function parse(answer: string) {
  return new DOMParser().parseFromString(renderAnswerCitations(answer, [citation]), 'text/html');
}

describe('readable answer citations', () => {
  it('uses validated titles as text and supports repeated, nonconsecutive reference numbers', () => {
    const doc = parse('规则 [S3]。再次引用 [S3]。');
    expect(doc.querySelectorAll('button[data-citation-rank="3"]')).toHaveLength(2);
    expect(doc.querySelector('button')?.textContent).toBe(citation.title);
    expect(doc.querySelector('img, [onerror]')).toBeNull();
  });
  it('preserves literal references in code and unknown references', () => {
    const doc = parse('`[S3]`\n\n```text\n[S3]\n```\n\n未知 [S9]。');
    expect(doc.querySelector('button')).toBeNull();
    expect(doc.body.textContent).toContain('[S9]');
  });
  it('sanitizes model HTML and unsafe links before inserting references', () => {
    const doc = parse('<script>alert(1)</script><img src=x onerror=alert(1)>\n\n[危险](javascript:alert(1)) [S3]');
    expect(doc.querySelector('script, [onerror], [href^="javascript:"]')).toBeNull();
    expect(doc.querySelector('button')?.dataset.citationRank).toBe('3');
  });
});
