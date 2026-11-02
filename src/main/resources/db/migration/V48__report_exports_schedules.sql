-- Reports: every export is kept for 7 days so it can be downloaded again; schedules email a report on a timetable.
create table report_export (
    id          uuid primary key default gen_random_uuid(),
    org_id      uuid        not null references org (id),
    report_key  text        not null,
    params      text        not null,
    format      text        not null,
    run_by      uuid references employee (id),
    schedule_id uuid,
    run_at      timestamptz not null default now(),
    row_count   int,
    file_key    text,
    file_name   text,
    status      text        not null default 'READY' check (status in ('READY', 'FAILED')),
    error       text
);
create index idx_report_export_org on report_export (org_id, run_at desc);

create table report_schedule (
    id           uuid primary key default gen_random_uuid(),
    org_id       uuid        not null references org (id),
    report_key   text        not null,
    frequency    text        not null check (frequency in ('DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY')),
    day_of_month text        not null default '1',
    weekday      text        not null default 'Mon' check (weekday in ('Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun')),
    at_time      time        not null default '09:00',
    recipients   text        not null,
    format       text        not null,
    enabled      boolean     not null default true,
    next_run_at  timestamptz not null,
    last_run_at  timestamptz,
    last_status  text check (last_status in ('DELIVERED', 'FAILED')),
    last_error   text,
    created_by   uuid        not null references employee (id),
    created_at   timestamptz not null default now()
);
create index idx_report_schedule_due on report_schedule (next_run_at) where enabled;

alter table notification_email
    add column attachment_key  text,
    add column attachment_name text;
