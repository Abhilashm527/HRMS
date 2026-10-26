-- Configuration: employee fields layout, custom field access, calendar + request types, asset and onboarding configuration
-- (see CONFIGURATION_MODULES_BACKEND_GUIDE.md)

-- ================================================================== A / B: fields
alter table config_section add column deleted_at timestamptz, add column builtin boolean not null default false;
alter table config_field
    add column deleted_at     timestamptz,
    add column access_preset  text check (access_preset in ('EDIT', 'HR_APPROVES', 'VIEW', 'HIDDEN')),
    add column validator      text,
    add column access_scope   text check (access_scope in ('EVERYONE', 'MANAGERS_HR', 'ADMINS')),
    add column employee_sees  boolean not null default true;
-- access rows written from a custom field's "Visible to" are marked, so an explicit Field-security choice is never overwritten
alter table config_field_access add column generated boolean not null default false;

update config_section set builtin = true where area_key = 'employee_profile' and key in ('basic', 'work', 'job', 'prev', 'docs');
update config_field set validator = 'PAN' where area_key = 'employee_profile' and key = 'pan';
update config_field set validator = 'AADHAAR' where area_key = 'employee_profile' and key = 'aadhaar';
update config_field set validator = 'IFSC' where area_key = 'employee_profile' and key = 'ifsc';
update config_field set validator = 'ACCOUNT' where area_key = 'employee_profile' and key = 'acct';

create table config_layout_version (
    org_id     uuid        not null references org (id),
    area_key   text        not null references config_area (key),
    version    bigint      not null default 0,
    updated_at timestamptz not null default now(),
    updated_by uuid,
    primary key (org_id, area_key)
);
-- which fields a role has already seen ("New fields" badge in Module permissions)
create table config_field_ack (
    org_id   uuid        not null references org (id),
    role_id  uuid        not null references role (id) on delete cascade,
    acked_at timestamptz not null default now(),
    primary key (org_id, role_id)
);

-- ================================================================== C: calendar event types
create table calendar_event_type (
    id uuid primary key default gen_random_uuid(),
    org_id uuid not null references org (id),
    key text not null,
    name text not null,
    fg text not null,
    bg text not null,
    who text not null default 'ALL' check (who in ('ALL', 'ADMINS')),
    system boolean not null default false,
    active boolean not null default true,
    sort int not null default 0,
    unique (org_id, key),
    constraint ck_cal_color check (fg ~ '^#[0-9A-Fa-f]{6}$' and bg ~ '^#[0-9A-Fa-f]{6}$'),
    created_at timestamptz not null default now(),
    created_by uuid,
    updated_at timestamptz not null default now(),
    updated_by uuid,
    version    bigint      not null default 0
);
create unique index ux_cal_type_name on calendar_event_type (org_id, lower(name));

insert into calendar_event_type (org_id, key, name, fg, bg, who, system, sort) values
 ('00000000-0000-0000-0000-000000000001', 'MEETING',     'Meeting',          '#1D4ED8', '#DBEAFE', 'ALL',    false, 10),
 ('00000000-0000-0000-0000-000000000001', 'APPOINTMENT', 'Appointment',      '#6D28D9', '#EDE9FE', 'ALL',    false, 20),
 ('00000000-0000-0000-0000-000000000001', 'INTERVIEW',   'Interview',        '#B45309', '#FEF3C7', 'ADMINS', false, 30),
 ('00000000-0000-0000-0000-000000000001', 'TRAINING',    'Training',         '#0F766E', '#CCFBF1', 'ALL',    false, 40),
 ('00000000-0000-0000-0000-000000000001', 'DEADLINE',    'Deadline',         '#B91C1C', '#FEE2E2', 'ALL',    false, 50),
 ('00000000-0000-0000-0000-000000000001', 'HOLIDAY',     'Holiday',          '#BE185D', '#FCE7F3', 'ALL',    true,  100),
 ('00000000-0000-0000-0000-000000000001', 'BIRTHDAY',    'Birthday',         '#B45309', '#FEF3C7', 'ALL',    true,  110),
 ('00000000-0000-0000-0000-000000000001', 'ANNIVERSARY', 'Work anniversary', '#0369A1', '#E0F2FE', 'ALL',    true,  120);

