create table org (
    id         uuid primary key default gen_random_uuid(),
    name       text        not null,
    legal_name text,
    country    text,
    timezone   text        not null default 'UTC',
    created_at timestamptz not null default now(),
    created_by uuid,
    updated_at timestamptz not null default now(),
    updated_by uuid,
    version    bigint      not null default 0
);

-- single-tenant today: one fixed org, referenced by hrms.default-org-id
insert into org (id, name, legal_name, country, timezone)
values ('00000000-0000-0000-0000-000000000001', 'Ivoyant', 'Ivoyant', 'IN', 'Asia/Kolkata');

create table location (
    id         uuid primary key default gen_random_uuid(),
    org_id     uuid        not null references org (id),
    name       text        not null,
    city       text,
    country    text,
    timezone   text,
    active     boolean     not null default true,
    created_at timestamptz not null default now(),
    created_by uuid,
    updated_at timestamptz not null default now(),
    updated_by uuid,
    version    bigint      not null default 0,
    unique (org_id, name)
);

create table department (
    id               uuid primary key default gen_random_uuid(),
    org_id           uuid        not null references org (id),
    name             text        not null,
    code             text        not null,
    head_employee_id uuid,
    parent_id        uuid references department (id),
    active           boolean     not null default true,
    created_at       timestamptz not null default now(),
    created_by       uuid,
    updated_at       timestamptz not null default now(),
    updated_by       uuid,
    version          bigint      not null default 0,
    unique (org_id, code)
);

create table designation (
    id         uuid primary key default gen_random_uuid(),
    org_id     uuid        not null references org (id),
    title      text        not null,
    level      int,
    active     boolean     not null default true,
    created_at timestamptz not null default now(),
    created_by uuid,
    updated_at timestamptz not null default now(),
    updated_by uuid,
    version    bigint      not null default 0,
    unique (org_id, title)
);

create table project (
    id         uuid primary key default gen_random_uuid(),
    org_id     uuid        not null references org (id),
    name       text        not null,
    code       text        not null,
    client     text,
    active     boolean     not null default true,
    created_at timestamptz not null default now(),
    created_by uuid,
    updated_at timestamptz not null default now(),
    updated_by uuid,
    version    bigint      not null default 0,
    unique (org_id, code)
);
