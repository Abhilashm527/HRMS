package com.ivoyant.hrms.common.web;

/** The organization is archived: nothing can be written except by a super admin reactivating it. */
public class OrgArchivedException extends RuntimeException {

	public OrgArchivedException() {
		super("This organization is archived. Only a super admin can reactivate it.");
	}
}