-- ================================================================== D: request types
create table request_type (
    id uuid primary key default gen_random_uuid(),
    org_id uuid not null references org (id),
    category text not null check (category in ('LETTER', 'REIMBURSEMENT', 'HELPDESK')),
    name text not null,
    active boolean not null default true,
    sort int not null default 0,
    helpdesk_team_role_id uuid references role (id),
    created_at timestamptz not null default now(),
    created_by uuid,
    updated_at timestamptz not null default now(),
    updated_by uuid,
    version    bigint      not null default 0
);
create unique index ux_request_type_name on request_type (org_id, category, lower(name));

insert into request_type (org_id, category, name, sort) values
 ('00000000-0000-0000-0000-000000000001', 'LETTER', 'Employment verification', 10), ('00000000-0000-0000-0000-000000000001', 'LETTER', 'Salary certificate', 20),
 ('00000000-0000-0000-0000-000000000001', 'LETTER', 'Address proof', 30), ('00000000-0000-0000-0000-000000000001', 'LETTER', 'Visa / travel letter', 40), ('00000000-0000-0000-0000-000000000001', 'LETTER', 'Experience letter', 50),
 ('00000000-0000-0000-0000-000000000001', 'REIMBURSEMENT', 'Travel', 10), ('00000000-0000-0000-0000-000000000001', 'REIMBURSEMENT', 'Internet / phone', 20), ('00000000-0000-0000-0000-000000000001', 'REIMBURSEMENT', 'Meals', 30),
 ('00000000-0000-0000-0000-000000000001', 'REIMBURSEMENT', 'Learning & certifications', 40), ('00000000-0000-0000-0000-000000000001', 'REIMBURSEMENT', 'Medical', 50), ('00000000-0000-0000-0000-000000000001', 'REIMBURSEMENT', 'Relocation', 60);
insert into request_type (org_id, category, name, sort, helpdesk_team_role_id)
select '00000000-0000-0000-0000-000000000001', 'HELPDESK', v.name, v.sort, (select r.id from role r where r.org_id = '00000000-0000-0000-0000-000000000001' and r.key = v.role)
from (values ('IT', 10, 'it_admin'), ('HR', 20, 'hr_admin'), ('Payroll', 30, 'finance'), ('Facilities', 40, 'it_admin')) as v(name, sort, role);

-- ================================================================== E: asset configuration
create table asset_status (
    id uuid primary key default gen_random_uuid(),
    org_id uuid not null references org (id),
    kind text not null check (kind in ('HARDWARE', 'SOFTWARE')),
    name text not null,
    color smallint not null default 0 check (color between 0 and 5),
    assignable boolean not null default false,
    active boolean not null default true,
    sort int not null default 0,
    created_at timestamptz not null default now(),
    created_by uuid,
    updated_at timestamptz not null default now(),
    updated_by uuid,
    version    bigint      not null default 0
);
create unique index ux_asset_status_name on asset_status (org_id, kind, lower(name));

create table asset_category (
    id uuid primary key default gen_random_uuid(),
    org_id uuid not null references org (id),
    kind text not null check (kind in ('HARDWARE', 'SOFTWARE')),
    name text not null,
    icon text not null default 'box',
    active boolean not null default true,
    sort int not null default 0,
    created_at timestamptz not null default now(),
    created_by uuid,
    updated_at timestamptz not null default now(),
    updated_by uuid,
    version    bigint      not null default 0
);
create unique index ux_asset_category_name on asset_category (org_id, kind, lower(name));

create table asset_type (
    id uuid primary key default gen_random_uuid(),
    org_id uuid not null references org (id),
    kind text not null check (kind in ('HARDWARE', 'SOFTWARE')),
    name text not null,
    category_id uuid references asset_category (id),
    icon text,
    max_age_years int check (max_age_years between 1 and 20),
    billing text check (billing in ('Monthly', 'Annual', 'One-time', 'Free')),
    unit text check (unit in ('Per seat', 'Per device', 'Site-wide')),
    active boolean not null default true,
    sort int not null default 0,
    created_at timestamptz not null default now(),
    created_by uuid,
    updated_at timestamptz not null default now(),
    updated_by uuid,
    version    bigint      not null default 0
);
create unique index ux_asset_type_name on asset_type (org_id, kind, lower(name));
create index idx_asset_type_category on asset_type (category_id);

