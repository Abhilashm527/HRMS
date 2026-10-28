package com.ivoyant.hrms.notification.service;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends the queued emails (notification_email) when SMTP is set up (SMTP_HOST). A message that fails is marked with the error and not
 * retried automatically; clear its error to try again.
 */
@Component
class EmailDelivery {

	private static final Logger log = LoggerFactory.getLogger(EmailDelivery.class);

	private final JdbcClient jdbc;
	private final ObjectProvider<JavaMailSender> sender;
	private final String from;
	private final String host;

	EmailDelivery(JdbcClient jdbc, ObjectProvider<JavaMailSender> sender, @Value("${hrms.mail-from:HRMS <no-reply@ivoyant.com>}") String from,
			@Value("${spring.mail.host:}") String host) {
		this.host = host;
		this.jdbc = jdbc;
		this.sender = sender;
		this.from = from;
	}

	@Scheduled(fixedDelay = 15_000, initialDelay = 30_000)
	@Transactional
	void send() {
		JavaMailSender mail = sender.getIfAvailable();
		if (mail == null || host == null || host.isBlank()) {
			return;
		}
		for (var row : jdbc.sql("select id, to_address, cc_address, subject, html from notification_email where sent_at is null and error is null order by id limit 20 for update skip locked")
				.query((rs, i) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)}).list()) {
			long id = (Long) row[0];
			try {
				MimeMessage message = mail.createMimeMessage();
				MimeMessageHelper h = new MimeMessageHelper(message, "UTF-8");
				h.setFrom(from);
				h.setTo((String) row[1]);
				if (row[2] != null && !((String) row[2]).isBlank()) {
					h.setCc((String) row[2]);
				}
				h.setSubject((String) row[3]);
				h.setText((String) row[4], true);
				mail.send(message);
				jdbc.sql("update notification_email set sent_at = now() where id = :id").param("id", id).update();
			}
			catch (Exception ex) {
				log.warn("Email {} to {} failed: {}", id, row[1], ex.getMessage());
				jdbc.sql("update notification_email set error = :e where id = :id").param("e", String.valueOf(ex.getMessage())).param("id", id).update();
			}
		}
	}
}
