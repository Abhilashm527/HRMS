package com.ivoyant.hrms.config;

import java.util.List;

public record FormSection(String name, boolean showName, List<FormField> fields) {
}
