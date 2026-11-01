package com.ivoyant.hrms.iam;

import java.util.Set;

/** Module permissions as other modules see them: which parts of the app the caller's roles switch on. */
public interface NavAccess {

	/** Tabs of someone else's profile that none of the caller's roles may open. */
	Set<String> hiddenProfileTabs();
}
