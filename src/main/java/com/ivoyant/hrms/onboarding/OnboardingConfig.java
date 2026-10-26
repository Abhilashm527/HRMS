package com.ivoyant.hrms.onboarding;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** What the hire flow reads from Settings → Configuration → Onboarding configuration. */
public interface OnboardingConfig {

	/** Active steps in order; a step that collects fields lists them. */
	List<StepView> hireSteps();

	/** The Microsoft 365 rule for the employment type: a rule for that type wins over the "All types" one. */
	Optional<M365Rule> m365For(UUID employmentTypeId);

	/** The invite email for the employment type, with the same precedence. */
	Optional<InviteTemplate> inviteFor(UUID employmentTypeId);

	/** Documents that apply to everyone plus those for this employment type. */
	List<DocumentView> documentsFor(UUID employmentTypeId);

	/** The checklist tasks, copied into a new hire's onboarding. */
	List<TaskTemplate> checklist();

	/** Fills the placeholders of an invite; values are HTML-escaped. */
	RenderedInvite renderInvite(InviteTemplate template, Map<String, String> values);

	record StepView(UUID id, String key, String name, String kind, int sort, boolean locked, List<FieldView> fields) {
	}

	record FieldView(String key, String label, String type, boolean required, List<String> options) {
	}

	record M365Rule(UUID id, String name, UUID employmentTypeId, String upnFormat, String licenseSku, List<String> groups, boolean forceReset) {
	}

	record InviteTemplate(UUID id, String name, UUID employmentTypeId, String subject, String body, int dueDaysBefore, Integer remindEveryDays,
			boolean ccManager) {
	}

	record DocumentView(UUID id, String key, String name, String hint, String group, UUID employmentTypeId, boolean required) {
	}

	record TaskTemplate(UUID id, String name, String phase, String owner) {
	}

	record RenderedInvite(String subject, String body) {
	}
}
