package com.ivoyant.hrms.config.dto;

import com.ivoyant.hrms.config.service.ListSpecs;
import java.util.List;

public record ListSpecResponse(String module, List<Spec> lists) {

	public record Spec(String key, String label, List<ListSpecs.Col> columns) {
	}
}
