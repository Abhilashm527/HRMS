package com.ivoyant.hrms.org.dto;

import java.util.UUID;

/** For pickers: an active title valid for the chosen department, with its position. {@code scope} is GLOBAL or DEPARTMENT. */
public record TitleOption(UUID id, String name, String code, UUID positionId, String positionName, String grade,
		Integer level, String scope) {
}
