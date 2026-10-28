-- Assets: software licences, seats, requests (see ASSETS_BACKEND_GUIDE.md).

alter table asset_category add column default_department_ids uuid[] not null default '{}';

-- licence thresholds (days before renewal that count as "renewal due"; seat use below this percent is "underused")
alter table org_settings add column assets_renewal_due_days int not null default 45 check (assets_renewal_due_days between 1 and 365);
alter table org_settings add column assets_underused_pct int not null default 60 check (assets_underused_pct between 1 and 100);

create table license (
    id                uuid primary key default gen_random_uuid(),
    org_id            uuid        not null references org (id),
    lid               text        not null,
    name              text        not null,
    vendor            text,
    category_id       uuid        not null references asset_category (id),
    type_id           uuid        not null references asset_type (id),
    seats             int check (seats is null or seats >= 0),
    cost_per_seat     bigint      not null default 0 check (cost_per_seat >= 0),
    term_start        date,
    renews_on         date,
    auto_renew        boolean     not null default false,
    owner_employee_id uuid        not null references employee (id),
    status_id         uuid references asset_status (id),
    notes             text,
    created_by        uuid,
    created_at        timestamptz not null default now(),
    updated_by        uuid,
    updated_at        timestamptz,
    deleted_at        timestamptz,
    version           bigint      not null default 0,
    unique (org_id, lid)
);
create unique index ux_license_name on license (org_id, lower(name), type_id) where deleted_at is null;

create table asset_request (
    id                  uuid primary key default gen_random_uuid(),
    org_id              uuid        not null references org (id),
    employee_id         uuid        not null references employee (id),
    kind                text        not null check (kind in ('HARDWARE', 'SOFTWARE')),
    asset_type_id       uuid references asset_type (id),
    license_id          uuid references license (id),
    reason              text        not null,
    source              text        not null default 'EMPLOYEE' check (source in ('EMPLOYEE', 'ONBOARDING', 'ADMIN')),
    status              text        not null default 'PENDING' check (status in ('PENDING', 'APPROVED', 'REJECTED', 'FULFILLED', 'CANCELLED')),
    approval_step       smallint    not null default 2,
    decided_by          uuid,
    decided_at          timestamptz,
    decision_note       text,
    fulfilled_asset_id  uuid references asset (id),
    fulfilled_seat_id   uuid,
    fulfilled_at        timestamptz,
    due_on              date,
    created_at          timestamptz not null default now(),
    version             bigint      not null default 0,
    check ((kind = 'HARDWARE' and asset_type_id is not null) or (kind = 'SOFTWARE' and license_id is not null))
);
create index ix_asset_req_status on asset_request (org_id, status, kind);
create index ix_asset_req_emp on asset_request (employee_id, status);

create table license_seat (
    id           uuid primary key default gen_random_uuid(),
    license_id   uuid        not null references license (id),
    employee_id  uuid        not null references employee (id),
    assigned_on  date        not null,
    revoked_on   date,
    request_id   uuid references asset_request (id),
    assigned_by  uuid,
    revoked_by   uuid,
    last_used_on date
);
create unique index ux_seat_open on license_seat (license_id, employee_id) where revoked_on is null;
create index ix_seat_emp on license_seat (employee_id) where revoked_on is null;
alter table asset_request add constraint fk_request_seat foreign key (fulfilled_seat_id) references license_seat (id);

alter table asset_assignment add constraint fk_assign_req foreign key (request_id) references asset_request (id);

create table license_event (
    id          bigserial primary key,
    org_id      uuid        not null,
    license_id  uuid        not null references license (id),
    kind        text        not null check (kind in ('CREATED', 'EDITED', 'SEAT_ASSIGNED', 'SEAT_REVOKED', 'SEATS_CHANGED', 'RENEWED', 'AUTO_RENEW', 'STATUS', 'IMPORTED')),
    title       text        not null,
    description text,
    prev_value  text,
    new_value   text,
    employee_id uuid,
    actor_id    uuid,
    at          timestamptz not null default now()
);
create index ix_license_event on license_event (license_id, at desc);
create index ix_license_event_org on license_event (org_id, at desc);

create table license_usage_month (
    org_id      uuid    not null,
    month       date    not null,
    category_id uuid    not null,
    total_seats int     not null,
    used_seats  int     not null,
    primary key (org_id, month, category_id)
);