create table asset_ownership (
    id uuid primary key default gen_random_uuid(),
    org_id uuid not null references org (id),
    name text not null,
    capture_fields jsonb not null default '[]',
    return_required boolean not null default false,
    active boolean not null default true,
    sort int not null default 0,
    created_at timestamptz not null default now(),
    created_by uuid,
    updated_at timestamptz not null default now(),
    updated_by uuid,
    version    bigint      not null default 0
);
create unique index ux_asset_ownership_name on asset_ownership (org_id, lower(name));

create table asset_id_format (
    id uuid primary key default gen_random_uuid(),
    org_id uuid not null references org (id),
    kind text not null check (kind in ('HARDWARE', 'SOFTWARE')),
    ownership_id uuid references asset_ownership (id),
    license_type_id uuid references asset_type (id),
    fmt text not null,
    active boolean not null default true,
    created_at timestamptz not null default now(),
    created_by uuid,
    updated_at timestamptz not null default now(),
    updated_by uuid,
    version    bigint      not null default 0
);
create unique index ux_asset_id_format on asset_id_format (org_id, kind, lower(fmt));

create table asset_id_seq (
    format_id  uuid not null references asset_id_format (id),
    scope      text not null,
    last_value int  not null default 0,
    primary key (format_id, scope)
);

create table asset_alert_rule (
    id uuid primary key default gen_random_uuid(),
    org_id uuid not null references org (id),
    kind text not null check (kind in ('HARDWARE', 'SOFTWARE')),
    event text not null check (event in ('WARRANTY_END', 'INSURANCE_END', 'LEASE_END', 'CLIENT_RETURN', 'MAX_AGE', 'RENEWAL', 'CONTRACT_END')),
    name text not null,
    days_before int not null check (days_before in (7, 10, 15, 30, 45, 60, 90)),
    in_app boolean not null default true,
    email boolean not null default false,
    active boolean not null default true,
    sort int not null default 0,
    created_at timestamptz not null default now(),
    created_by uuid,
    updated_at timestamptz not null default now(),
    updated_by uuid,
    version    bigint      not null default 0
);


create table asset_alert_sent (
    rule_id  uuid        not null references asset_alert_rule (id),
    record_id uuid       not null,
    due_date date        not null,
    sent_at  timestamptz not null default now(),
    primary key (rule_id, record_id, due_date)
);

-- the lists used to live as JSON in config_list: move the rows (ids kept), then drop the JSON copies (the version row stays)
insert into asset_status (id, org_id, kind, name, color, assignable, active, sort)
select (e.value ->> 'id')::uuid, l.org_id, upper(l.module), e.value ->> 'name', coalesce((e.value ->> 'color')::int, 0),
       coalesce((e.value ->> 'assignable')::boolean, false), coalesce((e.value ->> 'active')::boolean, true), (e.ordinality::int - 1) * 10
from config_list l cross join lateral jsonb_array_elements(l.items) with ordinality e(value, ordinality)
where l.list_key = 'statuses' and l.module in ('hardware', 'software');

