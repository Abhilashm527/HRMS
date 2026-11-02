-- Recruiting: job openings, candidates moving through stages, interviews with panel feedback, and offers.
create sequence job_opening_no start 1;

create table job_opening (
    id                 uuid primary key default gen_random_uuid(),
    org_id             uuid          not null references org (id),
    no                 bigint        not null default nextval('job_opening_no'),
    title              text          not null check (length(trim(title)) between 1 and 120),
    department_id      uuid references department (id),
    location_id        uuid references location (id),
    openings           int           not null default 1 check (openings between 1 and 500),
    experience         text,
    budget_min         numeric(8,2),
    budget_max         numeric(8,2),
    hiring_manager_id  uuid references employee (id),
    recruiter_id       uuid references employee (id),
    target_date        date,
    description        text,
    skills             text,
    status             text          not null default 'OPEN' check (status in ('DRAFT', 'OPEN', 'ON_HOLD', 'CLOSED')),
    created_by         uuid,
    created_at         timestamptz   not null default now(),
    closed_at          timestamptz
);

create table candidate (
    id               uuid primary key default gen_random_uuid(),
    org_id           uuid          not null references org (id),
    job_id           uuid          not null references job_opening (id) on delete cascade,
    name             text          not null check (length(trim(name)) between 1 and 120),
    email            text,
    phone            text,
    source           text          not null default 'Careers page',
    referred_by      uuid references employee (id),
    current_company  text,
    current_title    text,
    experience_years numeric(4,1),
    notice_days      int,
    expected_ctc     numeric(8,2),
    stage            text          not null default 'APPLIED' check (stage in ('APPLIED', 'SCREENING', 'INTERVIEW', 'OFFER', 'HIRED', 'REJECTED')),
    rejected_reason  text,
    resume_key       text,
    resume_name      text,
    notes            text,
    created_by       uuid,
    created_at       timestamptz   not null default now(),
    stage_changed_at timestamptz   not null default now()
);
create index idx_candidate_job on candidate (job_id, stage);

create table interview (
    id            uuid primary key default gen_random_uuid(),
    candidate_id  uuid          not null references candidate (id) on delete cascade,
    round         text          not null,
    scheduled_at  timestamptz   not null,
    duration_min  int           not null default 45 check (duration_min between 10 and 480),
    mode          text          not null default 'Video' check (mode in ('Video', 'In person', 'Phone')),
    place         text,
    panel         uuid[]        not null default '{}',
    status        text          not null default 'SCHEDULED' check (status in ('SCHEDULED', 'DONE', 'CANCELLED')),
    created_by    uuid,
    created_at    timestamptz   not null default now()
);
create index idx_interview_time on interview (scheduled_at);

create table interview_feedback (
    interview_id   uuid        not null references interview (id) on delete cascade,
    employee_id    uuid        not null references employee (id),
    rating         int         not null check (rating between 1 and 5),
    recommendation text        not null check (recommendation in ('STRONG_YES', 'YES', 'NO', 'STRONG_NO')),
    notes          text,
    submitted_at   timestamptz not null default now(),
    primary key (interview_id, employee_id)
);

create table job_offer (
    id            uuid primary key default gen_random_uuid(),
    candidate_id  uuid          not null references candidate (id) on delete cascade,
    ctc           numeric(8,2)  not null check (ctc > 0),
    joining_date  date          not null,
    note          text,
    status        text          not null default 'SENT' check (status in ('SENT', 'ACCEPTED', 'DECLINED', 'WITHDRAWN')),
    created_by    uuid,
    created_at    timestamptz   not null default now(),
    decided_at    timestamptz
);

create table candidate_event (
    id           uuid primary key default gen_random_uuid(),
    candidate_id uuid        not null references candidate (id) on delete cascade,
    text         text        not null,
    actor_id     uuid,
    at           timestamptz not null default now()
);
create index idx_candidate_event on candidate_event (candidate_id, at desc);
