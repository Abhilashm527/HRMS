-- the settings list editors keep row order in a sort column; two of the V22 tables were created without it
alter table asset_id_format add column sort int not null default 0;
alter table invite_template add column sort int not null default 0;
update asset_id_format set sort = 10 where sort = 0;
update invite_template set sort = case when employment_type_id is null then 20 else 10 end where sort = 0;
