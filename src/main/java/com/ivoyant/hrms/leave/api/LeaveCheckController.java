package com.ivoyant.hrms.leave.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.BadRequestException;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.leave.ApplyError;
import com.ivoyant.hrms.leave.ApplyForm;
import com.ivoyant.hrms.leave.LeavePolicy;
import com.ivoyant.hrms.leave.LeaveTypeView;
import com.ivoyant.hrms.leave.ResolvedStep;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The apply form's live check: how many days the dates count for, what is wrong with the application, and who would approve it. Nothing is
 * saved. HR can check for another employee with {@code employeeId}.
 */
@RestController
@RequestMapping("/api/v1/leave/apply")
@RequiresMember
public class LeaveCheckController {

	private final LeavePolicy policy;
	private final CurrentUser currentUser;
	private final ModuleAccess access;

	public LeaveCheckController(LeavePolicy policy, CurrentUser currentUser, ModuleAccess access) {
		this.policy = policy;
		this.currentUser = currentUser;
		this.access = access;
	}

	/** {@code {typeCode, from, to, half, hasDocument, available, employeeId?}} */
	@PostMapping("/check")
	public Map<String, Object> check(@RequestBody Map<String, Object> body) {
		UUID me = currentUser.requireEmployeeId();
		UUID employee = body.get("employeeId") == null ? me : UUID.fromString(body.get("employeeId").toString());
		if (!employee.equals(me) && !access.has("leave", "ADMIN")) {
			throw new ForbiddenException("You can only check your own leave");
		}
		if (body.get("typeCode") == null || body.get("from") == null) {
			throw new BadRequestException("typeCode and from are required");
		}
		LeaveTypeView type = policy.type(body.get("typeCode").toString()).orElseThrow(() -> new NotFoundException("There is no active leave type " + body.get("typeCode")));
		LocalDate from = LocalDate.parse(body.get("from").toString());
		LocalDate to = body.get("to") == null ? from : LocalDate.parse(body.get("to").toString());
		boolean half = Boolean.parseBoolean(String.valueOf(body.get("half")));
		ApplyForm form = new ApplyForm(from, to, half, Boolean.parseBoolean(String.valueOf(body.get("hasDocument"))),
				body.get("available") == null ? null : new BigDecimal(body.get("available").toString()));
		List<ApplyError> errors = policy.validate(employee, type, form);
		BigDecimal days = policy.countDays(employee, type, from, to, half);
		List<ResolvedStep> approvers = policy.chainFor(employee, days);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("days", days);
		out.put("errors", errors);
		out.put("approvers", approvers);
		out.put("autoApprove", approvers.isEmpty());
		return out;
	}
}
