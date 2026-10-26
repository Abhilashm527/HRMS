-- Titles (see TITLES_BACKEND_GUIDE.md)

alter table job_title add column sort int not null default 0;

-- an active title must have a position: titles that have none are switched off first
update job_title set active = false where designation_id is null and active;

create unique index ux_job_title_name on job_title (org_id, lower(name));
create unique index ux_job_title_code on job_title (org_id, upper(code)) where code is not null;
create index idx_job_title_department on job_title (department_id);
alter table job_title add constraint ck_job_title_active_has_position check (not active or designation_id is not null);

-- the employee's primary title: the one everything displays
alter table employee add column title_id uuid references job_title (id);
create index idx_employee_title on employee (title_id);

-- extra titles, only used while the "more than one title" setting is on
create table employee_extra_title (
    employee_id uuid        not null references employee (id),
    title_id    uuid        not null references job_title (id),
    org_id      uuid        not null references org (id),
    since       date        not null default current_date,
    created_at  timestamptz not null default now(),
    created_by  uuid,
    primary key (employee_id, title_id)
);
create index idx_extra_title_title on employee_extra_title (title_id);

-- title history: the profile timeline, and the "history points at it" delete guard
create table employee_title_history (
    id          uuid primary key default gen_random_uuid(),
    org_id      uuid        not null references org (id),
    employee_id uuid        not null references employee (id),
    title_id    uuid        not null references job_title (id),
    is_primary  boolean     not null,
    from_date   date        not null,
    to_date     date,
    reason      text,
    created_at  timestamptz not null default now(),
    created_by  uuid
);
create index idx_title_history_emp   on employee_title_history (employee_id, from_date desc);
create index idx_title_history_title on employee_title_history (title_id);
-- no ON DELETE CASCADE anywhere: deletes are guarded in the service

-- custom fields on a title (Settings > Onboarding > Custom fields, entity "Title") live in the config module
insert into config_area (key, name, module, description, icon, sort, perm_module)
values ('title', 'Titles', 'org', 'Extra fields on job titles.', 'badge', 102, 'org')
on conflict (key) do nothing;

insert into config_section (org_id, area_key, key, name, sort, system, locked, show_in_portal)
values ('00000000-0000-0000-0000-000000000001', 'title', 'custom', 'Additional details', 10, false, false, false)
on conflict do nothing;

insert into area_owner_role (org_id, area_key, role_id)
select r.org_id, 'title', r.id from role r
where r.org_id = '00000000-0000-0000-0000-000000000001' and r.key = 'hr_admin'
on conflict do nothing;

-- the employee profile gets a Title field next to Designation (same sections, same access per role)
insert into config_field (org_id, area_key, section_id, key, label, type, required, sort, system)
select f.org_id, f.area_key, f.section_id, 'title', 'Title', 'SELECT', false, f.sort + 1, true
from config_field f where f.area_key = 'employee_profile' and f.key = 'designation'
on conflict do nothing;

insert into config_field_access (field_id, role_id, access, own_access, others_access)
select t.id, a.role_id, a.access, a.own_access, a.others_access
from config_field d
join config_field t on t.org_id = d.org_id and t.area_key = d.area_key and t.key = 'title'
join config_field_access a on a.field_id = d.id
where d.area_key = 'employee_profile' and d.key = 'designation'
on conflict do nothing;
