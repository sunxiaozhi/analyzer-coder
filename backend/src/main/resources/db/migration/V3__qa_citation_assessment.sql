-- Citation status describes mechanical coverage only. It must not imply that
-- cited evidence semantically entails the answer. SUPPORTED remains readable
-- for historical rows created before this distinction was introduced.

ALTER TABLE qa_conversations
    DROP CONSTRAINT IF EXISTS chk_qa_conversations_evidence_status;

ALTER TABLE qa_conversations
    ADD CONSTRAINT chk_qa_conversations_evidence_status CHECK (
        evidence_status IN (
            'CITATION_COMPLETE',
            'CITATION_INCOMPLETE',
            'SUPPORTED',
            'DEGRADED',
            'MODEL_OUTPUT_REJECTED',
            'INSUFFICIENT',
            'UNKNOWN'
        )
    );

COMMENT ON COLUMN qa_conversations.evidence_status IS
    '回答证据状态；CITATION_* 仅表示引用编号与段落覆盖，不表示语义蕴含已验证';
