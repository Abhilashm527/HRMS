-- IT admins own the assets area, which includes the hardware and software lists
insert into area_owner_role (org_id, area_key, role_id)
select r.org_id, 'assets', r.id from role r
where r.org_id = '00000000-0000-0000-0000-000000000001' and r.key = 'it_admin'
on conflict do nothing;
