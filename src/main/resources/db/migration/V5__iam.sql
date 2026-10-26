create table app_user (
    id               uuid primary key default gen_random_uuid(),
    org_id           uuid        not null references org (id),
    employee_id      uuid        not null unique references employee (id),
    external_subject text        unique, -- Entra oid
    status           text        not null default 'ACTIVE',
    last_login_at    timestamptz,
    created_at       timestamptz not null default now(),
    created_by       uuid,
    updated_at       timestamptz not null default now(),
    updated_by       uuid,
    version          bigint      not null default 0
);

create table role (
    id          uuid primary key default gen_random_uuid(),
    org_id      uuid        not null references org (id),
    key         text        not null,
    name        text        not null,
    description text,
    system      boolean     not null default false,
    created_at  timestamptz not null default now(),
    created_by  uuid,
    updated_at  timestamptz not null default now(),
    updated_by  uuid,
    version     bigint      not null default 0,
    unique (org_id, key)
);

create table user_role (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid not null references app_user (id) on delete cascade,
    role_id    uuid not null references role (id) on delete cascade,
    scope      text not null default 'ORGANIZATION'
        check (scope in ('OWN', 'TEAM', 'DEPARTMENT', 'LOCATION', 'ORGANIZATION')),
    scope_ref  uuid
);
create unique index uq_user_role
    on user_role (user_id, role_id, scope, coalesce(scope_ref, '00000000-0000-0000-0000-000000000000'));

create table permission (
    id     uuid primary key default gen_random_uuid(),
    module text not null,
    action text not null,
    unique (module, action)
);

create table role_permission (
    role_id       uuid not null references role (id) on delete cascade,
    permission_id uuid not null references permission (id) on delete cascade,
    scope         text not null default 'ORGANIZATION'
        check (scope in ('OWN', 'TEAM', 'DEPARTMENT', 'LOCATION', 'ORGANIZATION')),
    primary key (role_id, permission_id, scope)
);

create table area_owner (
    id         uuid primary key default gen_random_uuid(),
    org_id     uuid        not null references org (id),
    area_key   text        not null,
    user_id    uuid        not null references app_user (id) on delete cascade,
    created_at timestamptz not null default now(),
    created_by uuid,
    updated_at timestamptz not null default now(),
    updated_by uuid,
    version    bigint      not null default 0,
    unique (org_id, area_key, user_id)
);

create table delegation (
    id           uuid primary key default gen_random_uuid(),
    org_id       uuid        not null references org (id),
    from_user_id uuid        not null references app_user (id),
    to_user_id   uuid        not null references app_user (id),
    module       text        not null,
    starts_at    timestamptz not null,
    ends_at      timestamptz not null,
    reason       text,
    created_at   timestamptz not null default now(),
    created_by   uuid,
    updated_at   timestamptz not null default now(),
    updated_by   uuid,
    version      bigint      not null default 0,
    check (ends_at > starts_at)
);

insert into role (org_id, key, name, description, system)
select '00000000-0000-0000-0000-000000000001', r.key, r.name, r.description, true
from (values
    ('super_admin', 'Super admin', 'Full access, including configuration and access control'),
    ('hr_admin',    'HR admin',    'Manages employees, policies and HR configuration'),
    ('hr_ops',      'HR operations', 'Day-to-day HR operations'),
    ('finance',     'Finance',     'Payroll and finance access'),
    ('it_admin',    'IT admin',    'Assets, accounts and provisioning'),
    ('manager',     'Manager',     'Manages a team'),
    ('employee',    'Employee',    'Default role for every employee')
) as r(key, name, description);
