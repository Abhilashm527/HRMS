-- Calendar events: rooms, events on layers (company, personal, team ...), invitees, RSVPs, the event history and sent reminders.
-- Holidays, birthdays, work anniversaries and leave are not stored here: the calendar feed reads them from their own tables.

create table calendar_room (
    id          uuid primary key default gen_random_uuid(),
    org_id      uuid        not null references org (id),
    name        text        not null,
    capacity    int         not null default 0 check (capacity >= 0),
    location_id uuid references location (id) on delete set null,
    active      boolean     not null default true,
    sort        int         not null default 0,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    updated_by  uuid,
    version     bigint      not null default 0
);
create unique index ux_calendar_room_name on calendar_room (org_id, lower(name));

create table calendar_event (
    id             uuid primary key default gen_random_uuid(),
    org_id         uuid        not null references org (id),
    layer          text        not null check (layer in ('COMPANY', 'MY', 'TEAM', 'DEPT', 'LOC', 'HR', 'REC', 'FIN')),
    type_id        uuid        not null references calendar_event_type (id),
    title          text        not null check (length(title) between 1 and 200),
    starts_on      date        not null,
    start_time     time,
    end_time       time,
    time_zone      text,
    where_text     text,
    room_id        uuid references calendar_room (id) on delete set null,
    audience       text        not null check (audience in ('ALL', 'ME', 'TEAM', 'DEPT', 'LOC', 'PEOPLE', 'ROLE')),
    audience_ref   uuid,
    audience_roles text[]      not null default '{}',
    private        boolean     not null default false,
    sensitive      boolean     not null default false,
    repeat         text        not null default 'NONE' check (repeat in ('NONE', 'WEEKLY', 'MONTHLY')),
    repeat_until   date,
    remind         text        not null default 'NONE' check (remind in ('NONE', '15M', '1H', '1D')),
    notes          text,
    status         text        not null default 'LIVE' check (status in ('LIVE', 'PENDING', 'REJECTED')),
    organiser_id   uuid        not null references employee (id),
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    updated_by     uuid,
    version        bigint      not null default 0,
    check (end_time is null or start_time is not null),
    check (end_time is null or end_time > start_time),
    check (repeat_until is null or repeat_until >= starts_on)
);
create index idx_calendar_event_range on calendar_event (org_id, starts_on, repeat_until);
create index idx_calendar_event_type on calendar_event (type_id);
create index idx_calendar_event_room on calendar_event (room_id) where room_id is not null;

create table calendar_event_person (
    event_id    uuid not null references calendar_event (id) on delete cascade,
    employee_id uuid not null references employee (id) on delete cascade,
    primary key (event_id, employee_id)
);
create index idx_calendar_event_person_emp on calendar_event_person (employee_id);

create table calendar_event_rsvp (
    event_id    uuid        not null references calendar_event (id) on delete cascade,
    employee_id uuid        not null references employee (id) on delete cascade,
    answer      text        not null check (answer in ('YES', 'MAYBE', 'NO')),
    answered_at timestamptz not null default now(),
    primary key (event_id, employee_id)
);

create table calendar_event_log (
    id       uuid primary key default gen_random_uuid(),
    event_id uuid        not null references calendar_event (id) on delete cascade,
    at       timestamptz not null default now(),
    by       uuid,
    action   text        not null
);
create index idx_calendar_event_log on calendar_event_log (event_id, at);

create table calendar_reminder_sent (
    event_id  uuid not null references calendar_event (id) on delete cascade,
    occurs_on date not null,
    sent_at   timestamptz not null default now(),
    primary key (event_id, occurs_on)
);

insert into calendar_room (org_id, name, capacity, location_id, sort)
select '00000000-0000-0000-0000-000000000001', r.name, r.cap,
       (select id from location where org_id = '00000000-0000-0000-0000-000000000001' and (city ilike r.city or name ilike r.city || '%') order by name limit 1), r.sort
from (values ('Board room', 14, 'Bengaluru', 10), ('Auditorium', 120, 'Bengaluru', 20), ('Training room', 30, 'Bengaluru', 30),
             ('Huddle 1', 4, 'Bengaluru', 40)) as r(name, cap, city, sort);
