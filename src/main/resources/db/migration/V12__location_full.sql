-- Locations (see LOCATIONS_BACKEND_GUIDE.md)

-- holiday calendars (the calendar module owns them; created here so a location can point at one)
create table holiday_calendar (
    id         uuid primary key default gen_random_uuid(),
    org_id     uuid        not null references org (id),
    name       text        not null,          -- 'Karnataka', 'National holidays only'
    state      text,                          -- null = national only
    active     boolean     not null default true,
    created_at timestamptz not null default now(),
    created_by uuid,
    updated_at timestamptz not null default now(),
    updated_by uuid,
    version    bigint      not null default 0,
    unique (org_id, name)
);

create table holiday (
    id          uuid primary key default gen_random_uuid(),
    calendar_id uuid not null references holiday_calendar (id) on delete cascade,
    date        date not null,
    name        text not null,
    kind        text not null default 'NATIONAL' check (kind in ('NATIONAL', 'REGIONAL', 'OPTIONAL')),
    unique (calendar_id, date)
);

alter table location
    add column code                text,
    add column type                text not null default 'BRANCH_OFFICE'
        check (type in ('HEAD_OFFICE', 'BRANCH_OFFICE', 'CO_WORKING', 'WAREHOUSE', 'REMOTE_HUB')),
    add column head_employee_id    uuid references employee (id) on delete set null,
    add column address_line        text,
    add column state               text,
    add column postal_code         text,
    add column phone               text,
    add column email               text,
    add column week_pattern        text not null default 'MON_FRI'
        check (week_pattern in ('MON_FRI', 'MON_SAT', 'MON_FRI_ALT_SAT')),
    add column start_time          time not null default '09:30',
    add column end_time            time not null default '18:30',
    add column grace_minutes       int  not null default 15 check (grace_minutes between 0 and 120),
    add column holiday_calendar_id uuid references holiday_calendar (id),
    add column attendance_methods  text[] not null default '{WEB}'
        check (cardinality(attendance_methods) >= 1
               and attendance_methods <@ array['BIOMETRIC', 'WEB', 'MOBILE_GPS', 'QR']),
    add column geo_radius_m        int check (geo_radius_m between 25 and 5000),
    add column latitude            numeric(9, 6),
    add column longitude           numeric(9, 6),
    add column wfh_allowed         boolean not null default true,
    add column lwf_applicable      boolean not null default false,
    add column esi_notified        boolean not null default true,
    add column shops_estab_reg_no  text,
    add column gstin               text,
    add column seat_capacity       int check (seat_capacity >= 0),
    add column asset_store         boolean not null default false;

-- backfill the rows that exist already
update location set code = upper(left(regexp_replace(name, '[^A-Za-z]', '', 'g'), 3)) || '-01' where code is null;
alter table location alter column code set not null;
create unique index ux_location_code on location (org_id, upper(code));
alter table location drop constraint if exists location_org_id_name_key;   -- case-sensitive; replaced below
create unique index ux_location_name on location (org_id, lower(name));
create index idx_location_active on location (org_id, active);
