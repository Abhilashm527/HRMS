package com.ivoyant.hrms.org.dto;

import java.util.UUID;

public record LocationEmployee(UUID id, String employeeCode, String displayName, String workEmail, String status) {
}
