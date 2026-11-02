-- Policies: versioned company policies that employees read and acknowledge.
create table policy_category (
    id         uuid primary key default gen_random_uuid(),
    org_id     uuid        not null references org (id),
    name       text        not null check (length(trim(name)) between 1 and 60),
    color_hex  text        not null default '#2563EB' check (color_hex ~ '^#[0-9A-Fa-f]{6}$'),
    sort       int         not null default 0,
    created_at timestamptz not null default now()
);
create unique index ux_policy_category_name on policy_category (org_id, lower(name));

create table policy (
    id                   uuid primary key default gen_random_uuid(),
    org_id               uuid        not null references org (id),
    name                 text        not null check (length(trim(name)) between 1 and 120),
    category_id          uuid        not null references policy_category (id),
    owner_id             uuid,
    approver_id          uuid,
    review_cycle_months  int         not null default 12 check (review_cycle_months in (3, 6, 12, 24, 36)),
    ack_required         boolean     not null default true,
    last_reviewed_on     date,
    archived_at          timestamptz,
    created_by           uuid,
    created_at           timestamptz not null default now()
);
create unique index ux_policy_name on policy (org_id, lower(name));

-- Published versions; the text never changes once published.
create table policy_version (
    id            uuid primary key default gen_random_uuid(),
    policy_id     uuid        not null references policy (id) on delete cascade,
    version       text        not null check (version ~ '^[0-9]+\.[0-9]+$'),
    sections      jsonb       not null,
    note          text,
    major         boolean     not null default false,
    requires_ack  boolean     not null default true,
    published_by  uuid,
    published_at  timestamptz not null default now(),
    unique (policy_id, version)
);

-- At most one draft per policy: the next version being written.
create table policy_draft (
    policy_id     uuid primary key references policy (id) on delete cascade,
    sections      jsonb       not null,
    note          text,
    in_review     boolean     not null default false,
    approved      boolean     not null default false,
    started_by    uuid,
    updated_by    uuid,
    updated_at    timestamptz not null default now()
);

create table policy_event (
    id         uuid primary key default gen_random_uuid(),
    policy_id  uuid        not null references policy (id) on delete cascade,
    type       text        not null,
    text       text        not null,
    version    text,
    actor_id   uuid,
    at         timestamptz not null default now()
);
create index idx_policy_event on policy_event (policy_id, at desc);

create table policy_ack (
    version_id  uuid        not null references policy_version (id) on delete cascade,
    employee_id uuid        not null,
    at          timestamptz not null default now(),
    primary key (version_id, employee_id)
);
