-- Fields & forms: per-area wiring (see FIELDS_AND_FORMS_AREAS_GUIDE.md)

-- an onboarding (new hire) field can be copied to the profile field of this key when the hire joins
alter table config_field add column profile_field_key text;
alter table config_field add constraint ck_profile_key_area check (profile_field_key is null or area_key = 'onboarding');

-- EMPLOYMENT_TYPE values: the type's code or name -> the type's id
update config_field_value v set value = to_jsonb(et.id::text)
from config_field f, employment_type et
where f.id = v.field_id and f.type = 'EMPLOYMENT_TYPE'
  and et.org_id = v.org_id and jsonb_typeof(v.value) = 'string'
  and (upper(et.code) = upper(v.value #>> '{}') or lower(et.name) = lower(v.value #>> '{}'));

-- which field types each area offers (was hard-coded)
create table config_area_type (
    area_key text not null references config_area (key),
    type     text not null,
    primary key (area_key, type)
);
insert into config_area_type (area_key, type)
select a.key, t from config_area a, unnest(array['TEXT', 'TEXTAREA', 'NUMBER', 'DATE', 'EMAIL', 'PHONE', 'SELECT', 'MULTISELECT', 'BOOLEAN', 'FILE']) t;
insert into config_area_type (area_key, type) values
 ('employee_profile', 'HEADING'),
 ('onboarding', 'DEPARTMENT'), ('onboarding', 'EMPLOYEE'), ('onboarding', 'LOCATION'), ('onboarding', 'EMPLOYMENT_TYPE')
on conflict do nothing;

-- sensible starting access for the two areas whose record is the employee (a role keeps whatever was set already)
insert into config_field_access (field_id, role_id, own_access, others_access)
select f.id, r.id, v.own, v.others
from config_field f
join (values
  ('payroll',     'payment_mode',        'finance',  null,   'EDIT'),
  ('payroll',     'payment_mode',        'hr_admin', null,   'EDIT'),
  ('payroll',     'payment_mode',        'employee', 'VIEW', null),
  ('payroll',     'salary_account',      'finance',  null,   'EDIT'),
  ('payroll',     'salary_account',      'hr_admin', null,   'EDIT'),
  ('payroll',     'salary_account',      'employee', 'MASKED', null),
  ('performance', 'career_aspiration',   'hr_admin', null,   'EDIT'),
  ('performance', 'career_aspiration',   'manager',  null,   'EDIT'),
  ('performance', 'career_aspiration',   'employee', 'EDIT', null),
  ('performance', 'promotion_ready',     'hr_admin', null,   'EDIT'),
  ('performance', 'promotion_ready',     'manager',  null,   'EDIT'),
  ('performance', 'promotion_ready',     'employee', 'VIEW', null)
) as v(area, field_key, role_key, own, others) on v.area = f.area_key and v.field_key = f.key
join role r on r.org_id = f.org_id and r.key = v.role_key
on conflict do nothing;
