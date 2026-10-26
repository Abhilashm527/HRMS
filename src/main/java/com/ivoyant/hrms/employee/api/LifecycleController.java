package com.ivoyant.hrms.employee.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.employee.service.JobChangeService;
import com.ivoyant.hrms.employee.service.LifecycleService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Job changes, probation, exit and archive of an employee. Each call checks the matching action for the target. */
@RestController
@RequestMapping("/api/v1/employees/{id}")
public class LifecycleController {

	private final JobChangeService jobChanges;
	private final LifecycleService lifecycle;

	public LifecycleController(JobChangeService jobChanges, LifecycleService lifecycle) {
		this.jobChanges = jobChanges;
		this.lifecycle = lifecycle;
	}

	@PostMapping("/job-changes")
	@ResponseStatus(HttpStatus.CREATED)
	@RequiresMember
	public Map<String, Object> jobChange(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return jobChanges.create(id, body);
	}

	@GetMapping("/job-changes")
	@RequiresMember
	public List<Map<String, Object>> jobChanges(@PathVariable UUID id) {
		return jobChanges.list(id);
	}

	@DeleteMapping("/job-changes/{changeId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequiresMember
	public void cancelJobChange(@PathVariable UUID id, @PathVariable UUID changeId) {
		jobChanges.cancel(id, changeId);
	}

	/** The header's quick Designation change: a redesignation effective today. */
	@PutMapping("/position")
	@RequiresMember
	public Map<String, Object> position(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return jobChanges.create(id, Map.of("positionId", String.valueOf(body.get("positionId")), "kind", "REDESIGNATION"));
	}

	@PostMapping("/probation/confirm")
	@RequiresMember
	public Map<String, Object> confirm(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
		return lifecycle.confirm(id, body == null ? Map.of() : body);
	}

	@PostMapping("/probation/extend")
	@RequiresMember
	public Map<String, Object> extend(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return lifecycle.extend(id, body);
	}

	@PostMapping("/exit")
	@ResponseStatus(HttpStatus.CREATED)
	@RequiresMember
	public Map<String, Object> exit(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return lifecycle.startExit(id, body);
	}

	@GetMapping("/exit")
	@RequiresMember
	public Map<String, Object> getExit(@PathVariable UUID id) {
		return lifecycle.exit(id);
	}

	@PatchMapping("/exit")
	@RequiresMember
	public Map<String, Object> changeExit(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return lifecycle.changeLastDay(id, body);
	}

	@PostMapping("/exit/withdraw")
	@RequiresMember
	public Map<String, Object> withdraw(@PathVariable UUID id) {
		return lifecycle.withdraw(id);
	}

	@PostMapping("/archive")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequiresMember
	public void archive(@PathVariable UUID id) {
		lifecycle.archive(id);
	}

	@PostMapping("/unarchive")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequiresMember
	public void unarchive(@PathVariable UUID id) {
		lifecycle.unarchive(id);
	}
}
