package com.ivoyant.hrms.org.dto;

import java.util.UUID;

/** What every member may see of a position: no band, no reporting line. */
public record PositionBasic(UUID id, String name, String grade, Integer level, boolean active) {
}
