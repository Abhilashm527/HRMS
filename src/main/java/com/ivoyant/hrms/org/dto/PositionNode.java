package com.ivoyant.hrms.org.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A position as the list, the ladder and the drawer show it. The same shape serves the flat list, the ladder
 * ({@code children}) and a single position ({@code custom}); parts that were not asked for are left out. The band is
 * left out (but {@code hasBand} stays) for callers who may not see salaries.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PositionNode(UUID id, String name, String grade, Integer level, String track, String trackLabel,
		BigDecimal bandMin, BigDecimal bandMax, String currency, String bandText, boolean hasBand, boolean isTop,
		Ref jobLevel, Ref reportsTo, UUID reportsToId, BigDecimal minExp, boolean approves, boolean active,
		String description, Integer headcount, Integer titleCount, Integer directReports, Integer ladderDepth,
		Map<String, Object> custom, Long version, List<PositionNode> children,
		@JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> warnings) {

	/** A linked record: the position it reports to ({@code grade}) or its job level ({@code grade} holds the code). */
	public record Ref(UUID id, String name, String grade) {
	}

	public PositionNode withChildren(List<PositionNode> kids) {
		return new PositionNode(id, name, grade, level, track, trackLabel, bandMin, bandMax, currency, bandText, hasBand,
				isTop, jobLevel, reportsTo, reportsToId, minExp, approves, active, description, headcount, titleCount,
				directReports, ladderDepth, custom, version, kids, warnings);
	}

	public PositionNode withExtras(Map<String, Object> customValues, List<String> warningList) {
		return new PositionNode(id, name, grade, level, track, trackLabel, bandMin, bandMax, currency, bandText, hasBand,
				isTop, jobLevel, reportsTo, reportsToId, minExp, approves, active, description, headcount, titleCount,
				directReports, ladderDepth, customValues, version, children, warningList);
	}
}
