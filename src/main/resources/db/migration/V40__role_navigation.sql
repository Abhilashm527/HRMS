-- What each role sees in the app's menus: whole menus, profile tabs and Settings / Configuration pages.
-- A missing row means the default (see NavAccessService); rows only record what an admin changed.
create table role_nav (
    role_id    uuid        not null references role (id) on delete cascade,
    area       text        not null check (area in ('menu', 'tab', 'page')),
    key        text        not null check (key ~ '^[a-z0-9/_:-]{1,80}$'),
    value      text        not null check (value in ('on', 'off', 'edit', 'view', 'hidden')),
    updated_at timestamptz not null default now(),
    updated_by uuid,
    primary key (role_id, area, key)
);
