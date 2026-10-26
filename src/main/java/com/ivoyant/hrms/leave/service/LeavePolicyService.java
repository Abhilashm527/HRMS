package com.ivoyant.hrms.leave.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ivoyant.hrms.common.event.LeavePolicyChangedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.leave.ApplyError;
import com.ivoyant.hrms.leave.ApplyForm;
import com.ivoyant.hrms.leave.LeavePolicy;
import com.ivoyant.hrms.leave.LeaveTypeView;
import com.ivoyant.hrms.leave.ResolvedStep;
import com.ivoyant.hrms.org.Locations;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The leave engine's read side: types (cached), eligibility, day counts, application checks and the approval chain. */
@Service
class LeavePolicyService implements LeavePolicy {

	private static final BigDecimal HALF = new BigDecimal("0.5");

	private final LeaveTypeService types;
	private final ApprovalChainService chain;
	private final Locations locations;
	private final CurrentUser currentUser;
	private final Cache<UUID, List<LeaveTypeView>> cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).build();

	LeavePolicyService(LeaveTypeService types, ApprovalChainService chain, Locations locations, CurrentUser currentUser) {
		this.types = types;
		this.chain = chain;
		this.locations = locations;
		this.currentUser = currentUser;
	}

	@EventListener
	void onChanged(LeavePolicyChangedEvent e) {
		cache.invalidateAll();
	}

	@Override
	@Transactional(readOnly = true)
	public List<LeaveTypeView> activeTypes() {
		return cache.get(currentUser.orgId(), org -> types.list(true).stream().map(types::view).toList());
	}

	@Override
	public Optional<LeaveTypeView> type(String code) {
		return activeTypes().stream().filter(t -> t.code().equalsIgnoreCase(code)).findFirst();
	}

	@Override
	@Transactional(readOnly = true)
	public boolean eligible(UUID employeeId, LeaveTypeView t, LocalDate on) {
		return LeaveTypeService.eligible(types.person(employeeId), t.gender(), t.minServiceDays(), on);
	}

	@Override
	@Transactional(readOnly = true)
	public BigDecimal countDays(UUID employeeId, LeaveTypeView t, LocalDate from, LocalDate to, boolean half) {
		if (to.isBefore(from)) {
			return BigDecimal.ZERO;
		}
		long days = "CALENDAR".equals(t.countAs()) ? ChronoUnit.DAYS.between(from, to) + 1 : locations.workingDays(employeeId, from, to).size();
		if (half && t.halfDay() && days == 1 && from.equals(to)) {
			return HALF;
		}
		return BigDecimal.valueOf(days);
	}

	@Override
	@Transactional(readOnly = true)
	public List<ApplyError> validate(UUID employeeId, LeaveTypeView t, ApplyForm f) {
		List<ApplyError> errors = new ArrayList<>();
		if (!eligible(employeeId, t, f.from())) {
			errors.add(new ApplyError("type", "NOT_ELIGIBLE", "You are not eligible for " + t.name()));
			return errors;
		}
		if (f.to().isBefore(f.from())) {
			errors.add(new ApplyError("to", "DATES_ORDER", "The end date must not be before the start date"));
			return errors;
		}
		if (f.half() && !t.halfDay()) {
			errors.add(new ApplyError("half", "HALF_DAY_NOT_ALLOWED", t.name() + " can't be taken as a half day"));
		}
		BigDecimal days = countDays(employeeId, t, f.from(), f.to(), f.half());
		if (days.signum() == 0) {
			errors.add(new ApplyError("from", "NO_WORKING_DAYS", "Selected dates fall on weekends or holidays."));
			return errors;
		}
		long notice = ChronoUnit.DAYS.between(LocalDate.now(), f.from());
		if (t.noticeDays() > 0 && notice < t.noticeDays()) {
			errors.add(new ApplyError("from", "NOTICE", "Apply at least " + t.noticeDays() + (t.noticeDays() == 1 ? " day" : " days") + " in advance"));
		}
		if (t.maxConsecutive() > 0 && days.compareTo(BigDecimal.valueOf(t.maxConsecutive())) > 0) {
			errors.add(new ApplyError("to", "MAX_CONSECUTIVE", "You can take at most " + t.maxConsecutive() + " days in a row"));
		}
		if (t.docAfterDays() > 0 && days.compareTo(BigDecimal.valueOf(t.docAfterDays())) > 0 && !f.hasDocument()) {
			errors.add(new ApplyError("document", "DOCUMENT_REQUIRED", "Attach a document for more than " + t.docAfterDays() + " days"));
		}
		if (f.available() != null && days.compareTo(f.available()) > 0) {
			errors.add(new ApplyError("to", "BALANCE", "Only " + f.available().stripTrailingZeros().toPlainString() + " of " + t.name().toLowerCase(Locale.ROOT) + " available."));
		}
		return errors;
	}

	@Override
	@Transactional(readOnly = true)
	public List<ResolvedStep> chainFor(UUID employeeId, BigDecimal days) {
		return chain.chainFor(employeeId, days);
	}
}
