-- Teams & Projects (see TEAMS_PROJECTS_BACKEND_GUIDE.md)

-- team
alter table team
    add column ms_channel_id text,
    add column description   text;
create unique index ux_team_name on team (org_id, department_id, lower(name));
create index idx_team_department on team (department_id);
create index idx_team_lead       on team (lead_employee_id);

create table team_member (
    id          uuid primary key default gen_random_uuid(),
    org_id      uuid        not null references org (id),
    team_id     uuid        not null references team (id),
    employee_id uuid        not null references employee (id),
    is_primary  boolean     not null default false,
    since       date        not null default current_date,
    until       date,
    created_at  timestamptz not null default now(),
    created_by  uuid,
    check (until is null or until >= since)
);
create unique index ux_team_member_current on team_member (team_id, employee_id) where until is null;
create unique index ux_team_member_primary on team_member (employee_id) where is_primary and until is null;
create index idx_team_member_emp on team_member (employee_id) where until is null;
create index idx_team_member_team on team_member (team_id);

-- project
alter table project
    add column start_date    date,
    add column end_date      date,
    add column department_id uuid references department (id),
    add column billable      boolean not null default false,
    add column description   text;
update project set status = upper(replace(status, ' ', '_'));
alter table project alter column status set default 'ACTIVE';
alter table project add constraint ck_project_status check (status in ('PLANNED', 'ACTIVE', 'ON_HOLD', 'COMPLETED'));
alter table project add constraint ck_project_dates check (end_date is null or start_date is null or end_date >= start_date);
alter table project drop constraint if exists project_org_id_code_key;
create unique index ux_project_code on project (org_id, upper(code));
create unique index ux_project_name on project (org_id, lower(name));
create index idx_project_owner on project (owner_employee_id);
update project set billable = true where coalesce(client, 'Internal') <> 'Internal';

create table project_member (
    id          uuid primary key default gen_random_uuid(),
    org_id      uuid        not null references org (id),
    project_id  uuid        not null references project (id),
    employee_id uuid        not null references employee (id),
    role        text        not null default 'CONTRIBUTOR' check (role in ('LEAD', 'CONTRIBUTOR', 'REVIEWER', 'ADVISOR')),
    allocation  int         not null default 0 check (allocation between 0 and 100),
    since       date        not null default current_date,
    until       date,
    note        text,
    created_at  timestamptz not null default now(),
    created_by  uuid,
    check (until is null or until >= since)
);
create unique index ux_project_member_current on project_member (project_id, employee_id) where until is null;
create index idx_project_member_emp on project_member (employee_id) where until is null;
create index idx_project_member_project on project_member (project_id);
-- no ON DELETE CASCADE: deletes are guarded in the services
