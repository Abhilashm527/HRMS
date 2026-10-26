package com.ivoyant.hrms.org.domain;

import com.ivoyant.hrms.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** The organization (single tenant today; every table points at it through {@code org_id}). */
@Entity
@Table(name = "org")
@Getter
@Setter
public class Org extends BaseEntity {

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "legal_name")
	private String legalName;

	@Column(name = "code", nullable = false)
	private String code;

	@Column(name = "industry")
	private String industry;

	@Column(name = "country")
	private String country;

	@Column(name = "timezone", nullable = false)
	private String timezone;

	@Column(name = "fy_start_month", nullable = false)
	private int fyStartMonth = 4;

	@Column(name = "currency", nullable = false)
	private String currency = "INR";

	@Column(name = "primary_domain")
	private String primaryDomain;

	@Column(name = "status", nullable = false)
	private String status = "ACTIVE";

	@Column(name = "employee_id_prefix", nullable = false)
	private String employeeIdPrefix = "IVY";

	@Column(name = "logo_file_id")
	private UUID logoFileId;
}
