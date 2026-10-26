package com.ivoyant.hrms.common.event;

/** Published by the org module; see ORGANIZATION_BACKEND_GUIDE.md. */
public record OrgFinancialYearChangedEvent(int from, int to) {
}
