package com.ivoyant.hrms.org.dto;

import java.util.UUID;

/** For pickers: active positions, most senior first. */
public record PositionOption(UUID id, String name, String grade, Integer level) {
}
