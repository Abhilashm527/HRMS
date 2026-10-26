-- Organization profile and settings (see ORGANIZATION_BACKEND_GUIDE.md)

alter table org
    add column code               text,
    add column industry           text,
    add column fy_start_month     int  not null default 4 check (fy_start_month between 1 and 12),
    add column currency           text not null default 'INR',
    add column primary_domain     text,
    add column status             text not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE', 'ARCHIVED')),
    add column employee_id_prefix text not null default 'IVY',
    add column logo_file_id       uuid;

update org set name = 'Ivoyant Technologies', legal_name = 'Ivoyant Technologies Pvt Ltd', code = 'IVOYANT',
               industry = 'IT services & consulting', primary_domain = 'ivoyant.com'
 where id = '00000000-0000-0000-0000-000000000001';
update org set code = upper(regexp_replace(name, '[^A-Za-z0-9]', '', 'g')) where code is null;
alter table org alter column code set not null;
create unique index ux_org_code on org (upper(code));

-- typed settings: one row per org, validated by check constraints
create table org_settings (
    org_id         uuid primary key references org (id),
    auto_id        boolean not null default true,
    id_format      text    not null default '{PREFIX}-{SEQ4}',
    req_mgr        boolean not null default true,
    multi_title    boolean not null default false,
    pos_mandatory  boolean not null default true,
    title_format   text    not null default '{title} · {position}',
    nested_depts   boolean not null default true,
    max_dept_depth int     not null default 5  check (max_dept_depth in (2, 3, 4, 5, 6, 8)),
    dept_code_req  boolean not null default true,
    max_rep_depth  int     not null default 10 check (max_rep_depth in (6, 8, 10, 12, 15)),
    custom_roles   boolean not null default true,
    role_inherit   boolean not null default true,
    sens_approval  boolean not null default true,
    audit_perm     boolean not null default true,
    salary_vis     text    not null default 'MANAGERS_HR'
        check (salary_vis in ('ADMINS_ONLY', 'HR_FINANCE', 'MANAGERS_HR', 'EVERYONE')),
    org_chart      boolean not null default true,
    analytics      boolean not null default true,
    retention      text    not null default 'Y7' check (retention in ('Y1', 'Y3', 'Y7', 'Y10')),
    audit_log      boolean not null default true,
    two_fa         boolean not null default true,
    pw_policy      text    not null default 'ENTRA' check (pw_policy in ('STANDARD', 'STRONG', 'ENTRA')),
    encrypt        boolean not null default true,
    updated_at     timestamptz not null default now(),
    updated_by     uuid,
    version        bigint  not null default 0
);
insert into org_settings (org_id) select id from org;

-- employee code sequence (per org; per year when the pattern has {YYYY})
create table employee_code_seq (
    org_id   uuid   not null references org (id),
    scope    text   not null,          -- 'ALL' or the year
    next_val bigint not null,
    primary key (org_id, scope)
);
-- start after the highest number in use, and never below 1001 so new codes (IVY-1001) do not look like old ones (EMP012)
insert into employee_code_seq (org_id, scope, next_val)
select o.id, 'ALL',
       greatest(coalesce(max(nullif(regexp_replace(e.employee_code, '\D', '', 'g'), '')::bigint), 0), 1000) + 1
from org o left join employee e on e.org_id = o.id
group by o.id;

-- setup checklist "reviewed" flags
create table org_setup_ack (
    org_id   uuid        not null references org (id),
    key      text        not null check (key in ('org_cfg', 'roles', 'ep', 'fls')),
    acked_by uuid        not null,
    acked_at timestamptz not null default now(),
    primary key (org_id, key)
);

-- the employee profile gets a compensation section, so the salary-visibility preset has fields to write to
insert into config_section (org_id, area_key, key, name, sort, system, locked, show_in_portal)
values ('00000000-0000-0000-0000-000000000001', 'employee_profile', 'compensation', 'Compensation', 55, true, false, false)
on conflict do nothing;

insert into config_field (org_id, area_key, section_id, key, label, type, required, sort, system)
select '00000000-0000-0000-0000-000000000001', 'employee_profile', s.id, 'ctc', 'Annual CTC (₹ lakh)', 'NUMBER', false, 10, true
from config_section s
where s.org_id = '00000000-0000-0000-0000-000000000001' and s.area_key = 'employee_profile' and s.key = 'compensation'
on conflict do nothing;

-- starting access, matching the default preset (Managers & HR): HR and finance edit, managers see it masked,
-- employees do not see anyone's, and see their own without being able to change it
insert into config_field_access (field_id, role_id, access, own_access, others_access)
select f.id, r.id, g.others, g.own, g.others
from config_field f
join (values ('hr_admin', 'EDIT', 'EDIT'), ('finance', 'EDIT', 'EDIT'), ('manager', null, 'MASKED'),
             ('employee', 'VIEW', 'HIDDEN')) g(role_key, own, others) on true
join role r on r.org_id = f.org_id and r.key = g.role_key
where f.org_id = '00000000-0000-0000-0000-000000000001' and f.area_key = 'employee_profile' and f.key = 'ctc'
on conflict do nothing;
