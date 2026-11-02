package com.ivoyant.hrms.performance.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.performance.service.PerformanceService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Review cycles, reviews and goals. Who may do what is checked in {@link PerformanceService}. */
@RestController
@RequestMapping("/api/v1/performance")
@RequiresMember
public class PerformanceController {

	private final PerformanceService perf;

	public PerformanceController(PerformanceService perf) {
		this.perf = perf;
	}

	@GetMapping("/cycles")
	public Map<String, Object> cycles() {
		return perf.cycles();
	}

	@PostMapping("/cycles")
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> create(@RequestBody Map<String, Object> body) {
		return perf.createCycle(body);
	}

	@GetMapping("/cycles/{id}")
	public Map<String, Object> cycle(@PathVariable UUID id) {
		return perf.cycle(id);
	}

	@DeleteMapping("/cycles/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID id) {
		perf.deleteCycle(id);
	}

	@PostMapping("/cycles/{id}/launch")
	public Map<String, Object> launch(@PathVariable UUID id) {
		return perf.launch(id);
	}

	@PostMapping("/cycles/{id}/close")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void close(@PathVariable UUID id) {
		perf.close(id);
	}

	@PostMapping("/cycles/{id}/remind")
	public Map<String, Object> remind(@PathVariable UUID id) {
		return perf.remind(id);
	}

	@GetMapping("/reviews/{id}")
	public Map<String, Object> review(@PathVariable UUID id) {
		return perf.review(id);
	}

	/** Body: {@code {answers, rating, submit}}. */
	@PutMapping("/reviews/{id}/self")
	public Map<String, Object> self(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return perf.saveSelf(id, body);
	}

	@PutMapping("/reviews/{id}/manager")
	public Map<String, Object> manager(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return perf.saveManager(id, body);
	}

	/** Body: {@code {rating, note}}. */
	@PutMapping("/reviews/{id}/final")
	public Map<String, Object> calibrate(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return perf.calibrate(id, body);
	}

	@GetMapping("/me")
	public Map<String, Object> mine() {
		return perf.mine();
	}

	@PostMapping("/goals")
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> addGoal(@RequestBody Map<String, Object> body) {
		return perf.saveGoal(null, body);
	}

	@PutMapping("/goals/{id}")
	public Map<String, Object> updateGoal(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return perf.saveGoal(id, body);
	}

	@DeleteMapping("/goals/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteGoal(@PathVariable UUID id) {
		perf.deleteGoal(id);
	}
}
