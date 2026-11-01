-- Asset statuses can use one of the six preset colours (color 0–5) or a custom one picked in Asset configuration.
alter table asset_status add column color_hex text check (color_hex is null or color_hex ~ '^#[0-9A-Fa-f]{6}$');
