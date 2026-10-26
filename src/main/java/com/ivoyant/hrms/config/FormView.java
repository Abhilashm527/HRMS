package com.ivoyant.hrms.config;

import java.util.List;
import java.util.UUID;

/** A form for one record (or a blank one) of an area; {@code side} is OWN or OTHERS. */
public record FormView(String title, String areaKey, UUID recordId, String side, List<FormSection> sections) {
}
