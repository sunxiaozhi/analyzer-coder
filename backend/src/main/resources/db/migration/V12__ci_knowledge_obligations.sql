UPDATE knowledge_cards
SET obligations_payload = obligations_payload
    || '{"prohibitedPathPatterns":[],"knowledgeUpdateRequired":false}'::jsonb
WHERE NOT (obligations_payload ? 'prohibitedPathPatterns')
   OR NOT (obligations_payload ? 'knowledgeUpdateRequired');

UPDATE knowledge_card_revisions
SET obligations_payload = obligations_payload
    || '{"prohibitedPathPatterns":[],"knowledgeUpdateRequired":false}'::jsonb
WHERE NOT (obligations_payload ? 'prohibitedPathPatterns')
   OR NOT (obligations_payload ? 'knowledgeUpdateRequired');

ALTER TABLE knowledge_cards ALTER COLUMN obligations_payload SET DEFAULT
    '{"requiredTests":[],"requiredApproverAccountIds":[],"instructions":[],"prohibitedPathPatterns":[],"knowledgeUpdateRequired":false}'::jsonb;
ALTER TABLE knowledge_card_revisions ALTER COLUMN obligations_payload SET DEFAULT
    '{"requiredTests":[],"requiredApproverAccountIds":[],"instructions":[],"prohibitedPathPatterns":[],"knowledgeUpdateRequired":false}'::jsonb;

ALTER TABLE knowledge_cards DROP CONSTRAINT chk_knowledge_obligations_payload;
ALTER TABLE knowledge_cards ADD CONSTRAINT chk_knowledge_obligations_payload CHECK (
    jsonb_typeof(obligations_payload)='object'
    AND jsonb_typeof(obligations_payload->'requiredTests')='array'
    AND jsonb_typeof(obligations_payload->'requiredApproverAccountIds')='array'
    AND jsonb_typeof(obligations_payload->'instructions')='array'
    AND jsonb_typeof(obligations_payload->'prohibitedPathPatterns')='array'
    AND jsonb_typeof(obligations_payload->'knowledgeUpdateRequired')='boolean'
);

ALTER TABLE knowledge_card_revisions ADD CONSTRAINT chk_knowledge_revision_obligations_payload CHECK (
    jsonb_typeof(obligations_payload)='object'
    AND jsonb_typeof(obligations_payload->'requiredTests')='array'
    AND jsonb_typeof(obligations_payload->'requiredApproverAccountIds')='array'
    AND jsonb_typeof(obligations_payload->'instructions')='array'
    AND jsonb_typeof(obligations_payload->'prohibitedPathPatterns')='array'
    AND jsonb_typeof(obligations_payload->'knowledgeUpdateRequired')='boolean'
);

COMMENT ON COLUMN knowledge_cards.obligations_payload IS
    '命中知识后要求的测试、审批、禁止路径、知识同步和补充开发动作';
