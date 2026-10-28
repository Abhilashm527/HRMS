package com.ivoyant.hrms.iam.auth;

import com.ivoyant.hrms.common.config.HrmsProperties;
import com.ivoyant.hrms.common.event.BootstrapAdminReadyEvent;
import com.ivoyant.hrms.common.event.EmployeeExitedEvent;
import com.ivoyant.hrms.common.event.EmployeeOnboardedEvent;
import com.ivoyant.hrms.notification.Notifier;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** "Set up your HRMS account" emails, and the clean-up that follows an exit. */
@Service
public class AuthInvites {

	private static final Logger log = LoggerFactory.getLogger(AuthInvites.class);

	private final JdbcClient jdbc;
	private final HrmsProperties props;
	private final AuthProps authProps;
	private final AuthService auth;
	private final PasswordService passwords;
	private final TokenService tokens;
	private final KeyService keys;
	private final Notifier notifier;

	AuthInvites(JdbcClient jdbc, HrmsProperties props, AuthProps authProps, AuthService auth, PasswordService passwords, TokenService tokens, KeyService keys, Notifier notifier) {
		this.jdbc = jdbc;
		this.props = props;
		this.authProps = authProps;
		this.auth = auth;
		this.passwords = passwords;
		this.tokens = tokens;
		this.keys = keys;
		this.notifier = notifier;
	}

	/** Sends the invite (or the "sign in with Microsoft" note when passwords are off). Returns the link when one was made, for admin tools. */
	@Transactional
	public String send(UUID employeeId) {
		AuthService.Account a = auth.find(jdbc.sql("select lower(work_email) from employee where id = :id").param("id", employeeId).query(String.class).single());
		if (a == null) {
			log.warn("No user account for employee {}", employeeId);
			return null;
		}
		jdbc.sql("update app_user set invited_at = now() where id = :id").param("id", a.userId()).update();
		if (!passwords.passwordLoginEnabled() && !a.breakGlass()) {
			notifier.email(a.email(), null, "Your HRMS account is ready", "<p>Hi " + AuthService.esc(a.firstName()) + ", sign in to HRMS with Microsoft using <b>" + AuthService.esc(a.email())
					+ "</b>.</p><p><a href=\"" + authProps.appBaseUrl() + "\">Open HRMS</a></p>");
			return null;
		}
		jdbc.sql("update auth_token set used_at = now() where user_id = :u and purpose = 'ACTIVATE' and used_at is null").param("u", a.userId()).update();
		String raw = auth.token(a.userId(), "ACTIVATE", Duration.ofDays(7), Map.of());
		String link = authProps.appBaseUrl() + "/#activate?token=" + raw;
		notifier.email(a.email(), null, "Set up your HRMS account", "<p>Hi " + AuthService.esc(a.firstName()) + ", your HRMS account is ready. Choose a password to get started. The link works once and expires in 7 days.</p>"
				+ "<p><a href=\"" + link + "\">Set up my account</a></p>");
		if (authProps.local()) {
			log.info("Activation link for {}: {}", a.email(), link);
		}
		else if (a.breakGlass()) {
			// the first admin may have no mail set up yet: the one-time link is also written to the server log
			log.warn("Account set-up link for the first admin {} (works once, 7 days): {}", a.email(), link);
		}
		return link;
	}

	/** The first super admin gets their set-up link (by email when mail is set up, and in the log). */
	@EventListener
	void onBootstrap(BootstrapAdminReadyEvent event) {
		try {
			send(event.employeeId());
		}
		catch (RuntimeException ex) {
			log.warn("Could not prepare the first admin's account link: {}", ex.getMessage());
		}
	}

	/** A hire who joined gets their account invite. */
	@EventListener
	void onJoined(EmployeeOnboardedEvent event) {
		try {
			send(event.employeeId());
		}
		catch (RuntimeException ex) {
			log.warn("Could not send the account invite to {}: {}", event.employeeId(), ex.getMessage());
		}
	}

	/** Someone who left is signed out everywhere at once. */
	@EventListener
	void onExit(EmployeeExitedEvent event) {
		jdbc.sql("select id from app_user where employee_id = :e").param("e", event.employeeId()).query(UUID.class).optional().ifPresent(u -> tokens.revokeAll(u, null, "EXIT"));
	}

	@Scheduled(cron = "0 10 3 * * *")
	void daily() {
		tokens.touchCleanup();
		keys.rotateIfDue();
	}
}
