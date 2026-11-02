-- Payroll: each person's salary, monthly pay runs and their payslips.
create table payroll_settings (
    org_id            uuid primary key references org (id),
    basic_pct         numeric(5,2) not null default 40 check (basic_pct between 10 and 100),
    hra_pct_of_basic  numeric(5,2) not null default 50 check (hra_pct_of_basic between 0 and 100),
    pf_rate           numeric(5,2) not null default 12,
    pf_wage_cap       numeric(12,2) not null default 15000,
    esi_threshold     numeric(12,2) not null default 21000,
    esi_employee_rate numeric(5,2) not null default 0.75,
    esi_employer_rate numeric(5,2) not null default 3.25,
    pay_day           int          not null default 30 check (pay_day between 1 and 31),
    updated_at        timestamptz  not null default now()
);

-- What a person is paid. Annual CTC in rupees; the monthly gross is CTC / 12.
create table salary (
    employee_id    uuid primary key references employee (id),
    org_id         uuid          not null references org (id),
    ctc_annual     numeric(14,2) not null check (ctc_annual > 0),
    regime         text          not null default 'NEW' check (regime in ('NEW', 'OLD')),
    pf_enabled     boolean       not null default true,
    effective_from date          not null default current_date,
    updated_at     timestamptz   not null default now(),
    updated_by     uuid
);

create table pay_run (
    id           uuid primary key default gen_random_uuid(),
    org_id       uuid        not null references org (id),
    period       date        not null check (extract(day from period) = 1),
    status       text        not null default 'DRAFT' check (status in ('DRAFT', 'REVIEWED', 'APPROVED', 'PAID')),
    pay_date     date        not null,
    created_by   uuid,
    created_at   timestamptz not null default now(),
    reviewed_by  uuid,
    reviewed_at  timestamptz,
    approved_by  uuid,
    approved_at  timestamptz,
    paid_by      uuid,
    paid_at      timestamptz,
    unique (org_id, period)
);

create table payslip (
    id               uuid primary key default gen_random_uuid(),
    run_id           uuid          not null references pay_run (id) on delete cascade,
    employee_id      uuid          not null references employee (id),
    days_in_month    int           not null,
    paid_days        numeric(5,1)  not null,
    lop_days         numeric(5,1)  not null default 0,
    extra_lop        numeric(5,1)  not null default 0,
    earnings         jsonb         not null,
    deductions       jsonb         not null,
    employer         jsonb         not null,
    adjustments      jsonb         not null default '[]',
    gross            numeric(14,2) not null,
    total_deductions numeric(14,2) not null,
    net              numeric(14,2) not null,
    pt_state         text,
    regime           text          not null,
    flags            jsonb         not null default '[]',
    unique (run_id, employee_id)
);
create index idx_payslip_employee on payslip (employee_id);
