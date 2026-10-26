package com.ivoyant.hrms.employee.view;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The prototype's split: Basic 40% of monthly CTC, HRA 50% of Basic, PF 12% of Basic (cap ₹1,800), ESI 0.75% when gross ≤ ₹21,000. */
@Component
class EstimatedCompensation implements CompensationView {

	private static final BigDecimal ZERO = BigDecimal.ZERO;

	@Override
	public Breakdown forEmployee(UUID employeeId, BigDecimal annualCtc) {
		if (annualCtc == null) {
			return new Breakdown(null, "New tax regime", null, List.of(), List.of(), null, true);
		}
		BigDecimal monthly = annualCtc.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
		BigDecimal basic = monthly.multiply(new BigDecimal("0.40")).setScale(0, RoundingMode.HALF_UP);
		BigDecimal hra = basic.multiply(new BigDecimal("0.50")).setScale(0, RoundingMode.HALF_UP);
		BigDecimal special = monthly.subtract(basic).subtract(hra).setScale(0, RoundingMode.HALF_UP).max(ZERO);
		BigDecimal gross = basic.add(hra).add(special);
		BigDecimal pf = basic.multiply(new BigDecimal("0.12")).min(new BigDecimal("1800")).setScale(0, RoundingMode.HALF_UP);
		BigDecimal esi = gross.compareTo(new BigDecimal("21000")) <= 0 ? gross.multiply(new BigDecimal("0.0075")).setScale(0, RoundingMode.HALF_UP) : ZERO;
		BigDecimal pt = gross.compareTo(new BigDecimal("25000")) >= 0 ? new BigDecimal("200") : ZERO;
		BigDecimal tds = ZERO;
		List<Line> earnings = new ArrayList<>(List.of(new Line("basic", "Basic", basic), new Line("hra", "House rent allowance", hra),
				new Line("special", "Special allowance", special), new Line("gross", "Gross", gross)));
		List<Line> deductions = List.of(new Line("pf", "Provident fund", pf), new Line("esi", "ESI", esi), new Line("pt", "Professional tax", pt),
				new Line("tds", "TDS", tds));
		BigDecimal net = gross.subtract(pf).subtract(esi).subtract(pt).subtract(tds);
		return new Breakdown(annualCtc, "New tax regime", "Professional tax", earnings, deductions, net, true);
	}
}
