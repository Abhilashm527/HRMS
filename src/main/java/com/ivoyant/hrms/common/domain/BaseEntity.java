package com.ivoyant.hrms.common.domain;

import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public abstract class BaseEntity {

	@Id
	private UUID id = UUID.randomUUID();

	@CreatedDate
	private Instant createdAt;

	@CreatedBy
	private UUID createdBy;

	@LastModifiedDate
	private Instant updatedAt;

	@LastModifiedBy
	private UUID updatedBy;

	// wrapper type so Spring Data treats an entity with a null version as new, even though the id is pre-assigned
	@Version
	private Long version;
}
