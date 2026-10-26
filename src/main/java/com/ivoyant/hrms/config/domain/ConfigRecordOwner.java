package com.ivoyant.hrms.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Who a record belongs to, for areas whose records have no module of their own yet. */
@Entity
@Table(name = "config_record_owner")
@IdClass(ConfigRecordOwnerId.class)
@Getter
@Setter
@NoArgsConstructor
public class ConfigRecordOwner {

	@Id
	@Column(name = "org_id")
	private UUID orgId;

	@Id
	@Column(name = "area_key")
	private String areaKey;

	@Id
	@Column(name = "record_id")
	private UUID recordId;

	@Column(name = "owner_employee_id", nullable = false)
	private UUID ownerEmployeeId;

	public ConfigRecordOwner(UUID orgId, String areaKey, UUID recordId, UUID ownerEmployeeId) {
		this.orgId = orgId;
		this.areaKey = areaKey;
		this.recordId = recordId;
		this.ownerEmployeeId = ownerEmployeeId;
	}
}
