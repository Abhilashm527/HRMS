package com.ivoyant.hrms.common.event;

/** Published by the org module; see ORGANIZATION_BACKEND_GUIDE.md. */
public record OrgTimezoneChangedEvent(String from, String to) {
}
