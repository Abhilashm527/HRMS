package com.ivoyant.hrms.org.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ivoyant.hrms.common.event.OrgSettingsChangedEvent;
import com.ivoyant.hrms.common.event.OrgUpdatedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.org.OrgProfile;
import com.ivoyant.hrms.org.OrgSettings;
import com.ivoyant.hrms.org.OrgView;
import com.ivoyant.hrms.org.PasswordPolicy;
import com.ivoyant.hrms.org.SalaryVisibility;
import com.ivoyant.hrms.org.domain.OrgSettingsRow;
import com.ivoyant.hrms.org.repo.OrgRepository;
import com.ivoyant.hrms.org.repo.OrgSettingsRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Period;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What other modules read about the organization. Cached for a minute and cleared whenever it changes. */
@Service
class OrgFacadeService implements OrgProfile, OrgSettings {

	private final OrgRepository orgs;
	private final OrgSettingsRepository settingsRepo;
	private final CurrentUser currentUser;
	private final Cache<UUID, OrgView> profiles = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).build();
	private final Cache<UUID, OrgSettingsRow> settings = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).build();

	OrgFacadeService(OrgRepository orgs, OrgSettingsRepository settingsRepo, CurrentUser currentUser) {
		this.orgs = orgs;
		this.settingsRepo = settingsRepo;
		this.currentUser = currentUser;
	}

	@EventListener
	void onOrgUpdated(OrgUpdatedEvent e) {
		profiles.invalidateAll();
	}

	@EventListener
	void onSettingsChanged(OrgSettingsChangedEvent e) {
		settings.invalidateAll();
	}

	// ---- profile ----

	@Override
	@Transactional(readOnly = true)
	public OrgView current() {
		return profiles.get(currentUser.orgId(), id -> orgs.findById(id).map(o -> new OrgView(o.getId(), o.getName(),
				o.getLegalName(), o.getCode(), o.getCountry(), ZoneId.of(o.getTimezone()), o.getFyStartMonth(),
				o.getCurrency(), o.getPrimaryDomain(), o.getStatus(), o.getEmployeeIdPrefix()))
				.orElseThrow(() -> new NotFoundException("Organization " + id + " not found")));
	}

	@Override
	public ZoneId zone() {
		return current().timezone();
	}

	@Override
	public YearMonth fyStart(LocalDate anyDate) {
		int start = current().fyStartMonth();
		int year = anyDate.getMonthValue() >= start ? anyDate.getYear() : anyDate.getYear() - 1;
		return YearMonth.of(year, start);
	}

	@Override
	public boolean isArchived() {
		return "ARCHIVED".equals(current().status());
	}

	// ---- settings ----

	private OrgSettingsRow row() {
		return settings.get(currentUser.orgId(), id -> settingsRepo.findById(id).orElseGet(() -> {
			OrgSettingsRow defaults = new OrgSettingsRow();
			defaults.setOrgId(id);
			return defaults;
		}));
	}

	@Override
	@Transactional(readOnly = true)
	public boolean autoEmployeeId() {
		return row().isAutoId();
	}

	@Override
	public String idFormat() {
		return row().getIdFormat();
	}

	@Override
	public boolean managerApprovesChanges() {
		return row().isReqMgr();
	}

	@Override
	public boolean multiTitle() {
		return row().isMultiTitle();
	}

	@Override
	public boolean positionMandatory() {
		return row().isPosMandatory();
	}

	@Override
	public String titleFormat() {
		return row().getTitleFormat();
	}

	@Override
	public boolean nestedDepartments() {
		return row().isNestedDepts();
	}

	@Override
	public int maxDepartmentDepth() {
		return row().getMaxDeptDepth();
	}

	@Override
	public boolean departmentCodeRequired() {
		return row().isDeptCodeReq();
	}

	@Override
	public int maxSpan() {
		return row().getMaxSpan();
	}

	@Override
	public int maxMentees() {
		return row().getMaxMentees();
	}

	@Override
	public int maxReportingDepth() {
		return row().getMaxRepDepth();
	}

	@Override
	public boolean customRoles() {
		return row().isCustomRoles();
	}

	@Override
	public boolean roleInheritance() {
		return row().isRoleInherit();
	}

	@Override
	public boolean outOfBandNeedsApproval() {
		return row().isOobApproval();
	}

	@Override
	public boolean sensitiveRoleSecondApprover() {
		return row().isSensApproval();
	}

	@Override
	public boolean logPermissionChanges() {
		return row().isAuditPerm();
	}

	@Override
	public SalaryVisibility salaryVisibility() {
		return SalaryVisibility.valueOf(row().getSalaryVis());
	}

	@Override
	public boolean orgChart() {
		return row().isOrgChart();
	}

	@Override
	public boolean analytics() {
		return row().isAnalytics();
	}

	@Override
	public Period retention() {
		return Period.ofYears(Integer.parseInt(row().getRetention().substring(1)));
	}

	@Override
	public boolean auditLog() {
		return row().isAuditLog();
	}

	@Override
	public boolean twoFactor() {
		return row().isTwoFa();
	}

	@Override
	public PasswordPolicy passwordPolicy() {
		return PasswordPolicy.valueOf(row().getPwPolicy());
	}

	@Override
	public boolean encryptSensitive() {
		return row().isEncrypt();
	}
}
