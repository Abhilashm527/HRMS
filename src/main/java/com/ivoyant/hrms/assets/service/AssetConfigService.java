package com.ivoyant.hrms.assets.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.NotFoundException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What the asset screens read: the active statuses, categories, types, ownership, ID formats and alerts, and the extra fields per type. */
@Service
public class AssetConfigService {

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;

	public AssetConfigService(JdbcClient jdbc, CurrentUser currentUser) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	private List<Map<String, Object>> rows(String sql, String kind) {
		return jdbc.sql(sql).param("org", currentUser.orgId()).param("kind", kind, java.sql.Types.VARCHAR).query((rs, i) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			java.sql.ResultSetMetaData md = rs.getMetaData();
			for (int c = 1; c <= md.getColumnCount(); c++) {
				Object v = rs.getObject(c);
				m.put(md.getColumnLabel(c), v instanceof java.util.UUID u ? u.toString() : v);
			}
			return m;
		}).list();
	}

	/** {@code kind} is hardware or software. */
	@Transactional(readOnly = true)
	public Map<String, Object> config(String kind) {
		String k = kind(kind);
		boolean hw = "HARDWARE".equals(k);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("statuses", rows("select id, name, color, assignable from asset_status where org_id = :org and kind = :kind and active order by sort, name", k));
		out.put("categories", rows("select id, name, icon from asset_category where org_id = :org and kind = :kind and active order by sort, name", k));
		out.put("types", rows(hw
				? "select id, name, category_id as \"categoryId\", icon, max_age_years as \"maxAgeYears\" from asset_type where org_id = :org and kind = :kind and active order by sort, name"
				: "select id, name, billing, unit from asset_type where org_id = :org and kind = :kind and active order by sort, name", k));
		if (hw) {
			out.put("ownership", rows("select id, name, capture_fields as \"captureFields\", return_required as \"returnRequired\" from asset_ownership where org_id = :org and active and :kind = :kind order by sort, name", k));
		}
		out.put("idFormats", rows("select id, ownership_id as \"ownershipId\", license_type_id as \"licenseTypeId\", fmt from asset_id_format where org_id = :org and kind = :kind and active order by sort", k));
		out.put("alerts", rows("select id, event, name, days_before as \"daysBefore\", in_app as \"inApp\", email from asset_alert_rule where org_id = :org and kind = :kind and active order by sort", k));
		return out;
	}

	/** The extra fields of a hardware type (or of a software category): the "Custom fields · Laptop" step and the detail card. */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> fields(String kind, UUID id) {
		String k = kind(kind);
		boolean hw = "HARDWARE".equals(k);
		String name = jdbc.sql("select name from " + (hw ? "asset_type" : "asset_category") + " where id = :id and org_id = :org and kind = :kind")
				.param("id", id).param("org", currentUser.orgId()).param("kind", k).query(String.class).optional()
				.orElseThrow(() -> new NotFoundException((hw ? "Asset type " : "Software category ") + id + " not found"));
		String sectionKey = hw ? name.toLowerCase(Locale.ROOT) : name.toLowerCase(Locale.ROOT).replace(' ', '_');
		return jdbc.sql("""
				select f.id, f.key, f.label, f.type, f.required, f.options from config_field f join config_section s on s.id = f.section_id
				where f.org_id = :org and f.area_key = :area and s.key = :section and f.active and f.deleted_at is null and s.deleted_at is null
				order by f.sort""").param("org", currentUser.orgId()).param("area", hw ? "asset_hardware" : "asset_software").param("section", sectionKey)
				.query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class).toString());
					m.put("key", rs.getString(2));
					m.put("label", rs.getString(3));
					m.put("type", rs.getString(4));
					m.put("required", rs.getBoolean(5));
					m.put("options", rs.getString(6) == null ? List.of() : rs.getString(6));
					return m;
				}).list();
	}

	private static String kind(String kind) {
		String k = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
		if (!k.equals("HARDWARE") && !k.equals("SOFTWARE")) {
			throw new NotFoundException("There is no '" + kind + "' asset configuration (use hardware or software)");
		}
		return k;
	}
}
