package com.ivoyant.hrms.notification;

import java.util.Collection;
import java.util.UUID;

/** What other modules use to tell people things. Delivery is queued; a failure to queue never fails the caller's change. */
public interface Notifier {

	/** Queues an email. {@code cc} may be null. */
	void email(String to, String cc, String subject, String html);

	/** Sends an SMS. The text may hold a secret, so it is never stored or logged. */
	void sms(String phone, String text);

	/** Puts a message in each employee's in-app inbox. {@code link} may be null. */
	void inApp(Collection<UUID> employeeIds, String title, String body, String link);

	/** In-app message with a type (for example ASSET_ALERT) so screens can list and clear one kind. */
	void inApp(Collection<UUID> employeeIds, String type, String title, String body, String link);

	/** Like {@link #inApp} but skips people who already got the same title and link within {@code windowMinutes}. */
	void inAppBatched(Collection<UUID> employeeIds, String title, String body, String link, int windowMinutes);
}
