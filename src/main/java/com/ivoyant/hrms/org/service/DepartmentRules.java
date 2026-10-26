package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.org.OrgSettings;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** What the organization settings say about department nesting. */
@Component
class DepartmentRules {

	private final OrgSettings settings;
	private final JdbcClient jdbc;

	DepartmentRules(OrgSettings settings, JdbcClient jdbc) {
		this.settings = settings;
		this.jdbc = jdbc;
	}

	/** {@code selfId} is null for a new department; its own sub-departments move down with it. */
	void checkParent(UUID parentId, UUID selfId) {
		if (parentId == null) {
			return;
		}
		if (!settings.nestedDepartments()) {
			throw new ConflictException("Nested departments are off in Settings → Organization");
		}
		long parentLevel = jdbc.sql("""
				with recursive up as (
				    select id, parent_id, 1 as lvl from department where id = :p
				    union all
				    select d.id, d.parent_id, up.lvl + 1 from department d join up on d.id = up.parent_id)
				select max(lvl) from up""").param("p", parentId).query(Long.class).single();
		long below = selfId == null ? 0 : jdbc.sql("""
				with recursive down as (
				    select id, 0 as h from department where id = :s
				    union all
				    select c.id, down.h + 1 from department c join down on c.parent_id = down.id)
				select max(h) from down""").param("s", selfId).query(Long.class).single();
		long deepest = parentLevel + 1 + below;
		if (deepest > settings.maxDepartmentDepth()) {
			throw new ConflictException("That would nest departments " + deepest + " levels deep; the limit is "
					+ settings.maxDepartmentDepth() + " (Settings → Organization)");
		}
	}

	boolean codeRequired() {
		return settings.departmentCodeRequired();
	}
}
