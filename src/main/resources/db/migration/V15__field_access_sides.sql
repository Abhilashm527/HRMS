-- The legacy single "access" value was used as the fallback for whichever side had no value of its own, so setting
-- only the others side also set the own side. Each side now stands alone: no value means "use the role's default".
update config_field_access set access = null;
