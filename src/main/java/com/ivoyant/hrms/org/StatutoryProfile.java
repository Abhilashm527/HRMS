package com.ivoyant.hrms.org;

/** What payroll needs to know about a location. */
public record StatutoryProfile(String state, boolean lwfApplicable, boolean esiNotified, String gstin,
		String shopsEstabRegNo) {
}
