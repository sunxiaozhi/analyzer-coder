-- These rows are produced by symbol-token string matching during indexing.
-- They are deliberately kept separate from published CodeGraph CLI artifacts.

ALTER TABLE code_graph_edges RENAME TO heuristic_call_edges;
ALTER INDEX idx_code_graph_edges_source RENAME TO idx_heuristic_call_edges_source;
ALTER INDEX idx_code_graph_edges_target RENAME TO idx_heuristic_call_edges_target;

COMMENT ON TABLE heuristic_call_edges IS
    '索引阶段按“符号名+左括号”字符串规则推断的启发式调用候选，不是 CodeGraph CLI 关系';
