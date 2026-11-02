package com.ivoyant.hrms.payroll.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;

/**
 * One month's pay from an annual CTC, before anything is saved. Gross is CTC / 12 split into Basic, HRA and a special allowance, then
 * prorated for paid days. Employee PF is a percentage of Basic up to the wage cap; ESI applies while gross is within the threshold;
 * professional tax follows the work location's state; TDS is the year's estimated tax for the chosen regime spread over 12 months.
 * TDS is an estimate: it doesn't yet net off tax already deducted earlier in the year.
 */
final class PayrollEngine {

	record Settings(BigDecimal basicPct, BigDecimal hraPctOfBasic, BigDecimal pfRate, BigDecimal pfWageCap, BigDecimal esiThreshold, BigDecimal esiEmployee,
			BigDecimal esiEmployer) {
	}

	record Line(String key, String label, BigDecimal amount) {
	}

	record Adjustment(String label, BigDecimal amount, String kind) {
	}

	/** Declared deductions that only count in the old regime. */
	record Declaration(BigDecimal sec80c, BigDecimal sec80d, BigDecimal rentMonthly, BigDecimal nps, BigDecimal homeLoan) {

		static final Declaration NONE = new Declaration(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
	}

	record Result(List<Line> earnings, List<Line> deductions, List<Line> employer, BigDecimal gross, BigDecimal totalDeductions, BigDecimal net,
			BigDecimal annualTax) {
	}

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
	private static final BigDecimal TWELVE = BigDecimal.valueOf(12);

	private PayrollEngine() {
	}

	static Result month(BigDecimal ctcAnnual, BigDecimal paidDays, int daysInMonth, boolean pfOn, String regime, String ptState, Month month, Settings s,
			List<Adjustment> adjustments, Declaration decl) {
		BigDecimal monthly = ctcAnnual.divide(TWELVE, 2, RoundingMode.HALF_UP);
		BigDecimal f = paidDays.divide(BigDecimal.valueOf(daysInMonth), 6, RoundingMode.HALF_UP);
		BigDecimal basicFull = pct(monthly, s.basicPct());
		BigDecimal hraFull = pct(basicFull, s.hraPctOfBasic());
		BigDecimal specialFull = monthly.subtract(basicFull).subtract(hraFull).max(BigDecimal.ZERO);
		BigDecimal basic = rupee(basicFull.multiply(f));
		BigDecimal hra = rupee(hraFull.multiply(f));
		BigDecimal special = rupee(specialFull.multiply(f));
		List<Line> earnings = new ArrayList<>(List.of(new Line("basic", "Basic", basic), new Line("hra", "House rent allowance", hra),
				new Line("special", "Special allowance", special)));
		BigDecimal extraEarn = BigDecimal.ZERO;
		BigDecimal extraDed = BigDecimal.ZERO;
		for (Adjustment a : adjustments) {
			if ("DEDUCTION".equals(a.kind())) {
				extraDed = extraDed.add(a.amount());
			}
			else {
				extraEarn = extraEarn.add(a.amount());
				earnings.add(new Line("adj", a.label(), rupee(a.amount())));
			}
		}
		BigDecimal gross = basic.add(hra).add(special).add(rupee(extraEarn));

		BigDecimal pfWage = basic.min(s.pfWageCap());
		BigDecimal pf = pfOn ? rupee(pct(pfWage, s.pfRate())) : BigDecimal.ZERO;
		boolean esiApplies = gross.compareTo(s.esiThreshold()) <= 0 && gross.signum() > 0;
		BigDecimal esi = esiApplies ? up(pct(gross, s.esiEmployee())) : BigDecimal.ZERO;
		BigDecimal pt = professionalTax(ptState, gross, month);
		BigDecimal annualTax = annualTax(ctcAnnual, regime, decl, pfOn ? pct(basicFull.min(s.pfWageCap()), s.pfRate()).multiply(TWELVE) : BigDecimal.ZERO);
		BigDecimal tds = rupee(annualTax.divide(TWELVE, 2, RoundingMode.HALF_UP).multiply(f));
		List<Line> deductions = new ArrayList<>(List.of(new Line("pf", "Provident fund", pf), new Line("esi", "ESI", esi), new Line("pt", "Professional tax", pt),
				new Line("tds", "Income tax (TDS)", tds)));
		for (Adjustment a : adjustments) {
			if ("DEDUCTION".equals(a.kind())) {
				deductions.add(new Line("adj", a.label(), rupee(a.amount())));
			}
		}
		BigDecimal totalDed = pf.add(esi).add(pt).add(tds).add(rupee(extraDed));
		List<Line> employer = List.of(new Line("pf", "Employer PF", pf), new Line("esi", "Employer ESI", esiApplies ? up(pct(gross, s.esiEmployer())) : BigDecimal.ZERO));
		return new Result(earnings, deductions, employer, gross, totalDed, gross.subtract(totalDed), annualTax);
	}

