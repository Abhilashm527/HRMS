package com.ivoyant.hrms.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Value of one field of one record, for areas whose values are not stored by their own module. */
@Entity
@Table(name = "config_field_value")
@IdClass(ConfigFieldValueId.class)
@Getter
@Setter
@NoArgsConstructor
public class ConfigFieldValue {

	@Id
	@Column(name = "org_id")
	private UUID orgId;

	@Id
	@Column(name = "area_key")
	private String areaKey;

	@Id
	@Column(name = "record_id")
	private UUID recordId;

	@Id
	@Column(name = "field_id")
	private UUID fieldId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "value", columnDefinition = "jsonb")
	private Object value;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Column(name = "updated_by")
	private UUID updatedBy;

	@Version
	private Long version;

	public ConfigFieldValue(UUID orgId, String areaKey, UUID recordId, UUID fieldId) {
		this.orgId = orgId;
		this.areaKey = areaKey;
		this.recordId = recordId;
		this.fieldId = fieldId;
	}
}
