package com.ivoyant.hrms.org.dto;

import java.util.List;

public record OrgMeta(List<Country> countries, List<Zone> timezones, List<Currency> currencies, List<FyMonth> fyMonths,
		List<Status> statuses) {

	public record Country(String code, String name) {
	}

	public record Zone(String id, String label) {
	}

	public record Currency(String code, String label) {
	}

	public record FyMonth(int value, String label) {
	}

	public record Status(String value, String label) {
	}
}
