-- Assets: hardware register (see ASSETS_BACKEND_GUIDE.md). V29-V31 are reserved for payroll.

-- the code must know "assigned", "in repair" ... whatever HR renames them
alter table asset_status add column system_key text
    check (system_key in ('AVAILABLE', 'ASSIGNED', 'IN_REPAIR', 'NOT_IN_USE', 'RETIRED', 'ACTIVE', 'UNASSIGNED', 'EXPIRING', 'EXPIRED'));
create unique index ux_asset_status_sys on asset_status (org_id, kind, system_key) where system_key is not null;
update asset_status set system_key = case lower(name)
    when 'available' then 'AVAILABLE' when 'assigned' then 'ASSIGNED' when 'in repair' then 'IN_REPAIR'
    when 'not in use' then 'NOT_IN_USE' when 'retired' then 'RETIRED' end
where kind = 'HARDWARE';
update asset_status set system_key = case lower(name)
    when 'active' then 'ACTIVE' when 'unassigned' then 'UNASSIGNED' when 'expiring soon' then 'EXPIRING'
    when 'expired' then 'EXPIRED' when 'not in use' then 'NOT_IN_USE' end
where kind = 'SOFTWARE';

-- in-app notifications get a type so the dashboard can list "System alerts" and mark them read
alter table notification_inbox add column type text;
create index notification_inbox_type on notification_inbox (employee_id, type) where read_at is null;

create table asset (
    id              uuid primary key default gen_random_uuid(),
    org_id          uuid        not null references org (id),
    tag             text        not null,
    type_id         uuid        not null references asset_type (id),
    model           text        not null,
    serial          text        not null,
    status_id       uuid        not null references asset_status (id),
    ownership_id    uuid        not null references asset_ownership (id),
    ownership_data  jsonb       not null default '{}',
    location_id     uuid references location (id),
    vendor          text,
    invoice_no      text,
    purchase_cost   bigint check (purchase_cost is null or purchase_cost >= 0),
    purchased_on    date,
    warranty_from   date,
    warranty_until  date,
    insurer         text,
    policy_no       text,
    insured_until   date,
    return_due      date,
    lost            boolean     not null default false,
    retired_on      date,
    retire_reason   text,
    notes           text,
    created_by      uuid,
    created_at      timestamptz not null default now(),
    updated_by      uuid,
    updated_at      timestamptz,
    deleted_at      timestamptz,
    version         bigint      not null default 0,
    unique (org_id, tag)
);
create unique index ux_asset_serial on asset (org_id, type_id, lower(trim(serial))) where deleted_at is null;
create index ix_asset_status on asset (org_id, status_id) where deleted_at is null;

create table asset_assignment (
    id               uuid primary key default gen_random_uuid(),
    asset_id         uuid        not null references asset (id),
    employee_id      uuid        not null references employee (id),
    assigned_on      date        not null,
    returned_on      date,
    return_condition text check (return_condition in ('GOOD', 'DAMAGED', 'MISSING')),
    request_id       uuid,
    assigned_by      uuid,
    returned_by      uuid,
    note             text,
    created_at       timestamptz not null default now()
);
create unique index ux_asset_open_assignment on asset_assignment (asset_id) where returned_on is null;
create index ix_assignment_emp on asset_assignment (employee_id) where returned_on is null;

create table asset_event (
    id          bigserial primary key,
    org_id      uuid        not null,
    asset_id    uuid        not null references asset (id),
    kind        text        not null check (kind in ('CREATED', 'EDITED', 'ASSIGNED', 'RECOVERED', 'REPAIR_OUT', 'REPAIR_IN', 'STATUS', 'RETIRED', 'FILE', 'NOTE', 'IMPORTED')),
    title       text        not null,
    description text,
    prev_value  text,
    new_value   text,
    note        text,
    employee_id uuid,
    actor_id    uuid,
    at          timestamptz not null default now()
);
create index ix_asset_event on asset_event (asset_id, at desc);
create index ix_asset_event_org on asset_event (org_id, at desc);

create table asset_file (
    id          uuid primary key default gen_random_uuid(),
    asset_id    uuid        not null references asset (id),
    name        text        not null,
    size_bytes  bigint      not null,
    mime        text,
    storage_key text        not null,
    uploaded_by uuid,
    uploaded_at timestamptz not null default now(),
    deleted_at  timestamptz
);
create index ix_asset_file on asset_file (asset_id) where deleted_at is null;
