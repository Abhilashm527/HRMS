package com.ivoyant.hrms.employee.api;

import com.ivoyant.hrms.common.web.PageResponse;
import com.ivoyant.hrms.employee.service.EmployeeTypeChangeService;
import com.ivoyant.hrms.employee.service.ManagerChangeService;
import com.ivoyant.hrms.employee.service.OrgChartService;
import com.ivoyant.hrms.employee.service.ReportingLineService;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reporting lines: the table, the chain, the org tree, the health report, one manager change, and the bulk save with its preview.
 * Changing needs Edit on the org module; a change of manager can be dated (up to 90 days ahead, or a past date for a correction).
 */
@RestController
@RequestMapping("/api/v1")
public class ReportingController {

	private static final String VIEW = "@moduleAccess.has('org', 'VIEW')";
	private static final String EDIT = "@moduleAccess.has('org', 'EDIT')";

	private final ReportingLineService lines;
	private final ManagerChangeService managers;
	private final EmployeeTypeChangeService types;
	private final OrgChartService chart;

	public ReportingController(ReportingLineService lines, ManagerChangeService managers, EmployeeTypeChangeService types, OrgChartService chart) {
		this.chart = chart;
		this.lines = lines;
		this.managers = managers;
		this.types = types;
	}

	/** {@code state}: NO_MANAGER, ON_NOTICE_MANAGER or OVER_SPAN. */
	@GetMapping("/org/reporting-lines")
	@PreAuthorize(VIEW)
	public PageResponse<Map<String, Object>> rows(@RequestParam(required = false) UUID departmentId, @RequestParam(required = false) String q,
			@RequestParam(required = false) String state, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "100") int size) {
		return lines.rows(departmentId, q, state, page, size);
	}

	/**
	 * {@code {effectiveDate, rows: [{employeeId, managerId, dottedManagerId, extraDepartmentIds, teams, projects}]}}. Every row is
	 * checked against the lines as they will be afterwards; all or nothing.
	 */
	@PutMapping("/org/reporting-lines")
	@PreAuthorize(EDIT)
	public Map<String, Object> save(@RequestBody Map<String, Object> body) {
		return lines.save(body);
	}

	/** The same body as the save: says what would change and what is wrong, and saves nothing. */
	@PostMapping("/org/reporting-lines/preview")
	@PreAuthorize(EDIT)
	public Map<String, Object> preview(@RequestBody Map<String, Object> body) {
		return lines.preview(body);
	}

	@GetMapping("/org/tree")
	@PreAuthorize(VIEW)
	public Map<String, Object> tree(@RequestParam(required = false) UUID rootId, @RequestParam(required = false) Integer depth,
			@RequestParam(required = false) UUID departmentId, @RequestParam(required = false) String q, @RequestParam(required = false) String include) {
		return chart.tree(rootId, depth, departmentId, q, include);
	}

	/** By department: the top person, the department heads with head-counts, and the selected head's team two levels deep. */
	@GetMapping("/org/departments-view")
	@PreAuthorize(VIEW)
	public Map<String, Object> departmentsView(@RequestParam(required = false) String headId) {
		return chart.departmentsView(headId);
	}

	@GetMapping("/org/reporting-health")
	@PreAuthorize(VIEW)
	public Map<String, Object> health() {
		return lines.health();
	}

	@GetMapping("/employees/{id}/chain")
	@PreAuthorize("@authz.can('can_view_basic', 'employee:' + #id)")
	public Map<String, Object> chain(@PathVariable UUID id) {
		return lines.chain(id);
	}

	/** {@code {managerId, effectiveDate, reason}}; a future date is scheduled and applied by a nightly job. */
	@PutMapping("/employees/{id}/manager")
	@PreAuthorize(EDIT)
	public Map<String, Object> manager(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return managers.set(id, body);
	}

	/** {@code {typeId, effectiveDate, reason, contractEndDate, resetProbation}} */
	@PostMapping("/employees/{id}/employment-type")
	@PreAuthorize(EDIT)
	public Map<String, Object> employmentType(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return types.change(id, body);
	}
}
