package com.ivoyant.hrms.org.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

/**
 * The organization profile. Members who are not admins only get the basic fields (id, name, code, country, timezone,
 * fyStartMonth, currency, primaryDomain); the rest is left out of the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrgResponse(UUID id, String name, String legalName, String code, String industry, String country,
		String countryName, String timezone, String timezoneLabel, Integer fyStartMonth, String fyLabel,
		String currency, String currencyLabel, String primaryDomain, String status, String employeeIdPrefix,
		Counts counts, Long version, @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> warnings) {

	public record Counts(long departments, long positions, long titles, long locations, long employees) {
	}

	public OrgResponse withWarnings(List<String> warnings) {
		return new OrgResponse(id, name, legalName, code, industry, country, countryName, timezone, timezoneLabel,
				fyStartMonth, fyLabel, currency, currencyLabel, primaryDomain, status, employeeIdPrefix, counts, version,
				warnings);
	}
}
