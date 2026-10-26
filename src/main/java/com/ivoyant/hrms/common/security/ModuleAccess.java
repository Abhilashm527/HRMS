package com.ivoyant.hrms.common.security;

/**
 * Does the caller hold at least {@code level} (VIEW, EDIT, APPROVE or ADMIN) in a module? Super admins and HR admins
 * always do. Implemented by the iam module; used from {@code @PreAuthorize("@moduleAccess.has('org','EDIT')")} so
 * other modules need no dependency on iam.
 */
public interface ModuleAccess {

	boolean has(String module, String level);
}
