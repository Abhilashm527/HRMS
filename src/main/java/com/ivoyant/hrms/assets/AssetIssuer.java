package com.ivoyant.hrms.assets;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Lets other modules ask the assets module to prepare equipment and software for a person. */
public interface AssetIssuer {

	/** A system request for a laptop (or the given hardware type), already approved, due on {@code dueOn}. Returns the request id. */
	UUID requestLaptop(UUID employeeId, UUID typeId, LocalDate dueOn);

	/** System requests for software licences, already approved. Returns the request ids. */
	List<UUID> requestLicences(UUID employeeId, List<UUID> licenseIds, LocalDate dueOn);
}
