package com.ivoyant.hrms.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A lookup list that has no table of its own: its rows are stored as one jsonb array. */
@Entity
@Table(name = "config_list")
@IdClass(ConfigListId.class)
@Getter
@Setter
@NoArgsConstructor
public class ConfigList {

	@Id
	@Column(name = "org_id")
	private UUID orgId;

	@Id
	@Column(name = "module")
	private String module;

	@Id
	@Column(name = "list_key")
	private String listKey;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "items", nullable = false, columnDefinition = "jsonb")
	private List<Map<String, Object>> items = new ArrayList<>();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Column(name = "updated_by")
	private UUID updatedBy;

	@Version
	private Long version;

	public ConfigList(UUID orgId, String module, String listKey) {
		this.orgId = orgId;
		this.module = module;
		this.listKey = listKey;
	}
}
