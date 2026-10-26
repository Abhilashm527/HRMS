package com.ivoyant.hrms.onboarding.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.employee.EmployeeProvisioning;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The joined employee's first-90-days checklist. */
@Service
public class EssOnboardingService {

	private static final Map<String, String[]> TASKS = new LinkedHashMap<>();

	static {
		TASKS.put("docs", new String[] {"Upload joining documents", "Anything HR still needs from you", null});
		TASKS.put("offer", new String[] {"Sign offer letter & NDA", "Sign the documents HR sent you", null});
		TASKS.put("profile", new String[] {"Complete your profile", "Fill in the required details", "/me/profile"});
		TASKS.put("policies", new String[] {"Read and acknowledge policies", "Company policies you need to acknowledge", "/policies"});
		TASKS.put("laptop", new String[] {"Collect your laptop", "From the IT desk at your location", null});
		TASKS.put("m365", new String[] {"Sign in to Microsoft 365 & Slack", "Use your work email and set a new password", null});
		TASKS.put("buddy", new String[] {"Meet your onboarding buddy", "Your buddy helps you settle in", null});
		TASKS.put("goals", new String[] {"Agree 30-day goals with your manager", "Write them down together", null});
	}

	private static final Set<String> AUTO = Set.of("profile", "policies");

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final HireStatusService status;
	private final EmployeeProvisioning employees;

	EssOnboardingService(JdbcClient jdbc, CurrentUser currentUser, HireStatusService status, EmployeeProvisioning employees) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.status = status;
		this.employees = employees;
	}

	@Transactional
	public Map<String, Object> get() {
		UUID me = currentUser.requireEmployeeId();
		LocalDate doj = jdbc.sql("select joining_date from employee where id = :id").param("id", me).query(LocalDate.class).optional().orElse(null);
		Map<String, Object> out = new LinkedHashMap<>();
		boolean visible = doj != null && !doj.isBefore(status.today().minusDays(90));
		out.put("visible", visible);
		if (!visible) {
			out.put("tasks", List.of());
			out.put("left", 0);
			return out;
		}
		for (String key : TASKS.keySet()) {
			jdbc.sql("insert into employee_onboarding_task (employee_id, task_key) values (:e, :k) on conflict do nothing").param("e", me).param("k", key).update();
		}
		Map<String, Boolean> done = new LinkedHashMap<>();
		jdbc.sql("select task_key, done from employee_onboarding_task where employee_id = :e").param("e", me).query((rs, i) -> done.put(rs.getString(1), rs.getBoolean(2))).list();
		List<Map<String, Object>> tasks = new ArrayList<>();
		int left = 0;
		for (Map.Entry<String, String[]> t : TASKS.entrySet()) {
			boolean auto = AUTO.contains(t.getKey());
			boolean isDone = auto ? autoDone(t.getKey(), me) : Boolean.TRUE.equals(done.get(t.getKey()));
			if (!isDone) {
				left++;
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("key", t.getKey());
			m.put("label", t.getValue()[0]);
			m.put("sub", t.getValue()[1]);
			m.put("done", isDone);
			m.put("auto", auto);
			m.put("action", t.getValue()[2]);
			tasks.add(m);
		}
		out.put("tasks", tasks);
		out.put("left", left);
		return out;
	}

	private boolean autoDone(String key, UUID me) {
		return switch (key) {
			case "profile" -> employees.missingRequired(me).isEmpty();
			default -> true; // policies: nothing to acknowledge until the policy module exists
		};
	}

	@Transactional
	public Map<String, Object> set(String key, boolean done) {
		if (!TASKS.containsKey(key)) {
			throw new UnprocessableException(List.of(ErrorItem.field("key", "UNKNOWN_TASK", "Unknown task '" + key + "'")));
		}
		if (AUTO.contains(key)) {
			throw new UnprocessableException(List.of(ErrorItem.field("key", "AUTO_TASK", "This step completes by itself")));
		}
		UUID me = currentUser.requireEmployeeId();
		jdbc.sql("""
				insert into employee_onboarding_task (employee_id, task_key, done, done_at) values (:e, :k, :d, case when :d then now() else null end)
				on conflict (employee_id, task_key) do update set done = :d, done_at = case when :d then now() else null end""")
				.param("e", me).param("k", key).param("d", done).update();
		return get();
	}
}
