package com.ivoyant.hrms.payroll;

import java.util.Optional;

/**
 * Professional tax by state. The payroll module owns the rules so the text shown in Settings and the deduction
 * payroll makes come from one place. Today it only describes the slabs; the monthly amount is added with payroll runs.
 */
public interface PtRules {

	boolean applies(String state);

	/** The slab text, e.g. "₹200 / month when gross ≥ ₹25,000"; empty when the state has no professional tax. */
	Optional<String> describe(String state);

	/** "Karnataka PT", or "No PT". */
	String label(String state);
}
