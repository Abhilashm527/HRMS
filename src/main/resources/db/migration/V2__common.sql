create extension if not exists pgcrypto;

create table fga_outbox (
    id           uuid primary key default gen_random_uuid(),
    op           text        not null check (op in ('WRITE', 'DELETE')),
    tuple        jsonb       not null,
    created_at   timestamptz not null default now(),
    processed_at timestamptz,
    attempts     int         not null default 0,
    last_error   text
);
create index idx_fga_outbox_pending on fga_outbox (created_at) where processed_at is null;

create table audit_log (
    id        uuid primary key default gen_random_uuid(),
    org_id    uuid,
    actor_id  uuid,
    module    text        not null,
    entity    text        not null,
    entity_id text,
    action    text        not null,
    before    jsonb,
    after     jsonb,
    at        timestamptz not null default now()
);
create index idx_audit_log_entity on audit_log (entity, entity_id);
create index idx_audit_log_at on audit_log (at desc);
