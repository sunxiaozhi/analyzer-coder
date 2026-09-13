import { expect, it } from 'vitest';
import {
  atlasFileType, fileIconSvg, fileIconUrl,
  MODULE_FOLDER_GLYPH_DIAGONAL, MODULE_RING_INNER_DIAMETER,
} from './atlasFileType';

it.each([
  ['src/Service.java', 'java'], ['src/main.ts', 'typescript'], ['src/types.d.ts', 'typescript'],
  ['src/view.tsx', 'tsx'], ['src/main.mjs', 'javascript'], ['src/App.vue', 'vue'],
  ['C:\\project\\README.MD', 'markdown'], ['src/main.PY', 'python'], ['main.rs', 'rust'],
  ['Dockerfile', 'docker'], ['docker/Dockerfile.dev', 'docker'], ['.env.local', 'config'],
  ['config.yaml', 'config'], ['foo.unknown', 'file'], ['', 'file'], ['LICENSE', 'file'],
])('maps %s from the source path to %s', (filePath, id) => {
  expect(atlasFileType({ kind: 'method', filePath }).id).toBe(id);
});
it('does not infer a module file type from a folder name ending in an extension', () => {
  expect(atlasFileType({ kind: 'MODULE', filePath: 'folder.java' }).id).toBe('folder');
});
it('uses distinct self-contained icons and escapes XML markup badges', () => {
  const html = atlasFileType({ kind: 'class', filePath: 'index.html' });
  expect(fileIconSvg(html)).toContain('&lt;/&gt;');
  expect(fileIconUrl(html)).toMatch(/^data:image\/svg\+xml/);
  expect(fileIconUrl(html)).not.toBe(fileIconUrl(atlasFileType({ kind: 'method', filePath: 'x.ts' })));
});
it('keeps the module folder fully inside the ring', () => {
  expect(MODULE_RING_INNER_DIAMETER).toBeGreaterThan(MODULE_FOLDER_GLYPH_DIAGONAL);
});
