import * as THREE from 'three';

export interface ScreenPoint { x: number; y: number; depth: number; pixelsPerUnit: number }
export interface ScreenBox { left: number; right: number; top: number; bottom: number }

export function projectAtlasPoint(point: THREE.Vector3, camera: THREE.PerspectiveCamera, width: number, height: number): ScreenPoint | undefined {
  const view = point.clone().applyMatrix4(camera.matrixWorldInverse);
  if (view.z >= -camera.near) return;
  const projected = point.clone().project(camera);
  if (projected.z < -1 || projected.z > 1) return;
  return { x: (projected.x + 1) * width / 2, y: (1 - projected.y) * height / 2, depth: -view.z,
    pixelsPerUnit: height / (2 * Math.tan(camera.fov * Math.PI / 360) * -view.z) };
}
export function boxesOverlap(a: ScreenBox, b: ScreenBox, gap = 4) {
  return a.left < b.right + gap && a.right > b.left - gap && a.top < b.bottom + gap && a.bottom > b.top - gap;
}
export function nodeScreenSize(worldSize: number, scale: number, selected: boolean) {
  return THREE.MathUtils.clamp(worldSize * scale * (selected ? 1.3 : 1), 18, selected ? 120 : 90);
}
