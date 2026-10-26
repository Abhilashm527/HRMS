package com.ivoyant.hrms.config.dto;

import com.ivoyant.hrms.config.FieldType;
import java.util.List;

public record AreaDetail(String key, String name, String description, String module, List<FieldType> allowedTypes,
		List<OwnerRef> owners, List<SectionDetail> sections) {
}
