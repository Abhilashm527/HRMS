create table config_area (
    key    text primary key,
    name   text not null,
    module text not null
);

insert into config_area (key, name, module) values
    ('employee_profile', 'Employee profile', 'employee'),
    ('onboarding',       'Onboarding',        'onboarding'),
    ('leave',            'Leave',             'leave'),
    ('attendance',       'Attendance',        'attendance'),
    ('requests',         'Requests',          'requests'),
    ('assets',           'Assets',            'assets'),
    ('recruiting',       'Recruiting',        'recruiting'),
    ('payroll',          'Payroll',           'payroll'),
    ('performance',      'Performance',       'performance');

alter table area_owner add constraint fk_area_owner_area foreign key (area_key) references config_area (key);

create table config_section (
    id         uuid primary key default gen_random_uuid(),
    org_id     uuid        not null references org (id),
    area_key   text        not null references config_area (key),
    key        text        not null,
    name       text        not null,
    sort       int         not null default 0,
    system     boolean     not null default false,
    created_at timestamptz not null default now(),
    created_by uuid,
    updated_at timestamptz not null default now(),
    updated_by uuid,
    version    bigint      not null default 0,
    unique (org_id, area_key, key)
);

create table config_field (
    id              uuid primary key default gen_random_uuid(),
    org_id          uuid        not null references org (id),
    area_key        text        not null references config_area (key),
    section_id      uuid        not null references config_section (id),
    key             text        not null,
    label           text        not null,
    type            text        not null check (type in (
        'TEXT', 'TEXTAREA', 'NUMBER', 'DATE', 'SELECT', 'MULTISELECT', 'BOOLEAN',
        'EMAIL', 'PHONE', 'FILE', 'EMPLOYEE', 'CURRENCY')),
    required        boolean     not null default false,
    default_value   jsonb,
    help_text       text,
    options         jsonb,
    visibility_rule jsonb,
    sort            int         not null default 0,
    system          boolean     not null default false,
    active          boolean     not null default true,
    created_at      timestamptz not null default now(),
    created_by      uuid,
    updated_at      timestamptz not null default now(),
    updated_by      uuid,
    version         bigint      not null default 0,
    unique (org_id, area_key, key)
);
create index idx_config_field_section on config_field (section_id);

create table config_field_access (
    field_id      uuid not null references config_field (id) on delete cascade,
    role_id       uuid not null references role (id) on delete cascade,
    access        text not null check (access in ('HIDDEN', 'MASKED', 'VIEW', 'EDIT', 'HR_APPROVES')),
    own_access    text check (own_access in ('HIDDEN', 'MASKED', 'VIEW', 'EDIT', 'HR_APPROVES')),
    others_access text check (others_access in ('HIDDEN', 'MASKED', 'VIEW', 'EDIT', 'HR_APPROVES')),
    primary key (field_id, role_id)
);

-- system sections and fields of the employee profile
insert into config_section (org_id, area_key, key, name, sort, system) values
    ('00000000-0000-0000-0000-000000000001', 'employee_profile', 'basic', 'Basic details', 10, true),
    ('00000000-0000-0000-0000-000000000001', 'employee_profile', 'work',  'Work details',  20, true);

insert into config_field (org_id, area_key, section_id, key, label, type, required, options, sort, system)
select '00000000-0000-0000-0000-000000000001', 'employee_profile', s.id, f.key, f.label, f.type, f.required,
       f.options::jsonb, f.sort, true
from (values
    ('basic', 'first_name',      'First name',      'TEXT',   true,  null,  10),
    ('basic', 'last_name',       'Last name',       'TEXT',   true,  null,  20),
    ('basic', 'work_email',      'Work email',      'EMAIL',  true,  null,  30),
    ('work',  'joining_date',    'Joining date',    'DATE',   true,  null,  10),
    ('work',  'department',      'Department',      'SELECT', true,  null,  20),
    ('work',  'designation',     'Designation',     'SELECT', true,  null,  30),
    ('work',  'manager',         'Manager',         'EMPLOYEE', false, null, 40),
    ('work',  'location',        'Location',        'SELECT', false, null,  50),
    ('work',  'employment_type', 'Employment type', 'SELECT', true,
        '["FULL_TIME","PART_TIME","CONTRACT","INTERN","CONSULTANT"]', 60)
) as f(section_key, key, label, type, required, options, sort)
join config_section s
  on s.org_id = '00000000-0000-0000-0000-000000000001'
 and s.area_key = 'employee_profile'
 and s.key = f.section_key;
