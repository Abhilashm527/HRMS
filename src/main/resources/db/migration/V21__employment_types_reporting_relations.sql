-- Employment types, reporting lines, dotted lines and mentors (see EMPLOYMENT_TYPES_REPORTING_RELATIONS_GUIDE.md)

-- ---------------------------------------------------------------- employment types
alter table employment_type
    add column is_fixed_term boolean not null default false,
    add column sort          int     not null default 0;
create unique index ux_emptype_name on employment_type (org_id, lower(name));
create unique index ux_emptype_code on employment_type (org_id, upper(code));
alter table employment_type add constraint ck_emptype_ranges
    check (probation_months between 0 and 24 and notice_days between 0 and 180);
update employment_type set leave_eligible = true where code = 'PT';
update employment_type set is_fixed_term = true where code in ('CT', 'IN');

alter table employee
    add column employment_type_id uuid references employment_type (id),
    add column probation_end_date date,
    add column confirmed_on       date,
    add column contract_end_date  date;

-- link by the old text: the type's name, its code, or the name written like FULL_TIME
update employee e set employment_type_id = t.id
  from employment_type t
 where t.org_id = e.org_id
   and (lower(t.name) = lower(e.employment_type) or upper(t.code) = upper(e.employment_type)
        or upper(replace(replace(t.name, '-', '_'), ' ', '_')) = upper(e.employment_type));
update employee e set employment_type = t.name from employment_type t where t.id = e.employment_type_id;

-- the column becomes mandatory once every employee has a type (otherwise the service still requires it for new people)
do $$
begin
    if not exists (select 1 from employee where employment_type_id is null) then
        alter table employee alter column employment_type_id set not null;
    end if;
end $$;
create index idx_employee_emptype on employee (employment_type_id);

create table employee_type_history (
    id             uuid primary key default gen_random_uuid(),
    org_id         uuid        not null references org (id),
    employee_id    uuid        not null references employee (id),
    from_type_id   uuid references employment_type (id),
    to_type_id     uuid        not null references employment_type (id),
    effective_date date        not null,
    reason         text,
    created_at     timestamptz not null default now(),
    created_by     uuid
);
create index idx_type_history_emp  on employee_type_history (employee_id, effective_date desc);
create index idx_type_history_type on employee_type_history (to_type_id);

-- ---------------------------------------------------------------- reporting lines
create table reporting_line_history (
    id          uuid primary key default gen_random_uuid(),
    org_id      uuid        not null references org (id),
    employee_id uuid        not null references employee (id),
    manager_id  uuid references employee (id),
    from_date   date        not null,
    to_date     date,
    reason      text,
    created_at  timestamptz not null default now(),
    created_by  uuid,
    check (to_date is null or to_date >= from_date)
);
create unique index ux_rl_current on reporting_line_history (employee_id) where to_date is null;
create index idx_rl_manager on reporting_line_history (manager_id);

insert into reporting_line_history (org_id, employee_id, manager_id, from_date, reason)
select org_id, id, manager_id, coalesce(joining_date, current_date), 'HIRE' from employee where status <> 'EXITED';

create table employee_extra_department (
    employee_id   uuid not null references employee (id),
    department_id uuid not null references department (id),
    org_id        uuid not null references org (id),
    since         date not null default current_date,
    primary key (employee_id, department_id)
);

-- ---------------------------------------------------------------- dotted lines and mentors
create table employee_relation (
    id          uuid primary key default gen_random_uuid(),
    org_id      uuid        not null references org (id),
    employee_id uuid        not null references employee (id),
    related_id  uuid        not null references employee (id),
    type        text        not null check (type in ('DOTTED', 'MENTOR')),
    from_date   date        not null default current_date,
    to_date     date,
    note        text,
    ended_at    timestamptz,
    ended_by    uuid,
    end_reason  text,
    created_at  timestamptz not null default now(),
    created_by  uuid,
    updated_at  timestamptz not null default now(),
    updated_by  uuid,
    version     bigint      not null default 0,
    check (employee_id <> related_id),
    check (to_date is null or to_date >= from_date)
);
create index idx_rel_emp     on employee_relation (employee_id) where ended_at is null;
create index idx_rel_related on employee_relation (related_id) where ended_at is null;

-- ---------------------------------------------------------------- settings
alter table org_settings
    add column max_span    int not null default 15,
    add column max_mentees int not null default 10;
