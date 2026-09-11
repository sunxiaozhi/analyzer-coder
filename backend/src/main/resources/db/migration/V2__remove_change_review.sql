-- 变更审查已退出最小产品范围。按依赖顺序移除审查结果、审查记录及其触发函数。
DROP TABLE IF EXISTS task_review_feedback;
DROP TABLE IF EXISTS task_review_outcomes;
DROP TABLE IF EXISTS task_reviews;

DROP FUNCTION IF EXISTS prevent_task_review_outcome_update();
DROP FUNCTION IF EXISTS prevent_terminal_task_review_update();
