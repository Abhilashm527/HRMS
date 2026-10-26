create table if not exists app_meta (key text primary key, value text);
insert into app_meta values ('schema', 'v1') on conflict do nothing;