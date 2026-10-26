package com.ivoyant.hrms.onboarding.domain;

import java.time.LocalDate;
import java.util.List;

/** The pipeline status of a hire, derived from its facts (never set directly). */
public final class HireStatus {

	public static final List<String> STAGES = List.of("PROFILE", "M365", "INVITE", "DETAILS_DOCS", "HR_VERIFICATION", "DAY_1");
	public static final List<String> ORDER = List.of("DRAFT", "INVITED", "IN_PROGRESS", "REVIEW_PENDING", "ACTION_NEEDED",
			"READY_TO_JOIN", "JOINED");

	private HireStatus() {
	}

	/** One required-or-not document of a hire. */
	public record Doc(String key, boolean required, String status) {
	}

	public record Facts(boolean m365Active, boolean invited, boolean detailsSubmitted, List<Doc> docs, LocalDate doj,
			LocalDate today, boolean joined) {
	}

	public record Result(String status, boolean[] stages) {
	}

	public static Result compute(Facts f) {
		List<Doc> required = f.docs().stream().filter(Doc::required).toList();
		boolean allSubmitted = required.stream().allMatch(d -> d.status().equals("SUBMITTED") || d.status().equals("VERIFIED"));
		boolean allVerified = !required.isEmpty() && required.stream().allMatch(d -> d.status().equals("VERIFIED"));
		boolean[] st = {true, f.m365Active(), f.invited(), f.detailsSubmitted() && allSubmitted,
				f.detailsSubmitted() && allVerified, !f.doj().isAfter(f.today())};
		boolean rejected = f.docs().stream().anyMatch(d -> d.status().equals("REJECTED"));
		boolean submitted = f.docs().stream().anyMatch(d -> d.status().equals("SUBMITTED"));
		boolean verifiedOther = f.docs().stream().anyMatch(d -> d.status().equals("VERIFIED") && !d.key().equals("offer")
				&& !d.key().equals("offer_signed"));
		String status = f.joined() ? "JOINED"
				: !st[2] ? "DRAFT"
				: st[4] ? "READY_TO_JOIN"
				: rejected ? "ACTION_NEEDED"
				: submitted ? "REVIEW_PENDING"
				: f.detailsSubmitted() || verifiedOther ? "IN_PROGRESS"
				: "INVITED";
		return new Result(status, st);
	}

	/** The first stage that is not done yet, or null. */
	public static String next(boolean[] stages) {
		for (int i = 0; i < stages.length; i++) {
			if (!stages[i]) {
				return STAGES.get(i);
			}
		}
		return null;
	}
}
