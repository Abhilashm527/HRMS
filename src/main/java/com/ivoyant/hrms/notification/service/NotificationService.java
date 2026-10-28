package com.ivoyant.hrms.notification.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.notification.Notifier;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Queues emails (notification_email, sent by a delivery worker) and writes the in-app inbox. */
@Service
class NotificationService implements Notifier {

	private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;

	NotificationService(JdbcClient jdbc, CurrentUser currentUser) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	@Override
	@Transactional
	public void email(String to, String cc, String subject, String html) {
		if (to == null || to.isBlank()) {
			return;
		}
		jdbc.sql("insert into notification_email (org_id, to_address, cc_address, subject, html) values (:org, :to, :cc, :s, :h)")
				.param("org", currentUser.orgId()).param("to", to).param("cc", cc).param("s", subject).param("h", html).update();
		log.info("email queued to {} — {}", to, subject);
	}

	@Override
	public void sms(String phone, String text) {
		// no SMS gateway is wired yet; only the fact is logged, never the text (it can carry a temporary password)
		log.info("sms to {} ({} characters)", phone, text == null ? 0 : text.length());
	}

	@Override
	@Transactional
	public void inApp(Collection<UUID> employeeIds, String title, String body, String link) {
		for (UUID id : employeeIds) {
			jdbc.sql("insert into notification_inbox (org_id, employee_id, title, body, link) values (:org, :e, :t, :b, :l)")
					.param("org", currentUser.orgId()).param("e", id).param("t", title).param("b", body).param("l", link).update();
		}
	}

	@Override
	@Transactional
	public void inApp(Collection<UUID> employeeIds, String type, String title, String body, String link) {
		for (UUID id : employeeIds) {
			jdbc.sql("insert into notification_inbox (org_id, employee_id, type, title, body, link) values (:org, :e, :ty, :t, :b, :l)")
					.param("org", currentUser.orgId()).param("e", id).param("ty", type).param("t", title).param("b", body).param("l", link).update();
		}
	}

	@Override
	@Transactional
	public void inAppBatched(Collection<UUID> employeeIds, String title, String body, String link, int windowMinutes) {
		for (UUID id : employeeIds) {
			boolean recent = jdbc.sql("select count(*) from notification_inbox where employee_id = :e and title = :t and coalesce(link, '') = coalesce(:l, '') and created_at > now() - make_interval(mins => :m)")
					.param("e", id).param("t", title).param("l", link).param("m", windowMinutes).query(Long.class).single() > 0;
			if (!recent) {
				inApp(List.of(id), title, body, link);
			}
		}
	}
}
