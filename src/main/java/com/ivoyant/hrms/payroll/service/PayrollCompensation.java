package com.ivoyant.hrms.payroll.service;

import com.ivoyant.hrms.employee.view.CompensationView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The profile's Compensation tab, worked out by payroll: from the person's payroll salary when there is one (exact), otherwise from the
 * CTC on their profile (an estimate). Replaces the stand-in estimate in the employee module.
 */
@Component
@Primary
class PayrollCompensation implements CompensationView {

	private final JdbcClient jdbc;
	private final PayrollService payroll;

	PayrollCompensation(JdbcClient jdbc, PayrollService payroll) {
		this.jdbc = jdbc;
		this.payroll = payroll;
	}

	@Override
	public Breakdown forEmployee(UUID employeeId, BigDecimal profileCtc) {
		record Sal(BigDecimal ctc, String regime, boolean pf, String state) {
		}
		Sal s = jdbc.sql("""
				select s.ctc_annual, s.regime, s.pf_enabled, l.state from salary s join employee e on e.id = s.employee_id left join location l on l.id = e.location_id
				where s.employee_id = :e""").param("e", employeeId).query((rs, i) -> new Sal(rs.getBigDecimal(1), rs.getString(2), rs.getBoolean(3), rs.getString(4)))
				.optional().orElse(null);
		boolean estimated = s == null;
		BigDecimal ctc = s != null ? s.ctc() : PayrollService.profileCtc(profileCtc == null ? null : profileCtc.toPlainString());
		if (ctc == null) {
			return new Breakdown(null, "New tax regime", null, List.of(), List.of(), null, true);
		}
		String regime = s == null ? "NEW" : s.regime();
		String state = s != null ? s.state()
				: jdbc.sql("select l.state from employee e join location l on l.id = e.location_id where e.id = :e").param("e", employeeId).query(String.class).optional().orElse(null);
		LocalDate now = LocalDate.now();
		PayrollEngine.Result r = PayrollEngine.month(ctc, BigDecimal.valueOf(now.lengthOfMonth()), now.lengthOfMonth(), s == null || s.pf(), regime, state, now.getMonth(),
				payroll.settings(), List.of(), PayrollEngine.Declaration.NONE);
		List<Line> earnings = new java.util.ArrayList<>(r.earnings().stream().map(l -> new Line(l.key(), l.label(), l.amount())).toList());
		earnings.add(new Line("gross", "Gross", r.gross()));
		List<Line> deductions = r.deductions().stream().map(l -> new Line(l.key(), l.label(), l.amount())).toList();
		return new Breakdown(ctc, "OLD".equals(regime) ? "Old tax regime" : "New tax regime", state == null ? "No PT" : state + " PT", earnings, deductions, r.net(),
				estimated);
	}
}
