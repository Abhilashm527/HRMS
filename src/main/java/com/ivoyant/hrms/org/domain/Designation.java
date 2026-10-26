package com.ivoyant.hrms.org.domain;

import com.ivoyant.hrms.common.domain.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** A position (the UI's word; the table and class keep the old name). {@code code} is the UI's "Grade". */
@Entity
@Table(name = "designation")
@Getter
@Setter
public class Designation extends TenantScoped {

	@Column(name = "title", nullable = false)
	private String title;

	@Column(name = "code")
	private String code;

	@Column(name = "level")
	private Integer level;

	/** A {@link PositionTrack} name. */
	@Column(name = "track", nullable = false)
	private String track = PositionTrack.INDIVIDUAL_CONTRIBUTOR.name();

	/** Full yearly amounts in the organization's currency. */
	@Column(name = "band_min")
	private BigDecimal bandMin;

	@Column(name = "band_max")
	private BigDecimal bandMax;

	@Column(name = "reports_to_id")
	private UUID reportsToId;

	@Column(name = "min_exp")
	private BigDecimal minExp;

	@Column(name = "approves", nullable = false)
	private boolean approves;

	@Column(name = "job_level_id")
	private UUID jobLevelId;

	@Column(name = "description")
	private String description;

	@Column(name = "active", nullable = false)
	private boolean active = true;
}
