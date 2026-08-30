import type { GraphResult } from '@/api/intelligence';

export const CALL_GRAPH_NODE_WIDTH = 244;
export const CALL_GRAPH_NODE_HEIGHT = 86;

const CANVAS_PADDING = 56;
const COLUMN_GAP = 116;
const ROW_GAP = 34;
const LAYER_GAP = 168;
const MIN_CANVAS_WIDTH = 920;
const MIN_CANVAS_HEIGHT = 620;

export type PositionedGraphNode = GraphResult['nodes'][number] & {
  x: number;
  y: number;
};

export interface CallGraphLayout {
  nodes: PositionedGraphNode[];
  width: number;
  height: number;
}

interface LayerPlan {
  nodes: GraphResult['nodes'];
  rows: number;
  width: number;
  height: number;
  x: number;
}

function rowsForLayer(nodeCount: number) {
  return Math.max(1, Math.min(12, Math.ceil(Math.sqrt(nodeCount * 1.5))));
}

export function layoutCallGraph(nodes: GraphResult['nodes']): CallGraphLayout {
  if (!nodes.length) {
    return { nodes: [], width: MIN_CANVAS_WIDTH, height: MIN_CANVAS_HEIGHT };
  }

  const grouped = new Map<number, GraphResult['nodes']>();
  nodes.forEach((node) => {
    const layer = grouped.get(node.depth) ?? [];
    layer.push(node);
    grouped.set(node.depth, layer);
  });

  let cursorX = CANVAS_PADDING;
  const plans: LayerPlan[] = [...grouped.entries()]
    .sort(([leftDepth], [rightDepth]) => leftDepth - rightDepth)
    .map(([, layerNodes]) => {
      const rows = rowsForLayer(layerNodes.length);
      const columns = Math.ceil(layerNodes.length / rows);
      const width = columns * CALL_GRAPH_NODE_WIDTH + Math.max(0, columns - 1) * COLUMN_GAP;
      const height = rows * CALL_GRAPH_NODE_HEIGHT + Math.max(0, rows - 1) * ROW_GAP;
      const plan = { nodes: layerNodes, rows, width, height, x: cursorX };
      cursorX += width + LAYER_GAP;
      return plan;
    });

  const contentHeight = Math.max(...plans.map((plan) => plan.height));
  const height = Math.max(MIN_CANVAS_HEIGHT, contentHeight + CANVAS_PADDING * 2);
  const positioned = plans.flatMap((plan) => {
    const layerTop = Math.max(CANVAS_PADDING, (height - plan.height) / 2);
    return plan.nodes.map((node, index) => {
      const column = Math.floor(index / plan.rows);
      const row = index % plan.rows;
      return {
        ...node,
        x: plan.x + column * (CALL_GRAPH_NODE_WIDTH + COLUMN_GAP),
        y: layerTop + row * (CALL_GRAPH_NODE_HEIGHT + ROW_GAP),
      };
    });
  });

  return {
    nodes: positioned,
    width: Math.max(MIN_CANVAS_WIDTH, cursorX - LAYER_GAP + CANVAS_PADDING),
    height,
  };
}
