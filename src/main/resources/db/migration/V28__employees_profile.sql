-- Employees: photo, archive, effective-dated job history, exit, documents, previous employment, preferences, exports
-- (see EMPLOYEES_BACKEND_GUIDE.md). Status keeps the existing ACTIVE / ON_NOTICE / EXITED values; PROBATION is derived.

alter table employee
    add column photo_key     text,
    add column photo_version int         not null default 0,
    add column archived_at   timestamptz,
    add column hire_id       uuid;
create index employee_dir on employee (org_id, status, department_id, location_id) where archived_at is null;

create table employee_job_history (
    id                 uuid primary key default gen_random_uuid(),
    org_id             uuid        not null references org (id),
    employee_id        uuid        not null references employee (id),
    kind               text        not null check (kind in ('JOIN', 'PROMOTION', 'TRANSFER', 'REDESIGNATION', 'MANAGER_CHANGE', 'TYPE_CHANGE', 'STATUS', 'CORRECTION', 'EXIT')),
    effective_from     date        not null,
    applied_at         timestamptz,
    department_id      uuid references department (id),
    position_id        uuid references designation (id),
    title_id           uuid references job_title (id),
    title              text,
    location_id        uuid references location (id),
    manager_id         uuid references employee (id),
    employment_type_id uuid references employment_type (id),
    status             text,
    reason             text,
    created_by         uuid,
    created_at         timestamptz not null default now(),
    cancelled_at       timestamptz
);
create index ejh_emp on employee_job_history (employee_id, effective_from desc);
create index ejh_due on employee_job_history (effective_from) where applied_at is null and cancelled_at is null;

insert into employee_job_history (org_id, employee_id, kind, effective_from, applied_at, department_id, position_id, title_id, location_id, manager_id,
                                  employment_type_id, status, reason)
select org_id, id, 'JOIN', joining_date, now(), department_id, designation_id, title_id, location_id, manager_id, employment_type_id, status, 'Joined'
from employee;

create table employee_exit (
    employee_id           uuid primary key references employee (id),
    type                  text        not null check (type in ('RESIGNATION', 'TERMINATION', 'CONTRACT_END', 'RETIREMENT', 'ABSCONDING')),
    notice_date           date        not null,
    last_working_day      date        not null,
    notice_shortfall_days int         not null default 0,
    reason                text,
    eligible_for_rehire   boolean,
    previous_status       text        not null,
    created_by            uuid,
    created_at            timestamptz not null default now(),
    withdrawn_at          timestamptz,
    completed_at          timestamptz
);

create table employee_document (
    id           uuid primary key default gen_random_uuid(),
    org_id       uuid        not null references org (id),
    employee_id  uuid        not null references employee (id),
    name         text        not null,
    category     text        not null,
    storage_key  text        not null,
    file_name    text        not null,
    mime         text        not null,
    size_bytes   int         not null check (size_bytes <= 10485760),
    sha256       text        not null,
    status       text        not null default 'IN_REVIEW' check (status in ('IN_REVIEW', 'VERIFIED', 'REJECTED')),
    note         text,
    source       text        not null default 'UPLOAD' check (source in ('UPLOAD', 'ONBOARDING', 'PAYROLL', 'LETTER')),
    uploaded_by  uuid,
    uploaded_at  timestamptz not null default now(),
    verified_by  uuid,
    verified_at  timestamptz,
    deleted_at   timestamptz
);
create index employee_document_emp on employee_document (employee_id) where deleted_at is null;

create table employee_prev_employment (
    id                 uuid primary key default gen_random_uuid(),
    org_id             uuid        not null references org (id),
    employee_id        uuid        not null references employee (id),
    company            text        not null,
    title              text,
    location           text,
    from_date          date,
    to_date            date,
    reason_for_leaving text,
    pf_member          boolean,
    verified           boolean     not null default false,
    verified_by        uuid,
    sort               int         not null default 0,
    check (to_date is null or from_date is null or to_date >= from_date)
);
create index employee_prev_employment_emp on employee_prev_employment (employee_id, sort);

create table user_preference (
    employee_id uuid        not null references employee (id) on delete cascade,
    key         text        not null,
    value       jsonb       not null,
    updated_at  timestamptz not null default now(),
    primary key (employee_id, key)
);

create table export_job (
    id           uuid primary key default gen_random_uuid(),
    org_id       uuid        not null references org (id),
    requested_by uuid        not null,
    kind         text        not null,
    params       jsonb       not null,
    state        text        not null default 'QUEUED' check (state in ('QUEUED', 'RUNNING', 'DONE', 'FAILED')),
    storage_key  text,
    rows         int,
    error        text,
    created_at   timestamptz not null default now(),
    finished_at  timestamptz
);
