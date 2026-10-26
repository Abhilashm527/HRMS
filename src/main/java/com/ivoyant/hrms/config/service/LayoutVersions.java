package com.ivoyant.hrms.config.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Per-area layout version: moves on every change to the area's sections, fields or access. */
@Component
public class LayoutVersions {

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;

	public LayoutVersions(JdbcClient jdbc, CurrentUser currentUser) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	public long version(String areaKey) {
		return jdbc.sql("select version from config_layout_version where org_id = :org and area_key = :area")
				.param("org", currentUser.orgId()).param("area", areaKey).query(Long.class).optional().orElse(0L);
	}

	public long bump(String areaKey) {
		return jdbc.sql("""
				insert into config_layout_version (org_id, area_key, version, updated_by) values (:org, :area, 1, :by)
				on conflict (org_id, area_key) do update set version = config_layout_version.version + 1, updated_at = now(), updated_by = :by
				returning version""").param("org", currentUser.orgId()).param("area", areaKey)
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).query(Long.class).single();
	}
}
