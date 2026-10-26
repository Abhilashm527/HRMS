package com.ivoyant.hrms.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A change to an HR-approves field waiting for an approver. */
@Entity
@Table(name = "config_change_request")
@Getter
@Setter
public class ConfigChangeRequest {

	public static final String PENDING = "PENDING";
	public static final String APPROVED = "APPROVED";
	public static final String REJECTED = "REJECTED";
	public static final String CANCELLED = "CANCELLED";

	@Id
	private UUID id = UUID.randomUUID();

	@Column(name = "org_id", nullable = false)
	private UUID orgId;

	@Column(name = "area_key", nullable = false)
	private String areaKey;

	@Column(name = "record_id", nullable = false)
	private UUID recordId;

	@Column(name = "field_id", nullable = false)
	private UUID fieldId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "old_value", columnDefinition = "jsonb")
	private Object oldValue;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "new_value", columnDefinition = "jsonb")
	private Object newValue;

	@Column(name = "status", nullable = false)
	private String status = PENDING;

	@Column(name = "requested_by", nullable = false)
	private UUID requestedBy;

	@Column(name = "requested_at", nullable = false)
	private Instant requestedAt = Instant.now();

	@Column(name = "decided_by")
	private UUID decidedBy;

	@Column(name = "decided_at")
	private Instant decidedAt;

	@Column(name = "note")
	private String note;

	@Version
	private Long version;
}
