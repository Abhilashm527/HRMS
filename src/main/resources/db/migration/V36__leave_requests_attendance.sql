-- Leave requests, the leave ledger (balances), encashment, year-end, leave notifications, and attendance.
-- Builds on V24 (leave types, approval chain, leave_request_step) and V12 (locations, holiday calendars).

-- ================================================================== leave requests
create sequence leave_request_no;

create table leave_request (
    id           uuid primary key default gen_random_uuid(),
    org_id       uuid          not null references org (id),
    no           bigint        not null default nextval('leave_request_no'),
    employee_id  uuid          not null references employee (id),
    type_id      uuid          not null references leave_type (id),
    from_date    date          not null,
    to_date      date          not null,
    half_day     boolean       not null default false,
    days         numeric(6, 1) not null check (days > 0),
    reason       text          not null,
    has_document boolean       not null default false,
    status       text          not null default 'PENDING' check (status in ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    applied_at   timestamptz   not null default now(),
    applied_by   uuid          not null references employee (id),
    decided_at   timestamptz,
    decided_by   uuid references employee (id),
    decision_note text,
    version      bigint        not null default 0,
    check (to_date >= from_date),
    unique (org_id, no)
);
create index idx_leave_request_emp on leave_request (employee_id, from_date);
create index idx_leave_request_status on leave_request (org_id, status, applied_at desc);
create index idx_leave_request_dates on leave_request (org_id, from_date, to_date);

alter table leave_request_step
    add constraint fk_leave_request_step_request foreign key (request_id) references leave_request (id) on delete cascade;
create index idx_leave_request_step_approver on leave_request_step (approver_emp_id, state);

-- what happened to a request, in order: applied, each approval, notes, reminders, cancellation.
create table leave_request_event (
    id          uuid primary key default gen_random_uuid(),
    request_id  uuid        not null references leave_request (id) on delete cascade,
    at          timestamptz not null default now(),
    actor_id    uuid references employee (id),
    kind        text        not null check (kind in ('APPLIED', 'APPROVED', 'REJECTED', 'CANCELLED', 'STEP_APPROVED', 'NOTE', 'REMINDED', 'AUTO_APPROVED')),
    text        text
);
create index idx_leave_request_event on leave_request_event (request_id, at);

-- ================================================================== ledger
-- Every change to a balance is one row. A balance is the sum of the rows for (employee, type, year).
-- CREDIT rows carry a period key ('Y', 'M03', 'Q2', 'OPENING') so the lazy crediting never credits twice.
create table leave_ledger (
    id          uuid primary key default gen_random_uuid(),
    org_id      uuid          not null references org (id),
    employee_id uuid          not null references employee (id),
    type_id     uuid          not null references leave_type (id),
    year        int           not null,
    kind        text          not null check (kind in ('OPENING', 'CREDIT', 'USED', 'ADJUST', 'ENCASH', 'LAPSE', 'CARRY_OUT', 'REVERSAL')),
    days        numeric(7, 2) not null,
    period      text,
    ref_id      uuid,
    note        text,
    at          timestamptz   not null default now(),
    by_id       uuid
);
create index idx_leave_ledger_emp on leave_ledger (employee_id, year, type_id);
create index idx_leave_ledger_ref on leave_ledger (ref_id);
create unique index leave_ledger_credit_once on leave_ledger (employee_id, type_id, year, period) where kind in ('CREDIT', 'OPENING') and period is not null;

-- ================================================================== encashment
create sequence leave_encash_no;

create table leave_encashment (
    id          uuid primary key default gen_random_uuid(),
    org_id      uuid          not null references org (id),
    no          bigint        not null default nextval('leave_encash_no'),
    employee_id uuid          not null references employee (id),
    type_id     uuid          not null references leave_type (id),
    year        int           not null,
    kind        text          not null check (kind in ('ON_REQUEST', 'EXIT', 'YEAR_END')),
    days        numeric(6, 1) not null check (days > 0),
    rate        numeric(12, 2) not null,
    amount      numeric(14, 2) not null,
    status      text          not null default 'PENDING' check (status in ('PENDING', 'APPROVED', 'PAID', 'REJECTED')),
    created_at  timestamptz   not null default now(),
    created_by  uuid,
    decided_at  timestamptz,
    decided_by  uuid,
    paid_at     timestamptz,
    note        text,
    unique (org_id, no)
);
create index idx_leave_encashment on leave_encashment (org_id, status, created_at desc);

-- ================================================================== year-end
create table leave_year_close (
    org_id     uuid        not null references org (id),
    year       int         not null,
    closed_at  timestamptz not null default now(),
    closed_by  uuid,
    encashed   boolean     not null default false,
    summary    jsonb       not null default '{}'::jsonb,
    primary key (org_id, year)
);

-- ================================================================== leave notifications (reminders to plan leave)
create table leave_notice (
    id           uuid primary key default gen_random_uuid(),
    org_id       uuid        not null references org (id),
    title        text        not null,
    body         text        not null,
    period_from  date,
    period_to    date,
    deadline     date,
    departments  uuid[]      not null default '{}',
    locations    uuid[]      not null default '{}',
    who          text        not null default 'ALL' check (who in ('ALL', 'LAPSE', 'BALANCE')),
    bal_type_id  uuid references leave_type (id),
    bal_min      numeric(6, 1),
    email        boolean     not null default true,
    send_at      timestamptz not null default now(),
    repeat       text        not null default 'NONE' check (repeat in ('NONE', 'WEEKLY')),
    status       text        not null default 'SCHEDULED' check (status in ('SCHEDULED', 'SENT', 'CANCELLED')),
    last_sent_at timestamptz,
    sent_count   int         not null default 0,
    recipients   int         not null default 0,
    created_at   timestamptz not null default now(),
    created_by   uuid
);
create index idx_leave_notice_due on leave_notice (status, send_at);

-- ================================================================== attendance
create table attendance_record (
    id          uuid primary key default gen_random_uuid(),
    org_id      uuid        not null references org (id),
    employee_id uuid        not null references employee (id),
    day         date        not null,
    in_at       timestamptz,
    out_at      timestamptz,
    source      text        not null default 'WEB' check (source in ('WEB', 'MOBILE_GPS', 'BIOMETRIC', 'QR', 'MANUAL')),
    wfh         boolean     not null default false,
    note        text,
    updated_at  timestamptz not null default now(),
    updated_by  uuid,
    unique (employee_id, day)
);
create index idx_attendance_day on attendance_record (org_id, day);
