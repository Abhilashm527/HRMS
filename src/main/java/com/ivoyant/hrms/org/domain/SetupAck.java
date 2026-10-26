package com.ivoyant.hrms.org.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A setup step an admin has reviewed and confirmed. */
@Entity
@Table(name = "org_setup_ack")
@IdClass(SetupAckId.class)
@Getter
@Setter
@NoArgsConstructor
public class SetupAck {

	@Id
	@Column(name = "org_id")
	private UUID orgId;

	@Id
	@Column(name = "key")
	private String key;

	@Column(name = "acked_by", nullable = false)
	private UUID ackedBy;

	@Column(name = "acked_at", nullable = false)
	private Instant ackedAt = Instant.now();

	public SetupAck(UUID orgId, String key, UUID ackedBy) {
		this.orgId = orgId;
		this.key = key;
		this.ackedBy = ackedBy;
	}
}
