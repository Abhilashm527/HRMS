-- Departments (see DEPARTMENTS_BACKEND_GUIDE.md)

alter table department
    add column description text,
    add column sort        int not null default 0;

-- case-insensitive uniqueness replaces the plain unique(org_id, code); the code is optional when the setting is off
alter table department drop constraint if exists department_org_id_code_key;
alter table department alter column code drop not null;
create unique index ux_department_code on department (org_id, upper(code)) where code is not null;
create unique index ux_department_name on department (org_id, lower(name));

create index idx_department_parent on department (parent_id);
create index idx_department_head   on department (head_employee_id);
alter table department add constraint ck_department_not_self check (parent_id is null or parent_id <> id);
-- no ON DELETE CASCADE anywhere: deletes are guarded in the service

-- custom fields on a department (Settings > Onboarding > Custom fields, entity "Department") live in the config module
insert into config_area (key, name, module, description, icon, sort, perm_module)
values ('department', 'Departments', 'org', 'Extra fields on departments — shown on the department and in the hierarchy.',
        'building', 100, 'org')
on conflict (key) do nothing;

insert into config_section (org_id, area_key, key, name, sort, system, locked, show_in_portal)
values ('00000000-0000-0000-0000-000000000001', 'department', 'custom', 'Additional details', 10, false, false, false)
on conflict do nothing;

insert into area_owner_role (org_id, area_key, role_id)
select r.org_id, 'department', r.id from role r
where r.org_id = '00000000-0000-0000-0000-000000000001' and r.key = 'hr_admin'
on conflict do nothing;
