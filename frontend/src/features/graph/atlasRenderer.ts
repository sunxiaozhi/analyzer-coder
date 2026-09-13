import * as THREE from 'three';

export function createAtlasRenderer() {
  const attempts: WebGLContextAttributes[] = [
    { antialias: true, alpha: true, powerPreference: 'default' },
    { antialias: false, alpha: true, powerPreference: 'default' },
    { antialias: false, alpha: true, powerPreference: 'low-power' },
  ];
  const failures: string[] = [];
  for (const options of attempts) {
    const canvas = document.createElement('canvas');
    let context: WebGL2RenderingContext | null = null;
    let detail = '';
    const onError = (event: Event) => { detail = (event as WebGLContextEvent).statusMessage || ''; };
    canvas.addEventListener('webglcontextcreationerror', onError);
    try {
      context = canvas.getContext('webgl2', { ...options, failIfMajorPerformanceCaveat: false });
      if (context && !context.isContextLost()) {
        return new THREE.WebGLRenderer({ canvas, context, ...options });
      }
      failures.push(detail || '浏览器没有返回 WebGL 2 上下文');
    } catch (error) {
      failures.push(error instanceof Error ? error.message : 'WebGL 2 初始化异常');
    } finally {
      canvas.removeEventListener('webglcontextcreationerror', onError);
    }
    context?.getExtension('WEBGL_lose_context')?.loseContext();
  }
  // Probe WebGL 1 only to explain why the current Three.js cannot use it.
  let legacy: WebGLRenderingContext | null = null;
  try { legacy = document.createElement('canvas').getContext('webgl'); } catch { /* diagnostic only */ }
  const capability = legacy ? '检测到 WebGL 1，但当前 3D 引擎需要 WebGL 2。' : '浏览器未提供可用的 WebGL 上下文。';
  legacy?.getExtension('WEBGL_lose_context')?.loseContext();
  throw new Error(capability + ' ' + [...new Set(failures)].join('；'));
}
