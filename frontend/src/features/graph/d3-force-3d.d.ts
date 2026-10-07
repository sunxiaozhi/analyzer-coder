// d3-force-3d has no bundled declarations; the public subset used by our worker.
declare module 'd3-force-3d' {
  interface Force<N> { (alpha: number): void; initialize?: (nodes: N[], ...args: unknown[]) => void }
  interface Simulation<N> {
    stop(): this; tick(iterations?: number): this; alphaDecay(value: number): this; velocityDecay(value: number): this;
    force(name: string, force: Force<N>): this;
  }
  interface PositionForce<N> extends Force<N> { strength(value: number): this }
  interface ChargeForce<N> extends Force<N> { strength(value: number): this; distanceMax(value: number): this }
  interface CollisionForce<N> extends Force<N> { strength(value: number): this; iterations(value: number): this }
  interface LinkForce<N, L> extends Force<N> { id(accessor: (node: N) => string): this; distance(accessor: (link: L) => number): this; strength(value: number): this }
  export function forceSimulation<N>(nodes: N[], dimensions: number): Simulation<N>;
  export function forceX<N>(accessor: (node: N) => number): PositionForce<N>;
  export function forceY<N>(accessor: (node: N) => number): PositionForce<N>;
  export function forceZ<N>(accessor: (node: N) => number): PositionForce<N>;
  export function forceManyBody<N>(): ChargeForce<N>;
  export function forceCollide<N>(accessor: (node: N) => number): CollisionForce<N>;
  export function forceLink<N, L>(links: L[]): LinkForce<N, L>;
}
