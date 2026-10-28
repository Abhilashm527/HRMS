package com.ivoyant.hrms.assets.service;

import com.ivoyant.hrms.assets.AssetAlertSource;
import com.ivoyant.hrms.common.security.CurrentUser;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Finds the assets and licences whose warranty, insurance, lease, client return, maximum age or renewal falls on a day. */
@Component
class AssetAlertSources implements AssetAlertSource {

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;

	AssetAlertSources(JdbcClient jdbc, CurrentUser currentUser) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	@Override
	public List<Due> dueOn(String kind, String event, LocalDate date) {
		if ("HARDWARE".equals(kind)) {
			String where = switch (event) {
				case "WARRANTY_END" -> "a.warranty_until = :d";
				case "INSURANCE_END" -> "a.insured_until = :d";
				case "LEASE_END" -> "a.return_due = :d and o.name ilike 'lease%'";
				case "CLIENT_RETURN" -> "a.return_due = :d and o.name ilike 'client%'";
				case "MAX_AGE" -> "t.max_age_years is not null and a.purchased_on is not null and a.purchased_on + make_interval(years => t.max_age_years) = :d";
				default -> null;
			};
			if (where == null) {
				return List.of();
			}
			return jdbc.sql("""
					select a.id, a.tag || ' · ' || a.model, ag.employee_id from asset a join asset_type t on t.id = a.type_id join asset_ownership o on o.id = a.ownership_id
					join asset_status s on s.id = a.status_id left join asset_assignment ag on ag.asset_id = a.id and ag.returned_on is null
					where a.org_id = :org and a.deleted_at is null and coalesce(s.system_key, '') <> 'RETIRED' and """ + where).param("org", currentUser.orgId()).param("d", date)
					.query((rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class))).list();
		}
		String where = switch (event) {
			case "RENEWAL" -> "l.renews_on = :d";
			case "CONTRACT_END" -> "l.renews_on = :d and t.billing = 'Annual'";
			default -> null;
		};
		if (where == null) {
			return List.of();
		}
		return jdbc.sql("select l.id, l.lid || ' · ' || l.name, l.owner_employee_id from license l join asset_type t on t.id = l.type_id where l.org_id = :org and l.deleted_at is null and " + where)
				.param("org", currentUser.orgId()).param("d", date).query((rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class))).list();
	}
}
