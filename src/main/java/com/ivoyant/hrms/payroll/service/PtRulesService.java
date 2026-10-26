package com.ivoyant.hrms.payroll.service;

import com.ivoyant.hrms.payroll.PtRules;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Slabs as listed in the locations guide (section 9.1). Payroll must verify them against current state notifications. */
@Service
class PtRulesService implements PtRules {

	private static final String HALF_YEARLY = "half-yearly slabs up to ₹1,250";
	private static final String TELANGANA_AP = "₹150 above ₹15,000 · ₹200 above ₹20,000";

	private static final Map<String, String> SLABS = Map.of(
			"Karnataka", "₹200 / month when gross ≥ ₹25,000",
			"Telangana", TELANGANA_AP,
			"Andhra Pradesh", TELANGANA_AP,
			"Maharashtra", "₹175 above ₹7,500 · ₹200 above ₹10,000 (₹300 in February)",
			"Tamil Nadu", HALF_YEARLY,
			"Kerala", HALF_YEARLY,
			"Gujarat", "₹200 / month above ₹12,000",
			"West Bengal", "₹110–200 / month slabs");

	@Override
	public boolean applies(String state) {
		return state != null && SLABS.containsKey(state);
	}

	@Override
	public Optional<String> describe(String state) {
		return Optional.ofNullable(state).map(SLABS::get);
	}

	@Override
	public String label(String state) {
		return applies(state) ? state + " PT" : "No PT";
	}
}
