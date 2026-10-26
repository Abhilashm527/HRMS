package com.ivoyant.hrms.assets.service;

import com.ivoyant.hrms.assets.AssetIds;
import com.ivoyant.hrms.common.web.ConflictException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The ID counter: one row per format and code, bumped under a row lock so two people can never get the same number. */
@Service
class AssetIdService implements AssetIds {

	private static final Pattern RUN = Pattern.compile("#+");

	private final JdbcClient jdbc;

	AssetIdService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional
	public String next(UUID formatId, String code) {
		String fmt = jdbc.sql("select fmt from asset_id_format where id = :id and active").param("id", formatId).query(String.class).optional()
				.orElseThrow(() -> new ConflictException("That asset ID format is not available"));
		String scope = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
		int n = jdbc.sql("""
				insert into asset_id_seq (format_id, scope, last_value) values (:f, :s, 1)
				on conflict (format_id, scope) do update set last_value = asset_id_seq.last_value + 1
				returning last_value""").param("f", formatId).param("s", scope).query(Integer.class).single();
		String withCode = fmt.replace("{TYPE}", scope).replace("{APP}", scope);
		Matcher m = RUN.matcher(withCode);
		StringBuilder out = new StringBuilder();
		boolean done = false;
		while (m.find()) {
			if (!done) {
				m.appendReplacement(out, String.format("%0" + m.group().length() + "d", n));
				done = true;
			}
		}
		m.appendTail(out);
		return out.toString();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UUID> formatFor(String kind, UUID ownershipOrLicenseTypeId) {
		String column = "HARDWARE".equalsIgnoreCase(kind) ? "ownership_id" : "license_type_id";
		return jdbc.sql("select id from asset_id_format where kind = :k and active and " + column + " = :x limit 1")
				.param("k", kind.toUpperCase(Locale.ROOT)).param("x", ownershipOrLicenseTypeId).query(UUID.class).optional();
	}
}
