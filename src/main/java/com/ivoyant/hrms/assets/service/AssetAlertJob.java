package com.ivoyant.hrms.assets.service;

import com.ivoyant.hrms.assets.AssetAlertSource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every morning (07:00 India time) each active alert rule looks for records whose date is {@code days before} away. A record is
 * alerted once per rule and due date. With no asset register yet the sources are empty and nothing happens.
 */
@Component
class AssetAlertJob {

	private static final Logger log = LoggerFactory.getLogger(AssetAlertJob.class);

	private final JdbcClient jdbc;
	private final ObjectProvider<AssetAlertSource> sources;

	AssetAlertJob(JdbcClient jdbc, ObjectProvider<AssetAlertSource> sources) {
		this.jdbc = jdbc;
		this.sources = sources;
	}

	@Scheduled(cron = "0 0 7 * * *", zone = "Asia/Kolkata")
	@Transactional
	public int run() {
		int sent = 0;
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
		List<Object[]> rules = jdbc.sql("select id, kind, event, name, days_before, in_app, email from asset_alert_rule where active")
				.query((rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
						rs.getBoolean(6), rs.getBoolean(7)}).list();
		for (Object[] rule : rules) {
			LocalDate due = today.plusDays((Integer) rule[4]);
			for (AssetAlertSource source : sources.orderedStream().toList()) {
				for (AssetAlertSource.Due d : source.dueOn((String) rule[1], (String) rule[2], due)) {
					int inserted = jdbc.sql("insert into asset_alert_sent (rule_id, record_id, due_date) values (:r, :rec, :d) on conflict do nothing")
							.param("r", rule[0]).param("rec", d.recordId()).param("d", due).update();
					if (inserted > 0) {
						sent++;
						log.info("Asset alert '{}': {} is due on {}", rule[3], d.label(), due);
					}
				}
			}
		}
		return sent;
	}
}
