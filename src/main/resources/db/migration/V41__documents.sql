-- Documents: the company's shared files in a folder tree, as in the UX's Documents page.
-- A folder's access applies to everything inside it unless a subfolder sets its own.
create table doc_folder (
    id         uuid primary key default gen_random_uuid(),
    org_id     uuid        not null references org (id),
    parent_id  uuid references doc_folder (id) on delete cascade,
    name       text        not null check (length(trim(name)) between 1 and 120),
    access     text check (access in ('ADMINS', 'HR', 'FINANCE', 'IT', 'EMPLOYEES')),
    created_by uuid,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create unique index ux_doc_folder_name on doc_folder (org_id, coalesce(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name));
create index idx_doc_folder_parent on doc_folder (parent_id);

create table doc_file (
    id          uuid primary key default gen_random_uuid(),
    org_id      uuid        not null references org (id),
    folder_id   uuid        not null references doc_folder (id) on delete cascade,
    name        text        not null check (length(trim(name)) between 1 and 200),
    storage_key text        not null,
    mime        text        not null,
    size_bytes  bigint      not null,
    sha256      text        not null,
    uploaded_by uuid,
    uploaded_at timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);
create unique index ux_doc_file_name on doc_file (folder_id, lower(name));
create index idx_doc_file_org on doc_file (org_id, uploaded_at desc);

-- Stars are per person.
create table doc_star (
    employee_id uuid not null,
    item_id     uuid not null,
    created_at  timestamptz not null default now(),
    primary key (employee_id, item_id)
);
