-- Recovery/verification ledger for restart-safe Hermes execution.
-- A Hermes success is only eligible for review.  A task becomes completed only
-- when Core persists an explicit accepted reviewer decision for the same run.

CREATE TABLE IF NOT EXISTS reviewer_acceptances (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE RESTRICT,
    reviewer_agent_id UUID NOT NULL REFERENCES agents(id) ON DELETE RESTRICT,
    reviewed_run_id TEXT NOT NULL CHECK (char_length(trim(reviewed_run_id)) BETWEEN 1 AND 512),
    decision TEXT NOT NULL CHECK (decision IN ('accepted', 'changes_requested')),
    evidence_summary TEXT NOT NULL CHECK (char_length(trim(evidence_summary)) BETWEEN 1 AND 4000),
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    idempotency_key TEXT NOT NULL UNIQUE
        CHECK (idempotency_key ~ '^reviewer-acceptance:v1:[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS reviewer_acceptances_task_created_idx
ON reviewer_acceptances(task_id, created_at DESC);

CREATE INDEX IF NOT EXISTS reviewer_acceptances_reviewer_created_idx
ON reviewer_acceptances(reviewer_agent_id, created_at DESC);

-- Review history is audit evidence.  Corrections are represented by a later
-- decision rather than mutating or deleting the original reviewer record.
CREATE OR REPLACE FUNCTION cyclone_reject_reviewer_acceptance_change()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'reviewer_acceptances rows are append-only'
        USING ERRCODE = '55000';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS reviewer_acceptances_immutable ON reviewer_acceptances;
CREATE TRIGGER reviewer_acceptances_immutable
BEFORE UPDATE OR DELETE ON reviewer_acceptances
FOR EACH ROW EXECUTE FUNCTION cyclone_reject_reviewer_acceptance_change();
