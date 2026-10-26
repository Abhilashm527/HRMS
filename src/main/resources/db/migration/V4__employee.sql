create table employee (
    id              uuid primary key default gen_random_uuid(),
    org_id          uuid        not null references org (id),
    employee_code   text        not null,
    first_name      text        not null,
    last_name       text        not null,
    display_name    text        not null,
    work_email      text        not null unique,
    personal_email  text,
    phone           text,
    department_id   uuid references department (id),
    designation_id  uuid references designation (id),
    location_id     uuid references location (id),
    manager_id      uuid references employee (id),
    employment_type text        not null,
    status          text        not null default 'ACTIVE' check (status in ('ACTIVE', 'ON_NOTICE', 'EXITED')),
    joining_date    date        not null,
    exit_date       date,
    -- values of admin-configured fields (config module), keyed by field key
    custom          jsonb       not null default '{}'::jsonb,
    created_at      timestamptz not null default now(),
    created_by      uuid,
    updated_at      timestamptz not null default now(),
    updated_by      uuid,
    version         bigint      not null default 0,
    unique (org_id, employee_code)
);
create index idx_employee_manager on employee (manager_id);
create index idx_employee_department on employee (department_id);
create index idx_employee_location on employee (location_id);
create index idx_employee_status on employee (status);

alter table department
    add constraint fk_department_head foreign key (head_employee_id) references employee (id);