-- hardware starter data (CFG0)
insert into asset_category (org_id, kind, name, icon, sort) values
 ('00000000-0000-0000-0000-000000000001', 'HARDWARE', 'Computing', 'laptop', 10), ('00000000-0000-0000-0000-000000000001', 'HARDWARE', 'Peripherals', 'monitor', 20),
 ('00000000-0000-0000-0000-000000000001', 'HARDWARE', 'Accessories', 'headphones', 30), ('00000000-0000-0000-0000-000000000001', 'HARDWARE', 'Network', 'globe', 40),
 ('00000000-0000-0000-0000-000000000001', 'HARDWARE', 'Mobile', 'phone', 50), ('00000000-0000-0000-0000-000000000001', 'HARDWARE', 'Non-IT', 'box', 60),
 ('00000000-0000-0000-0000-000000000001', 'SOFTWARE', 'Productivity', 'apps', 10), ('00000000-0000-0000-0000-000000000001', 'SOFTWARE', 'Development', 'file', 20),
 ('00000000-0000-0000-0000-000000000001', 'SOFTWARE', 'Design', 'monitor', 30), ('00000000-0000-0000-0000-000000000001', 'SOFTWARE', 'Communication', 'users', 40),
 ('00000000-0000-0000-0000-000000000001', 'SOFTWARE', 'Security', 'briefcase', 50), ('00000000-0000-0000-0000-000000000001', 'SOFTWARE', 'Finance', 'wallet', 60);
insert into asset_type (org_id, kind, name, category_id, icon, max_age_years, sort)
select '00000000-0000-0000-0000-000000000001', 'HARDWARE', v.name, (select c.id from asset_category c where c.org_id = '00000000-0000-0000-0000-000000000001' and c.kind = 'HARDWARE' and c.name = v.cat), v.icon, v.age, v.sort
from (values ('Laptop', 'Computing', 'laptop', 4, 10), ('Desktop', 'Computing', 'monitor', 5, 20), ('Monitor', 'Peripherals', 'monitor', 6, 30),
             ('Headset', 'Accessories', 'headphones', 2, 40), ('Router', 'Network', 'globe', 5, 50), ('Phone', 'Mobile', 'phone', 3, 60),
             ('Chair', 'Non-IT', 'box', 7, 70)) as v(name, cat, icon, age, sort);
insert into asset_type (org_id, kind, name, billing, unit, sort) values
 ('00000000-0000-0000-0000-000000000001', 'SOFTWARE', 'Subscription', 'Monthly', 'Per seat', 10), ('00000000-0000-0000-0000-000000000001', 'SOFTWARE', 'Enterprise', 'Annual', 'Site-wide', 20),
 ('00000000-0000-0000-0000-000000000001', 'SOFTWARE', 'Perpetual', 'One-time', 'Per device', 30), ('00000000-0000-0000-0000-000000000001', 'SOFTWARE', 'Open source', 'Free', 'Per device', 40),
 ('00000000-0000-0000-0000-000000000001', 'SOFTWARE', 'Trial', 'Free', 'Per seat', 50);
insert into asset_ownership (org_id, name, capture_fields, return_required, sort) values
 ('00000000-0000-0000-0000-000000000001', 'Company', '["Purchase date", "Vendor", "Warranty end"]', false, 10),
 ('00000000-0000-0000-0000-000000000001', 'Client', '["Client name", "Client asset tag", "Return date"]', true, 20),
 ('00000000-0000-0000-0000-000000000001', 'Leased', '["Lessor", "Lease start", "Lease end"]', true, 30);
insert into asset_id_format (id, org_id, kind, ownership_id, fmt, active)
select (e.value ->> 'id')::uuid, l.org_id, 'HARDWARE', (select o.id from asset_ownership o where o.org_id = l.org_id and lower(o.name) = lower(e.value ->> 'name')),
       e.value ->> 'fmt', coalesce((e.value ->> 'active')::boolean, true)
from config_list l cross join lateral jsonb_array_elements(l.items) e(value) where l.module = 'hardware' and l.list_key = 'idformat';
insert into asset_id_format (id, org_id, kind, license_type_id, fmt, active)
select (e.value ->> 'id')::uuid, l.org_id, 'SOFTWARE', (select t.id from asset_type t where t.org_id = l.org_id and t.kind = 'SOFTWARE' and lower(t.name) = lower(e.value ->> 'name')),
       e.value ->> 'fmt', coalesce((e.value ->> 'active')::boolean, true)