	/** Monthly professional tax by state on the month's gross. States without PT, or with half-yearly collection, return zero. */
	static BigDecimal professionalTax(String state, BigDecimal gross, Month month) {
		if (state == null) {
			return BigDecimal.ZERO;
		}
		int g = gross.intValue();
		int pt = switch (state) {
			case "Karnataka" -> g >= 25000 ? 200 : 0;
			case "Maharashtra" -> g > 10000 ? (month == Month.FEBRUARY ? 300 : 200) : g > 7500 ? 175 : 0;
			case "Telangana", "Andhra Pradesh" -> g > 20000 ? 200 : g > 15000 ? 150 : 0;
			case "Gujarat" -> g > 12000 ? 200 : 0;
			case "West Bengal" -> g > 40000 ? 200 : g > 25000 ? 150 : g > 15000 ? 130 : g > 10000 ? 110 : 0;
			default -> 0;
		};
		return BigDecimal.valueOf(pt);
	}

	/** The year's income tax on CTC for a regime, with the standard deduction, rebate under section 87A and 4% cess. */
	static BigDecimal annualTax(BigDecimal ctcAnnual, String regime, Declaration d, BigDecimal employeePfYear) {
		boolean old = "OLD".equals(regime);
		BigDecimal taxable = ctcAnnual.subtract(BigDecimal.valueOf(old ? 50000 : 75000));
		if (old) {
			BigDecimal c80 = d.sec80c().add(employeePfYear).min(BigDecimal.valueOf(150000));
			BigDecimal hraRelief = d.rentMonthly().multiply(TWELVE).min(ctcAnnual.multiply(new BigDecimal("0.20")));
			taxable = taxable.subtract(c80).subtract(d.sec80d().min(BigDecimal.valueOf(75000))).subtract(d.nps().min(BigDecimal.valueOf(50000)))
					.subtract(d.homeLoan().min(BigDecimal.valueOf(200000))).subtract(hraRelief);
		}
		taxable = taxable.max(BigDecimal.ZERO);
		long t = taxable.longValue();
		double tax;
		if (old) {
			tax = slab(t, new long[] {250000, 500000, 1000000}, new double[] {0, 0.05, 0.20, 0.30});
			if (t <= 500000) {
				tax = 0;
			}
		}
		else {
			tax = slab(t, new long[] {400000, 800000, 1200000, 1600000, 2000000, 2400000}, new double[] {0, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30});
			if (t <= 1200000) {
				tax = 0;
			}
		}
		return BigDecimal.valueOf(tax * 1.04).setScale(0, RoundingMode.HALF_UP);
	}

	private static double slab(long income, long[] limits, double[] rates) {
		double tax = 0;
		long prev = 0;
		for (int i = 0; i < limits.length; i++) {
			if (income <= prev) {
				break;
			}
			tax += (Math.min(income, limits[i]) - prev) * rates[i];
			prev = limits[i];
		}
		if (income > prev) {
			tax += (income - prev) * rates[rates.length - 1];
		}
		return tax;
	}

	private static BigDecimal pct(BigDecimal v, BigDecimal p) {
		return v.multiply(p).divide(HUNDRED, 4, RoundingMode.HALF_UP);
	}

	private static BigDecimal rupee(BigDecimal v) {
		return v.setScale(0, RoundingMode.HALF_UP);
	}

	private static BigDecimal up(BigDecimal v) {
		return v.setScale(0, RoundingMode.CEILING);
	}
}
