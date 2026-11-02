-- Self-service attendance: asking to fix a day, and weekly timesheets by project.
create table attendance_regularisation (
    id          uuid primary key default gen_random_uuid(),
    org_id      uuid        not null references org (id),
    employee_id uuid        not null references employee (id),
    day         date        not null,
    in_time     time,
    out_time    time,
    wfh         boolean     not null default false,
    reason      text        not null,
    status      text        not null default 'PENDING' check (status in ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    approver_id uuid,
    decided_by  uuid,
    decided_at  timestamptz,
    decision_note text,
    created_at  timestamptz not null default now()
);
create unique index ux_regularisation_pending on attendance_regularisation (employee_id, day) where status = 'PENDING';
create index idx_regularisation_approver on attendance_regularisation (org_id, status, approver_id);

create table timesheet (
    id           uuid primary key default gen_random_uuid(),
    org_id       uuid        not null references org (id),
    employee_id  uuid        not null references employee (id),
    week_start   date        not null check (extract(isodow from week_start) = 1),
    status       text        not null default 'DRAFT' check (status in ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED')),
    approver_id  uuid,
    submitted_at timestamptz,
    decided_by   uuid,
    decided_at   timestamptz,
    decision_note text,
    updated_at   timestamptz not null default now(),
    unique (employee_id, week_start)
);
create index idx_timesheet_approver on timesheet (org_id, status, approver_id);

create table timesheet_entry (
    timesheet_id uuid         not null references timesheet (id) on delete cascade,
    project_id   uuid         not null references project (id),
    day          date         not null,
    hours        numeric(4,2) not null check (hours > 0 and hours <= 24),
    primary key (timesheet_id, project_id, day)
);
