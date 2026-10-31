-- Employee requests from self-service: letters (HR issues), reimbursements (manager, then finance), helpdesk tickets (the type's team),
-- plus the yearly tax declaration that HR verifies. Profile changes stay in config_change_request.

create sequence employee_request_no start 801;

create table employee_request (
    id             uuid primary key default gen_random_uuid(),
    org_id         uuid          not null references org (id),
    no             bigint        not null default nextval('employee_request_no'),
    employee_id    uuid          not null references employee (id),
    kind           text          not null check (kind in ('LETTER', 'REIMBURSEMENT', 'HELPDESK')),
    type_id        uuid          not null references request_type (id),
    title          text,
    note           text          not null default '',
    amount         numeric(12, 2) check (amount is null or amount > 0),
    expense_date   date,
    receipt_key    text,
    receipt_name   text,
    receipt_mime   text,
    letter_key     text,
    letter_name    text,
    letter_mime    text,
    stage          text          not null check (stage in ('MANAGER', 'FINANCE', 'HR', 'TEAM', 'DONE')),
    status         text          not null check (status in ('PENDING', 'OPEN', 'ISSUED', 'PAID', 'RESOLVED', 'REJECTED', 'WITHDRAWN')),
    manager_id     uuid references employee (id),
    decided_by     uuid references employee (id),
    decided_at     timestamptz,
    decision_note  text,
    created_at     timestamptz   not null default now(),
    updated_at     timestamptz   not null default now(),
    version        bigint        not null default 0,
    unique (org_id, no)
);
create index idx_employee_request_emp on employee_request (employee_id, created_at desc);
create index idx_employee_request_queue on employee_request (org_id, kind, status, stage);
create index idx_employee_request_type on employee_request (type_id);

create table employee_request_event (
    id         uuid primary key default gen_random_uuid(),
    request_id uuid        not null references employee_request (id) on delete cascade,
    at         timestamptz not null default now(),
    by         uuid references employee (id),
    action     text        not null,
    note       text
);
create index idx_employee_request_event on employee_request_event (request_id, at);

create table tax_declaration (
    id            uuid primary key default gen_random_uuid(),
    org_id        uuid           not null references org (id),
    employee_id   uuid           not null references employee (id),
    fy            text           not null check (fy ~ '^\d{4}-\d{2}$'),
    regime        text           not null default 'NEW' check (regime in ('NEW', 'OLD')),
    sec80c        numeric(12, 2) not null default 0 check (sec80c >= 0),
    sec80d        numeric(12, 2) not null default 0 check (sec80d >= 0),
    rent_monthly  numeric(12, 2) not null default 0 check (rent_monthly >= 0),
    nps           numeric(12, 2) not null default 0 check (nps >= 0),
    home_loan     numeric(12, 2) not null default 0 check (home_loan >= 0),
    status        text           not null default 'DRAFT' check (status in ('DRAFT', 'SUBMITTED', 'VERIFIED', 'REJECTED')),
    submitted_at  timestamptz,
    decided_by    uuid references employee (id),
    decided_at    timestamptz,
    decision_note text,
    updated_at    timestamptz    not null default now(),
    version       bigint         not null default 0,
    unique (employee_id, fy)
);
create index idx_tax_declaration_queue on tax_declaration (org_id, status);
