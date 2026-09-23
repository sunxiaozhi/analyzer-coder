import { describe, expect, it } from 'vitest';
import { recommendAtlasDisplay } from './atlasPerformance';

describe('recommendAtlasDisplay', () => {
  it('keeps the spatial view for capable devices', () => {
    expect(recommendAtlasDisplay({ memoryGb: 8, cores: 8 }).mode).toBe('3d');
  });

  it('chooses the lightweight view for constrained devices and reduced motion', () => {
    expect(recommendAtlasDisplay({ memoryGb: 4, cores: 8 }).mode).toBe('2d');
    expect(recommendAtlasDisplay({ memoryGb: 8, cores: 4 }).mode).toBe('2d');
    expect(recommendAtlasDisplay({ saveData: true }).mode).toBe('2d');
    expect(recommendAtlasDisplay({ reducedMotion: true }).mode).toBe('2d');
  });
});
