-- Uploaded files kept in the database, so they survive redeploys on hosts with a throwaway disk (Render).
create table stored_file (
    key        text primary key,
    content    bytea       not null,
    size_bytes bigint      not null,
    created_at timestamptz not null default now()
);
