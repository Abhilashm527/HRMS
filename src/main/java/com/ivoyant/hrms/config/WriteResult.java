package com.ivoyant.hrms.config;

import java.util.List;

/** {@code updated} was written now; {@code pendingApproval} became a change request waiting for HR. */
public record WriteResult(List<String> updated, List<String> pendingApproval, FormView form) {
}
