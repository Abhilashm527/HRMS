-- HR admins and super admins hold every level on the org module (V13 only gave the other roles theirs), so the
-- default field access (department custom fields) lets them see and edit what they manage.
insert into role_permission (role_id, permission_id, scope)
select r.id, p.id, 'ORGANIZATION'
from role r
join permission p on p.module = 'org'
where r.org_id = '00000000-0000-0000-0000-000000000001' and r.key in ('hr_admin', 'super_admin')
on conflict do nothing;
