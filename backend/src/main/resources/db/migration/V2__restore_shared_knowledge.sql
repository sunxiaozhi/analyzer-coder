-- Existing knowledge keeps its original branch. Scope is versioned with the card.
CREATE TABLE knowledge_branch_scopes (
    card_id uuid PRIMARY KEY REFERENCES knowledge_cards(id) ON DELETE CASCADE,
    mode text NOT NULL CHECK (mode IN ('ALL_BRANCHES','SELECTED_BRANCHES')),
    branch_ids uuid[] NOT NULL,
    CHECK ((mode='ALL_BRANCHES' AND cardinality(branch_ids)=0)
        OR (mode='SELECTED_BRANCHES' AND cardinality(branch_ids)>0))
);
CREATE TABLE knowledge_branch_scope_history (
    card_id uuid NOT NULL,
    revision integer NOT NULL,
    mode text NOT NULL,
    branch_ids uuid[] NOT NULL,
    PRIMARY KEY(card_id,revision),
    FOREIGN KEY(card_id,revision) REFERENCES knowledge_card_revisions(card_id,revision) ON DELETE CASCADE
);
INSERT INTO knowledge_branch_scopes SELECT id,'SELECTED_BRANCHES',ARRAY[branch_id] FROM knowledge_cards;
INSERT INTO knowledge_branch_scope_history SELECT card_id,revision,'SELECTED_BRANCHES',ARRAY[branch_id] FROM knowledge_card_revisions;
CREATE FUNCTION capture_knowledge_branch_scope() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO knowledge_branch_scopes VALUES(NEW.id,'SELECTED_BRANCHES',ARRAY[NEW.branch_id]) ON CONFLICT DO NOTHING;
    INSERT INTO knowledge_branch_scope_history SELECT NEW.id,NEW.revision,mode,branch_ids FROM knowledge_branch_scopes WHERE card_id=NEW.id
    ON CONFLICT(card_id,revision) DO NOTHING;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_knowledge_scope_revision AFTER INSERT OR UPDATE OF revision ON knowledge_cards
FOR EACH ROW EXECUTE FUNCTION capture_knowledge_branch_scope();
CREATE FUNCTION knowledge_applies_to_branch(card uuid, branch uuid, rev integer DEFAULT NULL)
RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT EXISTS(SELECT 1 FROM knowledge_branch_scopes s WHERE s.card_id=card AND (s.mode='ALL_BRANCHES' OR branch=ANY(s.branch_ids)))
      AND (rev IS NULL OR EXISTS(SELECT 1 FROM knowledge_branch_scope_history h WHERE h.card_id=card AND h.revision=rev AND (h.mode='ALL_BRANCHES' OR branch=ANY(h.branch_ids))));
$$;
ALTER TABLE knowledge_code_refs ADD COLUMN branch_id uuid;
UPDATE knowledge_code_refs r SET branch_id=k.branch_id FROM knowledge_cards k WHERE k.id=r.card_id;
ALTER TABLE knowledge_code_refs ALTER COLUMN branch_id SET NOT NULL;
CREATE FUNCTION assign_knowledge_reference_branch() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.branch_id IS NULL THEN
        SELECT branch_id INTO NEW.branch_id FROM code_chunks WHERE id=NEW.chunk_id AND repo_id=NEW.repo_id;
        IF NEW.branch_id IS NULL THEN SELECT branch_id INTO NEW.branch_id FROM knowledge_cards WHERE id=NEW.card_id; END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_knowledge_reference_branch BEFORE INSERT ON knowledge_code_refs
FOR EACH ROW EXECUTE FUNCTION assign_knowledge_reference_branch();