from config_list l cross join lateral jsonb_array_elements(l.items) e(value) where l.module = 'software' and l.list_key = 'idformat';
insert into asset_alert_rule (org_id, kind, event, name, days_before, in_app, email, sort) values
 ('00000000-0000-0000-0000-000000000001', 'HARDWARE', 'WARRANTY_END', 'Warranty ending', 30, true, true, 10), ('00000000-0000-0000-0000-000000000001', 'HARDWARE', 'INSURANCE_END', 'Insurance ending', 30, true, false, 20),
 ('00000000-0000-0000-0000-000000000001', 'HARDWARE', 'LEASE_END', 'Lease ending', 60, true, true, 30), ('00000000-0000-0000-0000-000000000001', 'HARDWARE', 'CLIENT_RETURN', 'Client return due', 15, true, true, 40),
 ('00000000-0000-0000-0000-000000000001', 'HARDWARE', 'MAX_AGE', 'Reaching maximum age', 90, true, false, 50),
 ('00000000-0000-0000-0000-000000000001', 'SOFTWARE', 'RENEWAL', 'Renewal due', 30, true, true, 10), ('00000000-0000-0000-0000-000000000001', 'SOFTWARE', 'CONTRACT_END', 'Contract ending', 60, true, false, 20);
delete from config_list where module in ('hardware', 'software') and list_key in ('statuses', 'categories', 'types', 'ownership', 'idformat', 'fields', 'alerts');

-- custom fields per asset type / software category live in their own config areas, one section per type
insert into config_area (key, name, module, description, icon, sort, perm_module) values
 ('asset_hardware', 'Hardware assets', 'assets', 'Extra details per hardware type.', 'laptop', 130, 'assets'),
 ('asset_software', 'Software licences', 'assets', 'Extra details per software category.', 'apps', 140, 'assets')
on conflict (key) do nothing;
insert into area_owner_role (org_id, area_key, role_id)
select o.org_id, a.key, o.role_id from area_owner_role o cross join (values ('asset_hardware'), ('asset_software')) as a(key) where o.area_key = 'assets'
on conflict do nothing;
insert into config_section (org_id, area_key, key, name, sort, system, locked, show_in_portal)
select '00000000-0000-0000-0000-000000000001', 'asset_hardware', lower(t.name), t.name, t.sort, false, false, false from asset_type t where t.org_id = '00000000-0000-0000-0000-000000000001' and t.kind = 'HARDWARE'
on conflict do nothing;
insert into config_section (org_id, area_key, key, name, sort, system, locked, show_in_portal)
select '00000000-0000-0000-0000-000000000001', 'asset_software', lower(replace(c.name, ' ', '_')), c.name, c.sort, false, false, false from asset_category c where c.org_id = '00000000-0000-0000-0000-000000000001' and c.kind = 'SOFTWARE'
on conflict do nothing;
insert into config_field (org_id, area_key, section_id, key, label, type, required, options, sort, system)
select '00000000-0000-0000-0000-000000000001', 'asset_hardware', s.id, v.section || '_' || v.key, v.label, v.type, v.req, v.options::jsonb, v.sort, false
from (values ('laptop', 'processor', 'Processor', 'TEXT', true, null, 10), ('laptop', 'ram_gb', 'RAM (GB)', 'NUMBER', true, null, 20),
             ('laptop', 'storage_gb', 'Storage (GB)', 'NUMBER', false, null, 30), ('laptop', 'os', 'Operating system', 'SELECT', false, '["Windows 11", "macOS", "Ubuntu"]', 40),
             ('laptop', 'encrypted', 'Disk encrypted', 'BOOLEAN', false, null, 50), ('desktop', 'processor', 'Processor', 'TEXT', false, null, 10),
             ('desktop', 'ram_gb', 'RAM (GB)', 'NUMBER', false, null, 20), ('monitor', 'size_in', 'Screen size (inch)', 'NUMBER', false, null, 10),
             ('phone', 'imei', 'IMEI', 'TEXT', true, null, 10), ('phone', 'storage_gb', 'Storage (GB)', 'NUMBER', false, null, 20))
     as v(section, key, label, type, req, options, sort)
join config_section s on s.org_id = '00000000-0000-0000-0000-000000000001' and s.area_key = 'asset_hardware' and s.key = v.section;

