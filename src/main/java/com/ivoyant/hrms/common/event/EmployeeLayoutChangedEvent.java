package com.ivoyant.hrms.common.event;

/** The employee profile layout (sections, fields, access presets) was saved: forms for the profile and "My profile" must reload. */
public record EmployeeLayoutChangedEvent(long version) {
}
