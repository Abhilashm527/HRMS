-- Performance: review cycles, each person's self and manager review, and goals.
create table review_cycle (
    id            uuid primary key default gen_random_uuid(),
    org_id        uuid        not null references org (id),
    name          text        not null check (length(trim(name)) between 1 and 80),
    period_start  date        not null,
    period_end    date        not null check (period_end >= period_start),
    self_due      date        not null,
    manager_due   date        not null,
    status        text        not null default 'DRAFT' check (status in ('DRAFT', 'ACTIVE', 'CLOSED')),
    created_by    uuid,
    created_at    timestamptz not null default now(),
    launched_at   timestamptz,
    closed_at     timestamptz
);

create table review (
    id                  uuid primary key default gen_random_uuid(),
    cycle_id            uuid        not null references review_cycle (id) on delete cascade,
    employee_id         uuid        not null references employee (id),
    reviewer_id         uuid references employee (id),
    self_answers        jsonb       not null default '{}',
    self_rating         int check (self_rating between 1 and 5),
    self_submitted_at   timestamptz,
    manager_answers     jsonb       not null default '{}',
    manager_rating      int check (manager_rating between 1 and 5),
    manager_submitted_at timestamptz,
    final_rating        int check (final_rating between 1 and 5),
    final_note          text,
    unique (cycle_id, employee_id)
);
create index idx_review_reviewer on review (reviewer_id);

create table goal (
    id          uuid primary key default gen_random_uuid(),
    org_id      uuid        not null references org (id),
    employee_id uuid        not null references employee (id),
    title       text        not null check (length(trim(title)) between 1 and 160),
    detail      text,
    due_date    date,
    progress    int         not null default 0 check (progress between 0 and 100),
    status      text        not null default 'ACTIVE' check (status in ('ACTIVE', 'DONE', 'DROPPED')),
    created_by  uuid,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);
create index idx_goal_employee on goal (employee_id);
