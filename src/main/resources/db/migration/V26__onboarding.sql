-- Onboarding runtime: hires, portal details, documents, invites, Microsoft 365, checklist, activity, ESS onboarding
-- (see ONBOARDING_BACKEND_GUIDE.md). Personal email / UPN uniqueness is case-insensitive through lower() indexes.

create table onboarding_hire (
    id                  uuid primary key default gen_random_uuid(),
    org_id              uuid        not null references org (id),
    first_name          text        not null,
    last_name           text        not null default '',
    personal_email      text        not null,
    phone               text,
    gender              text,
    dob                 date,
    title               text        not null,
    title_id            uuid references job_title (id),
    department_id       uuid        not null references department (id),
    manager_id          uuid        not null references employee (id),
    location_id         uuid        not null references location (id),
    employment_type_id  uuid        not null references employment_type (id),
    doj                 date        not null,
    probation_months    smallint,
    ctc_lakh            numeric(8, 2),
    status              text        not null default 'DRAFT'
        check (status in ('DRAFT', 'INVITED', 'IN_PROGRESS', 'REVIEW_PENDING', 'ACTION_NEEDED', 'READY_TO_JOIN', 'JOINED')),
    stages              boolean[]   not null default '{t,f,f,f,f,f}',
    details_submitted_at timestamptz,
    candidate_id        uuid,
    employee_id         uuid references employee (id),
    joined_at           timestamptz,
    cancelled_at        timestamptz,
    cancel_reason       text,
    invite_template_id  uuid references invite_template (id),
    version             bigint      not null default 0,
    created_at          timestamptz not null default now(),
    created_by          uuid,
    updated_at          timestamptz not null default now(),
    updated_by          uuid
);
create unique index onboarding_hire_email_active on onboarding_hire (org_id, lower(personal_email))
    where joined_at is null and cancelled_at is null;
create index onboarding_hire_list on onboarding_hire (org_id, status, doj) where cancelled_at is null;

create table onboarding_hire_detail (
    hire_id          uuid        not null references onboarding_hire (id) on delete cascade,
    field_key        text        not null,
    value            text,
    value_enc        bytea,
    updated_at       timestamptz not null default now(),
    updated_by_hire  boolean     not null default true,
    primary key (hire_id, field_key)
);

create table onboarding_hire_document (
    id            uuid primary key default gen_random_uuid(),
    hire_id       uuid        not null references onboarding_hire (id) on delete cascade,
    document_id   uuid        not null references onboarding_document (id),
    doc_key       text        not null,
    required      boolean     not null,
    status        text        not null default 'MISSING' check (status in ('MISSING', 'SUBMITTED', 'VERIFIED', 'REJECTED')),
    note          text,
    sort          int         not null default 0,
    requested_at  timestamptz not null default now(),
    requested_by  uuid,
    verified_at   timestamptz,
    verified_by   uuid,
    unique (hire_id, doc_key),
    check (status <> 'REJECTED' or note is not null)
);

create table onboarding_hire_file (
    id                 uuid primary key default gen_random_uuid(),
    hire_document_id   uuid        not null references onboarding_hire_document (id) on delete cascade,
    storage_key        text        not null,
    file_name          text        not null,
    mime               text        not null,
    size_bytes         int         not null check (size_bytes <= 5242880),
    sha256             text        not null,
    scan_state         text        not null default 'PENDING' check (scan_state in ('PENDING', 'CLEAN', 'INFECTED')),
    uploaded_at        timestamptz not null default now(),
    superseded_at      timestamptz
);
create unique index onboarding_hire_file_current on onboarding_hire_file (hire_document_id) where superseded_at is null;

create table onboarding_invite (
    hire_id            uuid primary key references onboarding_hire (id) on delete cascade,
    sent_to            text        not null,
    token_hash         bytea       not null unique,
    expires_at         timestamptz not null,
    first_sent_at      timestamptz not null,
    last_sent_at       timestamptz not null,
    reminders          int         not null default 0,
    last_reminder_at   timestamptz,
    due_date           date        not null,
    remind_every_days  int,
    revoked_at         timestamptz
);

create table onboarding_m365 (
    hire_id        uuid primary key references onboarding_hire (id) on delete cascade,
    upn            text        not null,
    license_sku    text        not null,
    groups         jsonb       not null default '[]',
    force_reset    boolean     not null default true,
    send_sms       boolean     not null default true,
    state          text        not null default 'NONE' check (state in ('NONE', 'RUNNING', 'ACTIVE', 'FAILED', 'DISABLED')),
    step           smallint    not null default 0,
    error          text,
    attempts       smallint    not null default 0,
    entra_user_id  text,
    simulated      boolean     not null default false,
    created_at     timestamptz,
    updated_at     timestamptz not null default now()
);
create unique index onboarding_m365_upn on onboarding_m365 (lower(upn)) where state <> 'DISABLED';

create table onboarding_task (
    id           uuid primary key default gen_random_uuid(),
    hire_id      uuid        not null references onboarding_hire (id) on delete cascade,
    template_id  uuid references onboarding_task_template (id) on delete set null,
    name         text        not null,
    phase        text        not null,
    owner        text        not null,
    assignee_id  uuid references employee (id),
    done         boolean     not null default false,
    done_at      timestamptz,
    done_by      uuid,
    sort         int         not null default 0
);
create index onboarding_task_hire on onboarding_task (hire_id, sort);

create table onboarding_event (
    id             bigserial primary key,
    hire_id        uuid        not null references onboarding_hire (id) on delete cascade,
    kind           text        not null check (kind in ('MAIL', 'M365', 'DOC', 'OK', 'BAD', 'GEN')),
    title          text        not null,
    description    text,
    actor_id       uuid,
    actor_is_hire  boolean     not null default false,
    at             timestamptz not null default now()
);
create index onboarding_event_hire on onboarding_event (hire_id, at desc);

create table employee_onboarding_task (
    employee_id  uuid        not null references employee (id) on delete cascade,
    task_key     text        not null check (task_key in ('docs', 'offer', 'profile', 'policies', 'laptop', 'm365', 'buddy', 'goals')),
    done         boolean     not null default false,
    done_at      timestamptz,
    primary key (employee_id, task_key)
);

create table onboarding_job_queue (
    id            bigserial primary key,
    org_id        uuid        not null,
    kind          text        not null check (kind in ('M365_PROVISION', 'JOIN', 'REMIND')),
    hire_id       uuid        not null,
    run_after     timestamptz not null default now(),
    attempts      int         not null default 0,
    locked_until  timestamptz,
    done_at       timestamptz,
    last_error    text
);
create index onboarding_job_queue_due on onboarding_job_queue (run_after) where done_at is null;

-- notification module: queued emails and the in-app inbox (delivery is a separate worker)
create table notification_email (
    id          bigserial primary key,
    org_id      uuid        not null,
    to_address  text        not null,
    cc_address  text,
    subject     text        not null,
    html        text        not null,
    created_at  timestamptz not null default now(),
    sent_at     timestamptz,
    error       text
);

create table notification_inbox (
    id           bigserial primary key,
    org_id       uuid        not null,
    employee_id  uuid        not null references employee (id) on delete cascade,
    title        text        not null,
    body         text,
    link         text,
    created_at   timestamptz not null default now(),
    read_at      timestamptz
);
create index notification_inbox_employee on notification_inbox (employee_id, created_at desc);
