import { describe, expect, it } from 'vitest';
import { renderMarkdown } from './markdown';

function parse(source: string) {
  return new DOMParser().parseFromString(renderMarkdown(source), 'text/html');
}

describe('Markdown code highlighting', () => {
  it.each(['java', 'js', 'ts', 'sql', 'python', 'shell', 'vue', 'cmd'])('highlights %s without changing code text', language => {
    const samples: Record<string, string> = {
      java: 'public class Demo {\n    int count = 1;\n}',
      js: 'const message = "hello";', ts: 'const count: number = 1;',
      sql: 'SELECT id FROM users WHERE id = 1;', python: 'def hello():\n    return "hello"',
      shell: 'echo "hello"', vue: '<template><p>hello</p></template>', cmd: 'echo hello',
    };
    const source = samples[language];
    const doc = parse(`\`\`\`${language}\n${source}\n\`\`\``);
    expect(doc.querySelector('pre code.hljs span')).not.toBeNull();
    expect(doc.querySelector('pre code')?.textContent).toBe(source + '\n');
  });

  it('preserves unspecified languages, unknown languages, inline code and Mermaid as text', () => {
    for (const language of ['', 'unknown-language', 'mermaid']) {
      const doc = parse(`\`\`\`${language}\ngraph TD; A-->B\n\`\`\``);
      expect(doc.querySelector('code')?.textContent).toBe('graph TD; A-->B\n');
      expect(doc.querySelector('code span')).toBeNull();
    }
    expect(parse('`const x = 1`').querySelector('code span')).toBeNull();
  });

  it('keeps HTML examples as code and removes executable HTML outside code', () => {
    const doc = parse('```html\n<script>alert(1)</script>\n<img src="x" onerror="alert(1)">\n```\n\n<img src="x" onerror="alert(1)">');
    expect(doc.querySelector('pre code')?.textContent).toContain('<script>alert(1)</script>');
    expect(doc.querySelector('pre img, script, [onerror]')).toBeNull();
  });
});
