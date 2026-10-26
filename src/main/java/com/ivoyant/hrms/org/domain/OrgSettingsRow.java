package com.ivoyant.hrms.org.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** The 22 organization settings, one typed row per organization. */
@Entity
@Table(name = "org_settings")
@Getter
@Setter
public class OrgSettingsRow {

	@Id
	@Column(name = "org_id")
	private UUID orgId;

	@Column(name = "auto_id", nullable = false)
	private boolean autoId = true;

	@Column(name = "id_format", nullable = false)
	private String idFormat = "{PREFIX}-{SEQ4}";

	@Column(name = "req_mgr", nullable = false)
	private boolean reqMgr = true;

	@Column(name = "multi_title", nullable = false)
	private boolean multiTitle;

	@Column(name = "pos_mandatory", nullable = false)
	private boolean posMandatory = true;

	@Column(name = "title_format", nullable = false)
	private String titleFormat = "{title} · {position}";

	@Column(name = "out_of_band_needs_approval", nullable = false)
	private boolean oobApproval = true;

	@Column(name = "max_span", nullable = false)
	private int maxSpan = 15;

	@Column(name = "max_mentees", nullable = false)
	private int maxMentees = 10;

	@Column(name = "nested_depts", nullable = false)
	private boolean nestedDepts = true;

	@Column(name = "max_dept_depth", nullable = false)
	private int maxDeptDepth = 5;

	@Column(name = "dept_code_req", nullable = false)
	private boolean deptCodeReq = true;

	@Column(name = "max_rep_depth", nullable = false)
	private int maxRepDepth = 10;

	@Column(name = "custom_roles", nullable = false)
	private boolean customRoles = true;

	@Column(name = "role_inherit", nullable = false)
	private boolean roleInherit = true;

	@Column(name = "sens_approval", nullable = false)
	private boolean sensApproval = true;

	@Column(name = "audit_perm", nullable = false)
	private boolean auditPerm = true;

	@Column(name = "salary_vis", nullable = false)
	private String salaryVis = "MANAGERS_HR";

	@Column(name = "org_chart", nullable = false)
	private boolean orgChart = true;

	@Column(name = "analytics", nullable = false)
	private boolean analytics = true;

	@Column(name = "retention", nullable = false)
	private String retention = "Y7";

	@Column(name = "audit_log", nullable = false)
	private boolean auditLog = true;

	@Column(name = "two_fa", nullable = false)
	private boolean twoFa = true;

	@Column(name = "pw_policy", nullable = false)
	private String pwPolicy = "ENTRA";

	@Column(name = "encrypt", nullable = false)
	private boolean encrypt = true;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Column(name = "updated_by")
	private UUID updatedBy;

	@Version
	private Long version;
}
