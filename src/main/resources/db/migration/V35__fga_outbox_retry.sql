-- OpenFGA outbox: keep retrying while OpenFGA is unavailable (e.g. a sleeping host answering 502), with growing gaps,
-- instead of giving up after 10 attempts. Only rows OpenFGA itself rejects are parked.
alter table fga_outbox add column next_attempt_at timestamptz;
alter table fga_outbox add column gave_up_at timestamptz;

-- Rows that gave up before this change were almost all "OpenFGA unreachable"; let the worker try them again.
update fga_outbox set attempts = 0, next_attempt_at = now() where processed_at is null;

drop index if exists idx_fga_outbox_pending;
create index idx_fga_outbox_pending on fga_outbox (created_at) where processed_at is null and gave_up_at is null;
