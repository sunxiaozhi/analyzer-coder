CREATE TABLE knowledge_card_revisions (
    card_id UUID NOT NULL REFERENCES knowledge_cards(id) ON DELETE CASCADE,
    revision INTEGER NOT NULL,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    card_type VARCHAR(40) NOT NULL,
    content TEXT NOT NULL,
    tags TEXT[] NOT NULL DEFAULT '{}',
    status VARCHAR(30) NOT NULL,
    changed_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (card_id, revision)
);

INSERT INTO knowledge_card_revisions(card_id,revision,repo_id,title,card_type,content,tags,status,changed_by,changed_at)
SELECT id,revision,repo_id,title,card_type,content,tags,status,updated_by,updated_at
FROM knowledge_cards
ON CONFLICT DO NOTHING;

CREATE OR REPLACE FUNCTION capture_knowledge_card_revision() RETURNS trigger AS $$
BEGIN
    INSERT INTO knowledge_card_revisions(card_id,revision,repo_id,title,card_type,content,tags,status,changed_by,changed_at)
    VALUES (NEW.id,NEW.revision,NEW.repo_id,NEW.title,NEW.card_type,NEW.content,NEW.tags,NEW.status,NEW.updated_by,NEW.updated_at)
    ON CONFLICT (card_id,revision) DO UPDATE SET
      title=EXCLUDED.title,card_type=EXCLUDED.card_type,content=EXCLUDED.content,tags=EXCLUDED.tags,
      status=EXCLUDED.status,changed_by=EXCLUDED.changed_by,changed_at=EXCLUDED.changed_at;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_knowledge_card_revision ON knowledge_cards;
CREATE TRIGGER trg_knowledge_card_revision
AFTER INSERT OR UPDATE OF title,card_type,content,tags,status,revision ON knowledge_cards
FOR EACH ROW EXECUTE FUNCTION capture_knowledge_card_revision();

CREATE INDEX idx_knowledge_card_revisions_repo_card
ON knowledge_card_revisions(repo_id,card_id,revision DESC);
