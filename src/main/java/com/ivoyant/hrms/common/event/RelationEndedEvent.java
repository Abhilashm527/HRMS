package com.ivoyant.hrms.common.event;

import java.util.UUID;

/** See EMPLOYMENT_TYPES_REPORTING_RELATIONS_GUIDE.md section D3. */
public record RelationEndedEvent(UUID id, String type, UUID employeeId, UUID relatedId, String reason) {
}
