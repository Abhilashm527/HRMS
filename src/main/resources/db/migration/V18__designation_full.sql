-- Positions (see POSITIONS_BACKEND_GUIDE.md). "Position" in the UI is the designation table.

alter table designation
    add column description  text,
    add column job_level_id uuid references job_level (id);

-- case-insensitive uniqueness replaces unique(org_id, title)
alter table designation drop constraint if exists designation_org_id_title_key;
create unique index ux_designation_title on designation (org_id, lower(title));
create unique index ux_designation_code  on designation (org_id, upper(code)) where code is not null;

-- the track is stored as an enum text; the UI labels are mapped in the service
update designation set track = 'EXECUTIVE'  where lower(track) = 'executive';
update designation set track = 'MANAGEMENT' where lower(track) = 'management';
update designation set track = 'INDIVIDUAL_CONTRIBUTOR' where track is null or lower(track) = 'individual contributor';
alter table designation alter column track set default 'INDIVIDUAL_CONTRIBUTOR',
                        alter column track set not null;
alter table designation add constraint ck_designation_track
    check (track in ('EXECUTIVE', 'MANAGEMENT', 'INDIVIDUAL_CONTRIBUTOR'));

-- bands were typed in lakhs; they are now full yearly amounts in the org currency (INR orgs only)
update designation d set band_min = band_min * 100000, band_max = band_max * 100000
  from org o
 where o.id = d.org_id and coalesce(o.currency, 'INR') = 'INR' and d.band_max is not null and d.band_max < 10000;

alter table designation add constraint ck_designation_band
    check ((band_min is null and band_max is null) or (band_min >= 0 and band_max >= band_min));
alter table designation add constraint ck_designation_not_self check (reports_to_id is null or reports_to_id <> id);
alter table designation add constraint ck_designation_level check (level is null or level between 1 and 20);

-- exactly one active top position per org: where several active positions have no reports-to, the most senior one
-- stays the top and the others report to it
with ranked as (
    select id, org_id,
           row_number() over (partition by org_id order by (track = 'EXECUTIVE') desc, level desc nulls last, title) as rn
      from designation where active and reports_to_id is null)
update designation d
   set reports_to_id = (select t.id from ranked t where t.org_id = d.org_id and t.rn = 1)
  from ranked r
 where r.id = d.id and r.rn > 1;
-- an exclusion constraint (not a unique index) so it can be deferred: swapping the top in one save is valid at commit
alter table designation add constraint ex_designation_one_top
    exclude using btree (org_id with =) where (reports_to_id is null and active) deferrable initially deferred;

alter table org_settings add column out_of_band_needs_approval boolean not null default true;

create index if not exists idx_designation_reports_to on designation (reports_to_id);
create index if not exists idx_designation_job_level  on designation (job_level_id);
create index if not exists idx_employee_designation   on employee (designation_id);
create index if not exists idx_job_title_designation  on job_title (designation_id);
-- no ON DELETE CASCADE: deletes are guarded in the service

-- custom fields on a position (Settings > Onboarding > Custom fields, entity "Position") live in the config module
insert into config_area (key, name, module, description, icon, sort, perm_module)
values ('position', 'Positions', 'org', 'Extra fields on positions — shown on the position and in the ladder.',
        'briefcase', 101, 'org')
on conflict (key) do nothing;

insert into config_section (org_id, area_key, key, name, sort, system, locked, show_in_portal)
values ('00000000-0000-0000-0000-000000000001', 'position', 'custom', 'Additional details', 10, false, false, false)
on conflict do nothing;

insert into area_owner_role (org_id, area_key, role_id)
select r.org_id, 'position', r.id from role r
where r.org_id = '00000000-0000-0000-0000-000000000001' and r.key = 'hr_admin'
on conflict do nothing;
