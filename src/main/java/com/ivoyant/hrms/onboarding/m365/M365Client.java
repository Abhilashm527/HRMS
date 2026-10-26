package com.ivoyant.hrms.onboarding.m365;

import java.util.List;

/** The Microsoft Graph calls provisioning needs. Each call is idempotent from the caller's side (it stores what it got). */
public interface M365Client {

	/** True when the user principal name is free in the tenant. */
	boolean upnFree(String upn);

	/** Creates the user and returns its object id. */
	String createUser(String upn, String displayName, String password, boolean forceReset, boolean enabled);

	void assignLicense(String userId, String licenseSku);

	/** Adds the user to the named groups; throws {@link GroupNotFound} for a group that does not exist. */
	void addToGroups(String userId, List<String> groups);

	/** Waits until the mailbox answers. */
	void awaitMailbox(String userId);

	void resetPassword(String userId, String password, boolean forceReset);

	void disable(String userId);

	void enable(String userId);

	/** True for the stand-in client (no real tenant touched). */
	boolean simulated();

	class GroupNotFound extends RuntimeException {
		public GroupNotFound(String group) {
			super("Group '" + group + "' was not found in Microsoft 365");
		}
	}
}
