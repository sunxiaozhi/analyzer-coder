import type { AtlasNode } from '@/api/codeAtlas';

export interface AtlasFileType { id: string; label: string; badge: string; color: string }
const type = (id: string, label: string, badge: string, color: string): AtlasFileType => ({ id, label, badge, color });
const folder = type('folder', '模块 / 文件夹', '', '#9a6010');
const unknown = type('file', '其他文件', '?', '#526777');
const types = [
  [type('java', 'Java', 'JAVA', '#a64b20'), ['java']],
  [type('typescript', 'TypeScript', 'TS', '#2467ae'), ['ts', 'mts', 'cts']],
  [type('tsx', 'TypeScript JSX', 'TSX', '#2467ae'), ['tsx']],
  [type('javascript', 'JavaScript', 'JS', '#8c6500'), ['js', 'mjs', 'cjs']],
  [type('jsx', 'JavaScript JSX', 'JSX', '#08768a'), ['jsx']],
  [type('vue', 'Vue', 'VUE', '#16734e'), ['vue']],
  [type('python', 'Python', 'PY', '#346c97'), ['py', 'pyi', 'pyw']],
  [type('go', 'Go', 'GO', '#08768a'), ['go']],
  [type('rust', 'Rust', 'RS', '#9d4727'), ['rs']],
  [type('cpp', 'C / C++', 'C++', '#4f60a8'), ['cpp', 'cc', 'cxx', 'hpp', 'hh', 'hxx']],
  [type('c', 'C / 头文件', 'C', '#4f60a8'), ['c', 'h']],
  [type('csharp', 'C#', 'C#', '#6650a4'), ['cs']],
  [type('kotlin', 'Kotlin', 'KT', '#6650a4'), ['kt', 'kts']],
  [type('swift', 'Swift', 'SW', '#a64b20'), ['swift']],
  [type('ruby', 'Ruby', 'RB', '#af443c'), ['rb']],
  [type('php', 'PHP', 'PHP', '#6650a4'), ['php']],
  [type('markdown', 'Markdown', 'MD↓', '#526777'), ['md', 'mdx', 'markdown']],
  [type('json', 'JSON', '{}', '#8c6500'), ['json', 'jsonc', 'json5']],
  [type('config', '配置文件', 'CFG', '#6650a4'), ['yaml', 'yml', 'toml', 'ini', 'conf', 'properties', 'env']],
  [type('html', 'HTML', '</>', '#a64b20'), ['html', 'htm']],
  [type('xml', 'XML', 'XML', '#a64b20'), ['xml', 'xsd']],
  [type('css', '样式文件', 'CSS', '#2467ae'), ['css', 'scss', 'sass', 'less']],
  [type('sql', 'SQL', 'SQL', '#08768a'), ['sql']],
  [type('shell', '脚本', '>_', '#16734e'), ['sh', 'bash', 'zsh', 'ps1', 'bat', 'cmd']],
  [type('image', '图片', 'IMG', '#6650a4'), ['svg', 'png', 'jpg', 'jpeg', 'webp', 'gif', 'ico']],
  [type('docker', 'Docker', 'DOCK', '#2467ae'), ['dockerfile']],
] as const;
const extensionTypes = new Map<string, AtlasFileType>(types.flatMap(([entry, extensions]) => extensions.map(ext => [ext, entry] as const)));
export function atlasFileType(node: Pick<AtlasNode, 'kind' | 'filePath'>): AtlasFileType {
  if (node.kind === 'MODULE') return folder;
  const name = node.filePath.replace(/\\/g, '/').split('/').pop()?.toLowerCase() ?? '';
  if (name === 'dockerfile' || name.startsWith('dockerfile.')) return extensionTypes.get('dockerfile')!;
  if (name === '.env' || name.startsWith('.env.') || ['.gitignore', '.editorconfig'].includes(name)) return extensionTypes.get('env')!;
  const dot = name.lastIndexOf('.');
  return dot >= 0 ? extensionTypes.get(name.slice(dot + 1)) ?? unknown : unknown;
}
export const fileIconPath = 'M14 5H39L52 18V58H14Z';
export const folderIconPath = 'M5 16V10H25L31 17H59V54H5Z';
export const MODULE_ICON_SIZE = 55;
export const MODULE_RING_SIZE = 86;
export const MODULE_RING_INNER_DIAMETER = MODULE_RING_SIZE * 203 / 256;
export const MODULE_FOLDER_GLYPH_DIAGONAL = MODULE_ICON_SIZE * Math.hypot(54, 44) / 64;
export function fileIconSvg(entry: AtlasFileType): string {
  const escape = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><path d="' +
    (entry.id === 'folder' ? folderIconPath : fileIconPath) + '" fill="#fff" stroke="' + entry.color +
    '" stroke-width="3" stroke-linejoin="round"/>' +
    (entry.id === 'folder' ? '<path d="M5 24H59" stroke="' + entry.color + '" stroke-width="3"/>' :
      '<path d="M39 5V18H52" fill="none" stroke="' + entry.color + '" stroke-width="2"/><text x="33" y="42" text-anchor="middle" font-family="Arial,sans-serif" font-size="14" font-weight="700" fill="' + entry.color + '">' + escape(entry.badge) + '</text>') + '</svg>';
}
const urls = new Map<string, string>();
export function fileIconUrl(entry: AtlasFileType) {
  if (!urls.has(entry.id)) urls.set(entry.id, 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(fileIconSvg(entry)));
  return urls.get(entry.id)!;
}
