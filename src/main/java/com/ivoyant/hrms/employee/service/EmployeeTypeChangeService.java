package com.ivoyant.hrms.employee.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.employee.domain.Employee;
import com.ivoyant.hrms.employee.domain.EmployeeStatus;
import com.ivoyant.hrms.employee.repo.EmployeeRepository;
import com.ivoyant.hrms.org.EmploymentTypes;
import com.ivoyant.hrms.org.TypeView;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A change of employment type (for example an intern becoming full-time). The history keeps the old type; probation restarts when the
 * old type had none (or when asked); a type that is not fixed-term clears the contract end date.
 */
@Service
public class EmployeeTypeChangeService {

	private static final Set<String> REASONS = Set.of("CONVERSION", "CHANGE", "CORRECTION");

	private final EmployeeRecords records;
	private final EmployeeRepository employees;
	private final EmploymentTypes types;
	private final Audit audit;

	public EmployeeTypeChangeService(EmployeeRecords records, EmployeeRepository employees, EmploymentTypes types, Audit audit) {
		this.records = records;
		this.employees = employees;
		this.types = types;
		this.audit = audit;
	}

	/** Body: {@code typeId, effectiveDate, reason, contractEndDate, resetProbation}. */
	@Transactional
	public Map<String, Object> change(UUID employeeId, Map<String, Object> body) {
		Employee e = records.find(employeeId);
		if (e.getStatus() == EmployeeStatus.EXITED) {
			throw new ConflictException(e.getDisplayName() + " has left");
		}
		TypeView to = types.find(String.valueOf(body.get("typeId"))).orElseThrow(() -> invalid("typeId", "INVALID", "That employment type does not exist"));
		if (!to.active()) {
			throw invalid("typeId", "TYPE_INACTIVE", to.name() + " is inactive. Choose an active type");
		}
		TypeView from = e.getEmploymentTypeId() == null ? null : types.get(e.getEmploymentTypeId()).orElse(null);
		if (to.id().equals(e.getEmploymentTypeId())) {
			throw new ConflictException(e.getDisplayName() + " is already " + to.name());
		}
		LocalDate effective = body.get("effectiveDate") == null || body.get("effectiveDate").toString().isBlank() ? LocalDate.now()
				: LocalDate.parse(body.get("effectiveDate").toString());
		String reason = body.get("reason") == null ? "CONVERSION" : body.get("reason").toString().trim().toUpperCase();
		if (!REASONS.contains(reason)) {
			throw invalid("reason", "INVALID", "Reason must be one of " + String.join(", ", REASONS));
		}
		LocalDate contractEnd = body.get("contractEndDate") == null || body.get("contractEndDate").toString().isBlank() ? null
				: LocalDate.parse(body.get("contractEndDate").toString());
		if (to.fixedTerm() && (contractEnd == null || !contractEnd.isAfter(effective))) {
			throw invalid("contractEndDate", "CONTRACT_END_REQUIRED", to.name() + " needs a contract end date after " + effective);
		}
		boolean reset = body.get("resetProbation") == null ? from == null || from.probationMonths() == 0
				: Boolean.parseBoolean(body.get("resetProbation").toString());
		UUID old = e.getEmploymentTypeId();
		e.setEmploymentTypeId(to.id());
		e.setEmploymentType(to.name());
		if (reset) {
			e.setProbationEndDate(to.probationMonths() > 0 ? effective.plusMonths(to.probationMonths()) : null);
			e.setConfirmedOn(null);
		}
		e.setContractEndDate(to.fixedTerm() ? contractEnd : null);
		employees.saveAndFlush(e);
		records.recordTypeChange(e, old, reason, effective);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("employeeId", e.getId());
		out.put("typeId", to.id());
		out.put("type", to.name());
		out.put("probationEndDate", e.getProbationEndDate());
		out.put("contractEndDate", e.getContractEndDate());
		audit.updated("employee", "EmploymentType", employeeId, Map.of("type", from == null ? "" : from.name()), out);
		return out;
	}

	private static UnprocessableException invalid(String field, String code, String message) {
		return new UnprocessableException(List.of(ErrorItem.field(field, code, message)));
	}
}
