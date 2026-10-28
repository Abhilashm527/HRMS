-- Authentication: credentials, 2-step, sessions, tokens, signing keys (see AUTH_BACKEND_GUIDE.md)

alter table app_user
    add column email       text,
    add column break_glass boolean     not null default false,
    add column invited_at  timestamptz,
    add column activated_at timestamptz;
update app_user u set email = lower(e.work_email), activated_at = u.created_at from employee e where e.id = u.employee_id;
alter table app_user alter column email set not null;
alter table app_user add constraint app_user_status_check check (status in ('INVITED', 'ACTIVE', 'DISABLED'));
create unique index ux_app_user_email on app_user (org_id, lower(email));

alter table org add column email_domains text[] not null default '{ivoyant.com}';
update org set email_domains = array[lower(primary_domain)] where primary_domain is not null and primary_domain <> '';

create table auth_credential (
    user_id              uuid primary key references app_user (id) on delete cascade,
    password_hash        text,
    password_set_at      timestamptz,
    failed_count         int         not null default 0,
    locked_until         timestamptz,
    last_failed_at       timestamptz,
    totp_secret_enc      bytea,
    totp_enrolled_at     timestamptz,
    totp_last_step       bigint,
    mfa_reset_required   boolean     not null default false,
    version              bigint      not null default 0
);

create table auth_password_history (
    user_id        uuid        not null references app_user (id) on delete cascade,
    password_hash  text        not null,
    set_at         timestamptz not null default now()
);
create index ix_auth_pw_history on auth_password_history (user_id, set_at desc);

create table auth_recovery_code (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid        not null references app_user (id) on delete cascade,
    code_hash   text        not null,
    used_at     timestamptz,
    created_at  timestamptz not null default now()
);
create index ix_auth_recovery_user on auth_recovery_code (user_id) where used_at is null;

create table auth_session (
    id                   uuid primary key default gen_random_uuid(),
    user_id              uuid        not null references app_user (id) on delete cascade,
    org_id               uuid        not null,
    method               text        not null check (method in ('PASSWORD', 'SSO', 'ACTIVATION', 'DEV')),
    amr                  text[]      not null,
    remember             boolean     not null,
    user_agent           text,
    device_label         text,
    ip                   inet,
    place                text,
    created_at           timestamptz not null default now(),
    last_active_at       timestamptz not null default now(),
    expires_at           timestamptz not null,
    absolute_expires_at  timestamptz not null,
    revoked_at           timestamptz,
    revoked_reason       text
);
create index ix_session_user on auth_session (user_id) where revoked_at is null;

create table auth_refresh_token (
    token_hash  text primary key,
    session_id  uuid        not null references auth_session (id) on delete cascade,
    family      uuid        not null,
    issued_at   timestamptz not null default now(),
    used_at     timestamptz,
    expires_at  timestamptz not null
);
create index ix_refresh_session on auth_refresh_token (session_id);

create table auth_token (
    token_hash  text primary key,
    user_id     uuid        not null references app_user (id) on delete cascade,
    purpose     text        not null check (purpose in ('RESET', 'ACTIVATE', 'MFA_CHALLENGE', 'MFA_ENROLL')),
    data        jsonb       not null default '{}',
    expires_at  timestamptz not null,
    used_at     timestamptz,
    created_at  timestamptz not null default now()
);
create index ix_auth_token_user on auth_token (user_id, purpose) where used_at is null;

create table auth_signing_key (
    kid          text primary key,
    private_enc  bytea       not null,
    public_jwk   jsonb       not null,
    created_at   timestamptz not null default now(),
    retire_at    timestamptz
);