-- ================================================================== F: onboarding configuration
create table onboarding_step (
    id uuid primary key default gen_random_uuid(),
    org_id uuid not null references org (id),
    key text not null,
    name text not null,
    description text,
    kind text not null check (kind in ('FIELDS', 'M365', 'DOCS_INVITE')),
    sort int not null,
    locked boolean not null default false,
    config_section_id uuid references config_section (id),
    active boolean not null default true,
    unique (org_id, key),
    created_at timestamptz not null default now(),
    created_by uuid,
    updated_at timestamptz not null default now(),
    updated_by uuid,
    version    bigint      not null default 0
);


create table m365_rule (
    id uuid primary key default gen_random_uuid(),
    org_id uuid not null references org (id),
    name text not null,
    employment_type_id uuid references employment_type (id),
    upn_format text not null check (upn_format in ('first.last', 'firstlast', 'f.last', 'first')),
    license_sku text not null,
    groups jsonb not null default '[]',
    force_reset boolean not null default true,
    sort int not null default 0,
    active boolean not null default true,
    created_at timestamptz not null default now(),
    created_by uuid,
    updated_at timestamptz not null default now(),
    updated_by uuid,
    version    bigint      not null default 0
);


create table invite_template (
    id uuid primary key default gen_random_uuid(),
    org_id uuid not null references org (id),
    name text not null,
    employment_type_id uuid references employment_type (id),
    subject text not null,
    body text not null,
    due_days_before int not null check (due_days_before in (1, 2, 3, 5, 7)),
    remind_every_days int check (remind_every_days in (1, 2, 3, 5)),
    cc_manager boolean not null default true,
    active boolean not null default true,
    created_at timestamptz not null default now(),
    created_by uuid,
    updated_at timestamptz not null default now(),
    updated_by uuid,
    version    bigint      not null default 0
);


create table onboarding_document (
    id uuid primary key default gen_random_uuid(),
    org_id uuid not null references org (id),
    key text not null,
    name text not null,
    hint text,
    doc_group text not null default 'Other',
    employment_type_id uuid references employment_type (id),
    required boolean not null default true,
    sort int not null default 0,
    active boolean not null default true,
    unique (org_id, key),
    created_at timestamptz not null default now(),
    created_by uuid,
    updated_at timestamptz not null default now(),
    updated_by uuid,
    version    bigint      not null default 0
);


create table onboarding_task_template (
    id uuid primary key default gen_random_uuid(),
    org_id uuid not null references org (id),
    name text not null,
    phase text not null check (phase in ('BEFORE_DAY_1', 'DAY_1', 'FIRST_30_DAYS')),
    owner text not null check (owner in ('CANDIDATE', 'PEOPLE', 'IT', 'MANAGER', 'OPERATIONS', 'PAYROLL', 'FINANCE')),
    sort int not null default 0,
    active boolean not null default true,
    created_at timestamptz not null default now(),
    created_by uuid,
    updated_at timestamptz not null default now(),
    updated_by uuid,
    version    bigint      not null default 0
);


insert into onboarding_step (id, org_id, key, name, description, kind, sort, locked, config_section_id, active)
select (e.value ->> 'id')::uuid, l.org_id, lower(regexp_replace(e.value ->> 'name', '[^A-Za-z0-9]+', '_', 'g')), e.value ->> 'name', e.value ->> 'desc',
       case e.value ->> 'kind' when 'Fields' then 'FIELDS' when 'Microsoft 365' then 'M365' else 'DOCS_INVITE' end,
       coalesce((e.value ->> 'order')::int, e.ordinality::int), coalesce((e.value ->> 'lock')::boolean, false),
       (select s.id from config_section s where s.org_id = l.org_id and s.area_key = 'onboarding' and lower(s.name) = lower(e.value ->> 'name')),
       coalesce((e.value ->> 'active')::boolean, true)
from config_list l cross join lateral jsonb_array_elements(l.items) with ordinality e(value, ordinality)
where l.module = 'onboarding' and l.list_key = 'steps';
delete from config_list where module = 'onboarding' and list_key in ('steps', 'm365', 'invites', 'docs', 'checklist', 'portal');

