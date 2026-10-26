package com.ivoyant.hrms.org;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** What other modules may ask about positions (they never touch the repository). Cached and cleared on change. */
public interface Positions {

	Optional<PositionView> get(UUID id);

	Map<UUID, String> names(Collection<UUID> ids);

	boolean isActive(UUID id);

	/** The positions above it, nearest first. */
	List<UUID> ladder(UUID id);

	/** The single active position with no reports-to (the organization's top). */
	Optional<UUID> top();

	boolean approves(UUID id);

	boolean isTop(UUID id);

	/** How many positions use this job level (Levels & grades refuses to delete one that is used). */
	long countByLevel(UUID jobLevelId);

	/** Checks a full yearly amount in the organization's currency against the position's band. */
	BandCheck checkBand(UUID id, BigDecimal annualAmount);
}
