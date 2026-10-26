package com.ivoyant.hrms.assets;

import java.util.UUID;

/** Hands out asset ID numbers from the organization's ID formats. A number is never reused, even if the asset is deleted later. */
public interface AssetIds {

	/**
	 * The next ID for the format, for example {@code CMP-LPT-0042}. {@code code} is what stands for {@code {TYPE}} (hardware) or
	 * {@code {APP}} (software); each code has its own counter. Call it inside the transaction that saves the asset.
	 */
	String next(UUID formatId, String code);

	/** The active format for hardware of this ownership, or software of this licence type. */
	java.util.Optional<UUID> formatFor(String kind, UUID ownershipOrLicenseTypeId);
}
