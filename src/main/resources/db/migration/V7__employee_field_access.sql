-- More system fields of the employee profile (the remaining governed employee columns)
insert into config_field (org_id, area_key, section_id, key, label, type, required, options, sort, system)
select '00000000-0000-0000-0000-000000000001', 'employee_profile', s.id, f.key, f.label, f.type, f.required,
       f.options::jsonb, f.sort, true
from (values
    ('basic', 'personal_email', 'Personal email', 'EMAIL',  false, null, 40),
    ('basic', 'phone',          'Phone',          'PHONE',  false, null, 50),
    ('work',  'status',         'Status',         'SELECT', true,  '["ACTIVE","ON_NOTICE","EXITED"]', 70),
    ('work',  'exit_date',      'Exit date',      'DATE',   false, null, 80)
) as f(section_key, key, label, type, required, options, sort)
join config_section s
  on s.org_id = '00000000-0000-0000-0000-000000000001'
 and s.area_key = 'employee_profile'
 and s.key = f.section_key;

-- Default access matrix.
--   super_admin, hr_admin, hr_ops: edit every employee_profile field.
--   employee: may edit their own phone and personal email; others' personal email is hidden.
--   every other (role, field) pair has no rule: the field is read-only (VIEW) for them.
insert into config_field_access (field_id, role_id, access)
select f.id, r.id, 'EDIT'
from config_field f
join role r on r.org_id = f.org_id and r.key in ('super_admin', 'hr_admin', 'hr_ops')
where f.area_key = 'employee_profile' and f.org_id = '00000000-0000-0000-0000-000000000001';

insert into config_field_access (field_id, role_id, access, own_access, others_access)
select f.id, r.id, 'VIEW',
       case when f.key in ('phone', 'personal_email') then 'EDIT' else 'VIEW' end,
       case f.key when 'personal_email' then 'HIDDEN' else 'VIEW' end
from config_field f
join role r on r.org_id = f.org_id and r.key = 'employee'
where f.area_key = 'employee_profile' and f.org_id = '00000000-0000-0000-0000-000000000001'
  and f.key in ('phone', 'personal_email');
