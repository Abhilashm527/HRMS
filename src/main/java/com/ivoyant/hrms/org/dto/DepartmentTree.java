package com.ivoyant.hrms.org.dto;

import java.util.List;

/** {@code overDepth} is true when the tree is deeper than the limit in the organization settings. */
public record DepartmentTree(int maxDepth, int deepest, boolean overDepth, boolean nestedEnabled,
		List<DepartmentNode> roots) {
}
