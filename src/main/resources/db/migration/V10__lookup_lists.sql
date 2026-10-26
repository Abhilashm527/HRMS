-- Settings -> Configuration: lookup lists (SETTINGS_CONFIG_API.md section 6)

-- existing org tables get the extra columns the lists edit
alter table department
    add column cost_centre         text,
    add column default_location_id uuid references location (id);

alter table designation
    add column code           text,
    add column track          text,
    add column band_min       numeric,
    add column band_max       numeric,
    add column reports_to_id  uuid references designation (id),
    add column min_exp        numeric,
    add column approves       boolean not null default false;

alter table project
    add column owner_employee_id uuid references employee (id),
    add column status            text not null default 'Active';

-- new list tables
create table job_title (
    id             uuid primary key default gen_random_uuid(),
    org_id         uuid        not null references org (id),
    name           text        not null,
    code           text,
    designation_id uuid references designation (id),
    department_id  uuid references department (id),
    description    text,
    active         boolean     not null default true,
    created_at     timestamptz not null default now(),
    created_by     uuid,
    updated_at     timestamptz not null default now(),
    updated_by     uuid,
    version        bigint      not null default 0
);

create table job_level (
    id         uuid primary key default gen_random_uuid(),
    org_id     uuid        not null references org (id),
    code       text        not null,
    name       text        not null,
    min_exp    numeric,
    max_exp    numeric,
    approves   boolean     not null default false,
    active     boolean     not null default true,
    created_at timestamptz not null default now(),
    created_by uuid,
    updated_at timestamptz not null default now(),
    updated_by uuid,
    version    bigint      not null default 0,
    unique (org_id, code)
);

create table team (
    id               uuid primary key default gen_random_uuid(),
    org_id           uuid        not null references org (id),
    name             text        not null,
    department_id    uuid references department (id),
    lead_employee_id uuid references employee (id),
    active           boolean     not null default true,
    created_at       timestamptz not null default now(),
    created_by       uuid,
    updated_at       timestamptz not null default now(),
    updated_by       uuid,
    version          bigint      not null default 0
);

create table employment_type (
    id               uuid primary key default gen_random_uuid(),
    org_id           uuid        not null references org (id),
    name             text        not null,
    code             text,
    probation_months int,
    notice_days      int,
    leave_eligible   boolean     not null default false,
    payroll_eligible boolean     not null default false,
    active           boolean     not null default true,
    created_at       timestamptz not null default now(),
    created_by       uuid,
    updated_at       timestamptz not null default now(),
    updated_by       uuid,
    version          bigint      not null default 0
);

-- seed: what the spec lists explicitly
insert into employment_type (org_id, name, code, probation_months, notice_days, leave_eligible, payroll_eligible)
values ('00000000-0000-0000-0000-000000000001', 'Full-time',  'FT', 6, 60, true,  true),
       ('00000000-0000-0000-0000-000000000001', 'Part-time',  'PT', 3, 30, false, true),
       ('00000000-0000-0000-0000-000000000001', 'Contract',   'CT', 0, 15, false, true),
       ('00000000-0000-0000-0000-000000000001', 'Intern',     'IN', 0, 15, false, true),
       ('00000000-0000-0000-0000-000000000001', 'Consultant', 'CN', 0, 30, false, false);

insert into job_level (org_id, code, name, min_exp, max_exp, approves)
values ('00000000-0000-0000-0000-000000000001', 'L1', 'Associate', 0,   6,   false),
       ('00000000-0000-0000-0000-000000000001', 'L8', 'CXO',       120, 300, true);

-- the employee profile's custom fields (Settings > Onboarding > Custom fields, entity "Employee") live here
insert into config_section (org_id, area_key, key, name, sort, system, locked, show_in_portal)
values ('00000000-0000-0000-0000-000000000001', 'employee_profile', 'custom', 'Additional details', 90, false, false, false)
on conflict do nothing;

-- hardware and software starter lists
insert into config_list (org_id, module, list_key, items) values
('00000000-0000-0000-0000-000000000001', 'hardware', 'statuses', '[
  {"id":"b0000000-0000-4000-8000-000000000001","name":"Available","color":0,"assignable":true,"active":true},
  {"id":"b0000000-0000-4000-8000-000000000002","name":"Assigned","color":1,"assignable":false,"active":true},
  {"id":"b0000000-0000-4000-8000-000000000003","name":"In repair","color":2,"assignable":false,"active":true},
  {"id":"b0000000-0000-4000-8000-000000000004","name":"Not in use","color":5,"assignable":true,"active":true},
  {"id":"b0000000-0000-4000-8000-000000000005","name":"Retired","color":3,"assignable":false,"active":true}]'),
('00000000-0000-0000-0000-000000000001', 'software', 'statuses', '[
  {"id":"c0000000-0000-4000-8000-000000000001","name":"Active","color":0,"assignable":false,"active":true},
  {"id":"c0000000-0000-4000-8000-000000000002","name":"Unassigned","color":5,"assignable":true,"active":true},
  {"id":"c0000000-0000-4000-8000-000000000003","name":"Expiring soon","color":2,"assignable":false,"active":true},
  {"id":"c0000000-0000-4000-8000-000000000004","name":"Expired","color":3,"assignable":false,"active":true},
  {"id":"c0000000-0000-4000-8000-000000000005","name":"Not in use","color":4,"assignable":true,"active":true}]'),
('00000000-0000-0000-0000-000000000001', 'hardware', 'idformat', '[
  {"id":"b1000000-0000-4000-8000-000000000001","name":"Company","fmt":"CMP-{TYPE}-####","active":true},
  {"id":"b1000000-0000-4000-8000-000000000002","name":"Client","fmt":"CLT-{TYPE}-####","active":true},
  {"id":"b1000000-0000-4000-8000-000000000003","name":"Leased","fmt":"LSD-{TYPE}-####","active":true}]'),
('00000000-0000-0000-0000-000000000001', 'software', 'idformat', '[
  {"id":"c1000000-0000-4000-8000-000000000001","name":"Subscription","fmt":"SUB-{APP}-###","active":true},
  {"id":"c1000000-0000-4000-8000-000000000002","name":"Enterprise","fmt":"ENT-{APP}-###","active":true},
  {"id":"c1000000-0000-4000-8000-000000000003","name":"Perpetual","fmt":"PRP-{APP}-###","active":true}]'),
('00000000-0000-0000-0000-000000000001', 'onboarding', 'steps', '[
  {"id":"d0000000-0000-4000-8000-000000000001","order":1,"name":"Personal","desc":"Personal details of the new hire","kind":"Fields","active":true,"lock":true},
  {"id":"d0000000-0000-4000-8000-000000000002","order":2,"name":"Job","desc":"Role, team and joining details","kind":"Fields","active":true,"lock":true},
  {"id":"d0000000-0000-4000-8000-000000000003","order":3,"name":"Microsoft 365","desc":"Account and licence","kind":"Microsoft 365","active":true,"lock":false},
  {"id":"d0000000-0000-4000-8000-000000000004","order":4,"name":"Documents & invite","desc":"Documents to collect and the welcome email","kind":"Documents & invite","active":true,"lock":true}]');
