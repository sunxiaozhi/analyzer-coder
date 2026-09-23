export interface AtlasDeviceHints {
  memoryGb?: number;
  cores?: number;
  saveData?: boolean;
  reducedMotion?: boolean;
}

export interface AtlasDisplayRecommendation {
  mode: '3d' | '2d';
  reason: string;
}

export function recommendAtlasDisplay(hints: AtlasDeviceHints): AtlasDisplayRecommendation {
  if (hints.reducedMotion) return { mode: '2d', reason: '系统偏好减少动态效果' };
  if (hints.saveData) return { mode: '2d', reason: '设备已开启节省资源模式' };
  if (hints.memoryGb !== undefined && hints.memoryGb <= 4) {
    return { mode: '2d', reason: '设备内存较低' };
  }
  if (hints.cores !== undefined && hints.cores <= 4) {
    return { mode: '2d', reason: '设备处理能力较低' };
  }
  return { mode: '3d', reason: '' };
}

export function browserAtlasDisplayRecommendation(): AtlasDisplayRecommendation {
  if (typeof window === 'undefined' || typeof navigator === 'undefined') {
    return { mode: '3d', reason: '' };
  }
  const device = navigator as Navigator & {
    deviceMemory?: number;
    connection?: { saveData?: boolean };
  };
  return recommendAtlasDisplay({
    memoryGb: device.deviceMemory,
    cores: device.hardwareConcurrency,
    saveData: device.connection?.saveData,
    reducedMotion: window.matchMedia?.('(prefers-reduced-motion: reduce)').matches,
  });
}
