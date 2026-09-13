import * as THREE from 'three';
import { expect, it } from 'vitest';
import { boxesOverlap, nodeScreenSize, projectAtlasPoint } from './atlasProjection';

it('projects camera rotation and zoom and rejects nodes behind the camera', () => {
  const camera = new THREE.PerspectiveCamera(45, 1.5, 1, 10000);
  const point = new THREE.Vector3(70, 40, 20);
  camera.position.set(0, 0, 500); camera.lookAt(0, 0, 0); camera.updateMatrixWorld(true);
  const initial = projectAtlasPoint(point, camera, 900, 600)!;
  camera.position.set(500, 0, 0); camera.lookAt(0, 0, 0); camera.updateMatrixWorld(true);
  expect(projectAtlasPoint(point, camera, 900, 600)!.x).not.toBeCloseTo(initial.x);
  camera.position.set(0, 0, 250); camera.lookAt(0, 0, 0); camera.updateMatrixWorld(true);
  expect(projectAtlasPoint(point, camera, 900, 600)!.pixelsPerUnit).toBeGreaterThan(initial.pixelsPerUnit);
  expect(projectAtlasPoint(new THREE.Vector3(0, 0, 600), camera, 900, 600)).toBeUndefined();
});
it('bounds oversized icons and checks label/icon overlap with padding', () => {
  expect(nodeScreenSize(86, 100, false)).toBe(90);
  expect(nodeScreenSize(86, 100, true)).toBe(120);
  expect(boxesOverlap({ left: 10, top: 10, right: 30, bottom: 30 }, { left: 31, top: 15, right: 50, bottom: 25 })).toBe(true);
  expect(boxesOverlap({ left: 10, top: 10, right: 30, bottom: 30 }, { left: 40, top: 40, right: 50, bottom: 50 })).toBe(false);
});
