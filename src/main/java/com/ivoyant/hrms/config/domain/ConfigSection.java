package com.ivoyant.hrms.config.domain;

import com.ivoyant.hrms.common.domain.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "config_section")
@Getter
@Setter
public class ConfigSection extends TenantScoped {

	@Column(name = "area_key", nullable = false, updatable = false)
	private String areaKey;

	// not called "key": that is an HQL keyword
	@Column(name = "key", nullable = false, updatable = false)
	private String sectionKey;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "sort", nullable = false)
	private int sort;

	@Column(name = "system", nullable = false, updatable = false)
	private boolean system;

	@Column(name = "description")
	private String description;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	/** Profile: shown in the onboarding portal. */
	@Column(name = "show_in_portal", nullable = false)
	private boolean showInPortal;

	/** Rendered by code, not configurable (Job, Previous employment, Documents). */
	@Column(name = "locked", nullable = false)
	private boolean locked;

	/** Soft delete: the section and its fields are hidden everywhere, but what people entered stays. A built-in section can be restored. */
	@Column(name = "deleted_at")
	private java.time.Instant deletedAt;

	/** One of the sections that come with the product (Basic, Work, Job, Previous employment, Documents). */
	@Column(name = "builtin", nullable = false)
	private boolean builtin;
}
