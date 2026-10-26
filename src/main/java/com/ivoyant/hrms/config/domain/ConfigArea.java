package com.ivoyant.hrms.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "config_area")
@Getter
@Setter
public class ConfigArea {

	@Id
	@Column(name = "key")
	private String areaKey;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "module", nullable = false)
	private String module;

	@Column(name = "description")
	private String description;

	@Column(name = "icon")
	private String icon;

	@Column(name = "sort", nullable = false)
	private int sort;

	/** Module whose role permission levels drive the default field access (see FieldAccessResolver). */
	@Column(name = "perm_module")
	private String permModule;
}
