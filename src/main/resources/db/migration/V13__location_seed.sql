-- Locations: reference data and the starter locations (see LOCATIONS_BACKEND_GUIDE.md section 10)

-- 1. who may read or change locations: the org module's permission levels
insert into permission (module, action) values ('org','view'),('org','edit'),('org','approve'),('org','admin')
on conflict (module, action) do nothing;

insert into role_permission (role_id, permission_id, scope)
select r.id, p.id, 'ORGANIZATION'
from role r
join (values ('hr_ops','view'),('hr_ops','edit'),('finance','view'),('it_admin','view')) g(role_key, action)
  on r.key = g.role_key
join permission p on p.module = 'org' and p.action = g.action
where r.org_id = '00000000-0000-0000-0000-000000000001'
on conflict do nothing;

-- 2. holiday calendars: one national calendar and one per state, each with its own dates.
--    Regional calendars inherit the national dates when read, so national days are not repeated.
insert into holiday_calendar (org_id, name, state) values
 ('00000000-0000-0000-0000-000000000001', 'National holidays only', null),
 ('00000000-0000-0000-0000-000000000001', 'Karnataka', 'Karnataka'),
 ('00000000-0000-0000-0000-000000000001', 'Telangana', 'Telangana'),
 ('00000000-0000-0000-0000-000000000001', 'Maharashtra', 'Maharashtra'),
 ('00000000-0000-0000-0000-000000000001', 'Tamil Nadu', 'Tamil Nadu')
on conflict (org_id, name) do nothing;

-- starter dates for 2026: only the fixed-date holidays. Festival dates that move every year are loaded by HR.
insert into holiday (calendar_id, date, name, kind)
select c.id, h.d::date, h.n, h.k
from holiday_calendar c
join (values
 ('National holidays only', '2026-01-26', 'Republic Day',     'NATIONAL'),
 ('National holidays only', '2026-08-15', 'Independence Day', 'NATIONAL'),
 ('National holidays only', '2026-10-02', 'Gandhi Jayanti',   'NATIONAL'),
 ('Karnataka',   '2026-11-01', 'Kannada Rajyotsava',      'REGIONAL'),
 ('Telangana',   '2026-06-02', 'Telangana Formation Day', 'REGIONAL'),
 ('Maharashtra', '2026-05-01', 'Maharashtra Day',         'REGIONAL'),
 ('Tamil Nadu',  '2026-04-14', 'Tamil New Year',          'REGIONAL')
) h(cal, d, n, k) on h.cal = c.name
where c.org_id = '00000000-0000-0000-0000-000000000001'
on conflict (calendar_id, date) do nothing;

-- 3. the starter locations: update the five rows the local seed created, in place, so their ids and employee links stay
update location l set name = 'Bengaluru HQ', code = 'BLR-HQ', type = 'HEAD_OFFICE',
       address_line = 'Prestige Tech Park, Tower B, 4th floor, Outer Ring Road, Marathahalli',
       city = 'Bengaluru', state = 'Karnataka', postal_code = '560103', phone = '+91 80 4718 2200',
       email = 'blr.office@ivoyant.com', week_pattern = 'MON_FRI', start_time = '09:30', end_time = '18:30',
       grace_minutes = 15, attendance_methods = '{BIOMETRIC,WEB,MOBILE_GPS}', geo_radius_m = 200,
       wfh_allowed = true, lwf_applicable = true, esi_notified = true,
       shops_estab_reg_no = 'KA/BLR/SE/2016/04812', gstin = '29AAFCI4521K1ZP', seat_capacity = 30, asset_store = true,
       holiday_calendar_id = (select id from holiday_calendar where org_id = l.org_id and name = 'Karnataka')
where l.name = 'Bengaluru';

update location l set name = 'Hyderabad office', code = 'HYD-01', type = 'BRANCH_OFFICE',
       address_line = 'Knowledge City, Plot 12, Raidurg, HITEC City',
       city = 'Hyderabad', state = 'Telangana', postal_code = '500081', phone = '+91 40 4520 7710',
       email = 'hyd.office@ivoyant.com', week_pattern = 'MON_FRI', start_time = '09:00', end_time = '18:00',
       grace_minutes = 10, attendance_methods = '{BIOMETRIC,MOBILE_GPS}', geo_radius_m = 150,
       wfh_allowed = true, lwf_applicable = true, esi_notified = true,
       shops_estab_reg_no = 'TS/HYD/SE/2019/11230', gstin = '36AAFCI4521K1ZQ', seat_capacity = 14, asset_store = true,
       holiday_calendar_id = (select id from holiday_calendar where org_id = l.org_id and name = 'Telangana')
where l.name = 'Hyderabad';

update location l set name = 'Mumbai BKC', code = 'MUM-01', type = 'CO_WORKING',
       address_line = 'WeWork, G Block, Bandra Kurla Complex, Bandra East',
       city = 'Mumbai', state = 'Maharashtra', postal_code = '400051', phone = '+91 22 6190 4400',
       email = 'mum.office@ivoyant.com', week_pattern = 'MON_FRI', start_time = '10:00', end_time = '19:00',
       grace_minutes = 15, attendance_methods = '{WEB,QR}', geo_radius_m = 100,
       wfh_allowed = true, lwf_applicable = true, esi_notified = false,
       shops_estab_reg_no = 'MH/MUM/SE/2017/77310', gstin = '27AAFCI4521K1ZR', seat_capacity = 8, asset_store = false,
       holiday_calendar_id = (select id from holiday_calendar where org_id = l.org_id and name = 'Maharashtra')
where l.name = 'Mumbai';

update location l set name = 'Pune studio', code = 'PUN-01', type = 'CO_WORKING',
       address_line = 'Smartworks, 3rd floor, Baner–Pashan Link Road, Baner',
       city = 'Pune', state = 'Maharashtra', postal_code = '411045', phone = '+91 20 6720 1180',
       email = 'pune.office@ivoyant.com', week_pattern = 'MON_FRI', start_time = '10:00', end_time = '19:00',
       grace_minutes = 15, attendance_methods = '{WEB,MOBILE_GPS}', geo_radius_m = 150,
       wfh_allowed = true, lwf_applicable = true, esi_notified = false,
       shops_estab_reg_no = 'MH/PUN/SE/2018/20417', gstin = '27AAFCI4521K1ZR', seat_capacity = 6, asset_store = false,
       holiday_calendar_id = (select id from holiday_calendar where org_id = l.org_id and name = 'Maharashtra')
where l.name = 'Pune';

-- Remote has no office: a remote hub, web check-in only, national holidays, no state (so no PT)
update location l set code = 'REMOTE', type = 'REMOTE_HUB', attendance_methods = '{WEB}', esi_notified = false,
       holiday_calendar_id = (select id from holiday_calendar where org_id = l.org_id and name = 'National holidays only')
where l.name = 'Remote';
