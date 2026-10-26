-- Settings -> Configuration: Fields & forms (see SETTINGS_CONFIG_API.md)

-- 1. area metadata
alter table config_area
    add column description text,
    add column icon        text,
    add column sort        int not null default 0,
    add column perm_module text;

update config_area set sort = s.sort, icon = s.icon, perm_module = s.pm, description = s.d
from (values
 ('employee_profile',10,'users','employee','Sections and fields on every employee profile. Changes show on My profile and on admin profiles straight away.'),
 ('onboarding',20,'userPlus','recruiting','Steps and fields on the Add new hire form.'),
 ('leave',30,'cal','leave','Extra fields on leave requests — shown when applying and on the request.'),
 ('attendance',40,'clock','attendance','Extra fields on attendance regularisation requests.'),
 ('requests',50,'dList','employee','Extra fields on letter, reimbursement and helpdesk requests.'),
 ('assets',60,'monitor','assets','Extra fields on asset requests from employees.'),
 ('recruiting',70,'target','recruiting','Details on each candidate — open a card in Recruiting → Details.'),
 ('payroll',80,'wallet','payroll','Per-employee payroll details — on the payslip, the profile and Payslips & tax.'),
 ('performance',90,'chart','performance','Per-employee performance details — on the employee profile.')
) as s(k, sort, icon, pm, d) where config_area.key = s.k;

-- 2. field types
alter table config_field drop constraint config_field_type_check;
alter table config_field add constraint config_field_type_check check (type in (
  'TEXT','TEXTAREA','NUMBER','DATE','SELECT','MULTISELECT','BOOLEAN','EMAIL','PHONE','FILE',
  'EMPLOYEE','CURRENCY','HEADING','DEPARTMENT','LOCATION','EMPLOYMENT_TYPE'));

-- 3. section + field flags
alter table config_section
    add column description    text,
    add column active         boolean not null default true,
    add column show_in_portal boolean not null default false,
    add column locked         boolean not null default false;

alter table config_field
    add column masked_default boolean not null default false,
    add column source         text;

-- 4. area owners by role (the UI model); area_owner (user) stays for exceptions
create table area_owner_role (
    org_id     uuid not null references org (id),
    area_key   text not null references config_area (key),
    role_id    uuid not null references role (id) on delete cascade,
    created_at timestamptz not null default now(),
    created_by uuid,
    primary key (org_id, area_key, role_id)
);
insert into area_owner_role (org_id, area_key, role_id)
select r.org_id, a.k, r.id from role r
join (values ('employee_profile','hr_admin'),('onboarding','hr_admin'),('leave','hr_admin'),
             ('attendance','hr_admin'),('requests','hr_admin'),('payroll','finance'),('performance','hr_admin'))
  as a(k, rk) on r.key = a.rk;

-- 5. field values (employee_profile values stay on the employee row: columns + custom jsonb)
create table config_field_value (
    org_id     uuid  not null references org (id),
    area_key   text  not null references config_area (key),
    record_id  uuid  not null,
    field_id   uuid  not null references config_field (id) on delete cascade,
    value      jsonb,
    updated_at timestamptz not null default now(),
    updated_by uuid,
    version    bigint not null default 0,
    primary key (org_id, area_key, record_id, field_id)
);
create index idx_cfv_field on config_field_value (field_id);

-- who owns a record of an area whose module does not exist yet (e.g. a leave request): the requester
create table config_record_owner (
    org_id            uuid not null references org (id),
    area_key          text not null references config_area (key),
    record_id         uuid not null,
    owner_employee_id uuid not null,
    primary key (org_id, area_key, record_id)
);

-- 6. HR-approves change requests
create table config_change_request (
    id           uuid primary key default gen_random_uuid(),
    org_id       uuid not null references org (id),
    area_key     text not null references config_area (key),
    record_id    uuid not null,
    field_id     uuid not null references config_field (id) on delete cascade,
    old_value    jsonb,
    new_value    jsonb,
    status       text not null default 'PENDING' check (status in ('PENDING','APPROVED','REJECTED','CANCELLED')),
    requested_by uuid not null,
    requested_at timestamptz not null default now(),
    decided_by   uuid,
    decided_at   timestamptz,
    note         text,
    version      bigint not null default 0
);
create unique index ux_ccr_open on config_change_request (org_id, area_key, record_id, field_id) where status = 'PENDING';
create index idx_ccr_status on config_change_request (org_id, status);

-- 7. lookup lists that have no domain table (System B)
create table config_list (
    org_id     uuid not null references org (id),
    module     text not null,
    list_key   text not null,
    items      jsonb not null default '[]',
    updated_at timestamptz not null default now(),
    updated_by uuid,
    version    bigint not null default 0,
    primary key (org_id, module, list_key)
);

-- 8. field access: own/others carry the meaning; the legacy single value may be empty
alter table config_field_access alter column access drop not null;
