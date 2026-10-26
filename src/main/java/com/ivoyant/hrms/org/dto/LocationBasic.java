package com.ivoyant.hrms.org.dto;

import java.util.UUID;

/** What a member without access to location settings may see. */
public record LocationBasic(UUID id, String name, String code, String city, String state, boolean active) {
}
