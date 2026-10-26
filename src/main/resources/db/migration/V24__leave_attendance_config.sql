-- Leave & attendance configuration: holiday types, leave types, the leave approval chain
-- (see LEAVE_ATTENDANCE_CONFIG_BACKEND_GUIDE.md)

-- ================================================================== C: holiday types
create table holiday_type (
    id         uuid primary key default gen_random_uuid(),
    org_id     uuid        not null references org (id),
    name       text        not null,
    fg         text        not null check (fg ~ '^#[0-9A-Fa-f]{6}$'),
    bg         text        not null check (bg ~ '^#[0-9A-Fa-f]{6}$'),
    day_off    boolean     not null default true,
    sort       int         not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    updated_by uuid
);
create unique index holiday_type_name_ci on holiday_type (org_id, lower(name));

insert into holiday_type (org_id, name, fg, bg, day_off, sort)
select o.id, v.name, v.fg, v.bg, v.day_off, v.sort
from org o cross join (values ('National', '#1D4ED8', '#DBEAFE', true, 0), ('Regional', '#0F766E', '#CCFBF1', true, 1),
                              ('Optional', '#B45309', '#FEF3C7', false, 2)) as v(name, fg, bg, day_off, sort);

alter table holiday add column type_id uuid references holiday_type (id);
update holiday h set type_id = ht.id
  from holiday_calendar c, holiday_type ht
 where c.id = h.calendar_id and ht.org_id = c.org_id and upper(ht.name) = h.kind;
alter table holiday alter column type_id set not null;
alter table holiday drop column kind;
create index idx_holiday_type on holiday (type_id);

create table holiday_year_lock (
    calendar_id uuid        not null references holiday_calendar (id) on delete cascade,
    year        int         not null,
    closed_at   timestamptz not null default now(),
    closed_by   uuid        not null,
    primary key (calendar_id, year)
);

-- ================================================================== A: leave types
create table leave_type (
    id               uuid primary key default gen_random_uuid(),
    org_id           uuid        not null references org (id),
    code             text        not null check (code ~ '^[A-Z]{2,4}$'),
    name             text        not null,
    short_label      text        not null,
    color_index      smallint    not null default 0 check (color_index between 0 and 9),
    active           boolean     not null default true,
    system           boolean     not null default false,
    accrual          text        not null check (accrual in ('YEARLY', 'MONTHLY', 'QUARTERLY', 'EVENT', 'EARNED')),
    quota_days       numeric(6, 1),
    carry            text        not null default 'NONE' check (carry in ('NONE', 'UPTO', 'ALL')),
    carry_max        numeric(6, 1) not null default 0,
    expiry_days      int         not null default 0,
    encashable       boolean     not null default false,
    encash_max       numeric(6, 1) not null default 0,
    count_as         text        not null default 'WORKING' check (count_as in ('WORKING', 'CALENDAR')),
    paid             boolean     not null default true,
    gender           text        not null default 'ALL' check (gender in ('ALL', 'FEMALE', 'MALE')),
    half_day         boolean     not null default true,
    notice_days      int         not null default 0,
    max_consecutive  int         not null default 0,
    doc_after_days   int         not null default 0,
    min_service_days int         not null default 0,
    sort             int         not null default 0,
    version          bigint      not null default 0,
    created_at       timestamptz not null default now(),
    created_by       uuid,
    updated_at       timestamptz not null default now(),
    updated_by       uuid,
    unique (org_id, code),
    check (accrual = 'EARNED' or quota_days is not null),
    check (carry = 'UPTO' or carry_max = 0)
);
create unique index leave_type_name_ci on leave_type (org_id, lower(name));

insert into leave_type (org_id, code, name, short_label, color_index, system, accrual, quota_days, carry, carry_max, expiry_days, encashable,
                        encash_max, count_as, gender, half_day, notice_days, max_consecutive, doc_after_days, min_service_days, sort)
select o.id, v.code, v.name, v.short_label, v.color, true, v.accrual, v.quota, v.carry, v.carry_max, v.expiry, v.encash, v.encash_max,
       v.count_as, v.gender, v.half, v.notice, v.max_run, v.doc, v.service, v.sort
from org o cross join (values
  ('CL', 'Casual leave',    'Casual',    0, 'YEARLY',    12.0, 'NONE',  0.0, 0,  false, 0.0,  'WORKING',  'ALL',    true,  1, 3, 0, 0,  10),
  ('SL', 'Sick leave',      'Sick',      1, 'MONTHLY',   12.0, 'UPTO',  6.0, 0,  false, 0.0,  'WORKING',  'ALL',    true,  0, 0, 2, 0,  20),
  ('EL', 'Earned leave',    'Earned',    2, 'MONTHLY',   18.0, 'UPTO', 30.0, 0,  true,  15.0, 'WORKING',  'ALL',    true,  7, 0, 0, 90, 30),
  ('CO', 'Comp-off',        'Comp-off',  3, 'EARNED',    null, 'NONE',  0.0, 60, false, 0.0,  'WORKING',  'ALL',    true,  0, 0, 0, 0,  40),
  ('ML', 'Maternity leave', 'Maternity', 4, 'EVENT',    182.0, 'NONE',  0.0, 0,  false, 0.0,  'CALENDAR', 'FEMALE', false, 0, 0, 1, 80, 50),
  ('PL', 'Paternity leave', 'Paternity', 5, 'EVENT',     10.0, 'NONE',  0.0, 0,  false, 0.0,  'WORKING',  'MALE',   false, 7, 0, 0, 0,  60)
) as v(code, name, short_label, color, accrual, quota, carry, carry_max, expiry, encash, encash_max, count_as, gender, half, notice, max_run, doc, service, sort);

-- ================================================================== B: leave approval chain
create table leave_approval_step (
    id        uuid primary key default gen_random_uuid(),
    org_id    uuid    not null references org (id),
    sort      int     not null,
    approver  text    not null check (approver in ('REPORTING_MANAGER', 'SKIP_LEVEL_MANAGER', 'DEPARTMENT_HEAD', 'HR')),
    condition text    not null default 'ALWAYS' check (condition in ('ALWAYS', 'OVER_DAYS')),
    min_days  numeric(5, 1),
    unique (org_id, sort),
    check (condition = 'ALWAYS' or min_days is not null)
);
create table leave_chain_version (
    org_id     uuid primary key references org (id),
    version    bigint      not null default 0,
    updated_at timestamptz not null default now(),
    updated_by uuid
);
insert into leave_approval_step (org_id, sort, approver, condition, min_days)
select o.id, v.sort, v.approver, v.condition, v.min_days
from org o cross join (values (1, 'REPORTING_MANAGER', 'ALWAYS', null::numeric), (2, 'DEPARTMENT_HEAD', 'OVER_DAYS', 3.0), (3, 'HR', 'ALWAYS', null::numeric))
     as v(sort, approver, condition, min_days);
insert into leave_chain_version (org_id) select id from org;

-- the chain a request was resolved to at submit; it never changes when the chain is edited later.
-- (request_id gets its foreign key to leave_request when the requests table is built)
create table leave_request_step (
    id            uuid primary key default gen_random_uuid(),
    request_id    uuid    not null,
    sort          int     not null,
    approver      text    not null,
    approver_emp_id uuid  not null references employee (id),
    delegated_from uuid references employee (id),
    state         text    not null default 'WAITING' check (state in ('WAITING', 'PENDING', 'APPROVED', 'REJECTED', 'SKIPPED')),
    acted_at      timestamptz,
    note          text,
    unique (request_id, sort)
);