insert into m365_rule (org_id, name, employment_type_id, upn_format, license_sku, groups, force_reset, sort) values
 ('00000000-0000-0000-0000-000000000001', 'Employees', null, 'first.last', 'Microsoft 365 Business Standard', '["All staff", "{department}"]', true, 30),
 ('00000000-0000-0000-0000-000000000001', 'Interns', (select id from employment_type where org_id = '00000000-0000-0000-0000-000000000001' and code = 'IN'), 'first.last', 'Microsoft 365 Business Basic', '["Interns"]', true, 10),
 ('00000000-0000-0000-0000-000000000001', 'Contractors', (select id from employment_type where org_id = '00000000-0000-0000-0000-000000000001' and code = 'CT'), 'first.last', 'Microsoft 365 Business Basic', '["Contractors"]', true, 20);
insert into invite_template (org_id, name, employment_type_id, subject, body, due_days_before, remind_every_days, cc_manager) values
 ('00000000-0000-0000-0000-000000000001', 'Welcome — all hires', null, 'Welcome to {company}, {first}!',
  'Hi {first}, we are glad you are joining as {title} on {doj} in {location}. Your manager is {manager}. Please sign in as {upn} and upload: {docs}.', 3, 2, true),
 ('00000000-0000-0000-0000-000000000001', 'Welcome — interns', (select id from employment_type where org_id = '00000000-0000-0000-0000-000000000001' and code = 'IN'), 'Your internship at {company} starts {doj}',
  'Hi {first}, welcome! You start on {doj} as {title}. Please upload: {docs}.', 5, 3, true);
insert into onboarding_document (org_id, key, name, hint, doc_group, employment_type_id, required, sort) values
 ('00000000-0000-0000-0000-000000000001', 'pan', 'PAN card', 'Clear photo or scan of both sides', 'Identity', null, true, 10),
 ('00000000-0000-0000-0000-000000000001', 'aadhaar', 'Aadhaar card', 'Front and back', 'Identity', null, true, 20),
 ('00000000-0000-0000-0000-000000000001', 'photo', 'Passport-size photo', 'White background', 'Identity', null, true, 30),
 ('00000000-0000-0000-0000-000000000001', 'cheque', 'Cancelled cheque', 'For salary account details', 'Bank', null, true, 40),
 ('00000000-0000-0000-0000-000000000001', 'education', 'Education certificates', 'Highest qualification', 'Education', null, true, 50),
 ('00000000-0000-0000-0000-000000000001', 'relieving', 'Relieving letter', 'From the previous employer', 'Previous employment', (select id from employment_type where org_id = '00000000-0000-0000-0000-000000000001' and code = 'FT'), false, 60),
 ('00000000-0000-0000-0000-000000000001', 'offer_signed', 'Signed offer letter', 'Signed copy of the offer', 'Contract', null, true, 70);
insert into onboarding_task_template (org_id, name, phase, owner, sort) values
 ('00000000-0000-0000-0000-000000000001', 'Send welcome email', 'BEFORE_DAY_1', 'PEOPLE', 10), ('00000000-0000-0000-0000-000000000001', 'Create accounts and email', 'BEFORE_DAY_1', 'IT', 20),
 ('00000000-0000-0000-0000-000000000001', 'Order laptop and accessories', 'BEFORE_DAY_1', 'IT', 30), ('00000000-0000-0000-0000-000000000001', 'Welcome and office tour', 'DAY_1', 'MANAGER', 10),
 ('00000000-0000-0000-0000-000000000001', 'Collect documents', 'DAY_1', 'PEOPLE', 20), ('00000000-0000-0000-0000-000000000001', 'Add to payroll', 'DAY_1', 'PAYROLL', 30),
 ('00000000-0000-0000-0000-000000000001', '30-day check-in', 'FIRST_30_DAYS', 'MANAGER', 10), ('00000000-0000-0000-0000-000000000001', 'Complete mandatory training', 'FIRST_30_DAYS', 'CANDIDATE', 20),
 ('00000000-0000-0000-0000-000000000001', 'Set probation goals', 'FIRST_30_DAYS', 'MANAGER', 30);
