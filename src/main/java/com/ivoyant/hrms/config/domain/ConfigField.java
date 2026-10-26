package com.ivoyant.hrms.config.domain;

import com.ivoyant.hrms.common.domain.TenantScoped;
import com.ivoyant.hrms.config.FieldType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "config_field")
@Getter
@Setter
public class ConfigField extends TenantScoped {

	@Column(name = "area_key", nullable = false, updatable = false)
	private String areaKey;

	@Column(name = "section_id", nullable = false)
	private UUID sectionId;

	// not called "key"/"type": those are HQL keywords
	@Column(name = "key", nullable = false, updatable = false)
	private String fieldKey;

	@Column(name = "label", nullable = false)
	private String label;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false)
	private FieldType fieldType;

	@Column(name = "required", nullable = false)
	private boolean required;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "default_value", columnDefinition = "jsonb")
	private Object defaultValue;

	@Column(name = "help_text")
	private String helpText;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "options", columnDefinition = "jsonb")
	private Object options;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "visibility_rule", columnDefinition = "jsonb")
	private Object visibilityRule;

	@Column(name = "sort", nullable = false)
	private int sort;

	@Column(name = "system", nullable = false, updatable = false)
	private boolean system;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	/** A custom field's "Masked" toggle: shown masked to roles that only get default read access. */
	@Column(name = "masked_default", nullable = false)
	private boolean maskedDefault;

	/** Non-null: the value is fed by another module (for example payroll) and is always read-only. */
	@Column(name = "source")
	private String source;

	/** Soft delete: hidden everywhere, values kept. */
	@Column(name = "deleted_at")
	private java.time.Instant deletedAt;

	/** What the employee may do with their own value, as picked in Employee fields: EDIT, HR_APPROVES, VIEW or HIDDEN. */
	@Column(name = "access_preset")
	private String accessPreset;

	/** PAN, IFSC, AADHAAR or ACCOUNT: the value must look like one. */
	@Column(name = "validator")
	private String validator;

	/** A custom field's "Visible to": EVERYONE, MANAGERS_HR or ADMINS. */
	@Column(name = "access_scope")
	private String accessScope;

	/** A custom field's "Employee sees". */
	@Column(name = "employee_sees", nullable = false)
	private boolean employeeSees = true;
}
