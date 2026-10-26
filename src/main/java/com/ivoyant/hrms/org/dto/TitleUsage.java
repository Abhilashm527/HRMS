package com.ivoyant.hrms.org.dto;

/**
 * What still points at a title. {@code employees} hold it as their primary title now; {@code employeesTotal} includes former
 * employees; {@code historyRows} are title-history entries that mention it.
 */
public record TitleUsage(long employees, long employeesTotal, long extraHolders, long historyRows, long openJobs,
		long offers, long onboardingDrafts, long customFieldValues, String message) {

	/** Anything that stops the title from being deleted (it can still be merged into another title). */
	public boolean blocksDelete() {
		return employeesTotal > 0 || extraHolders > 0 || historyRows > 0 || openJobs > 0 || offers > 0
				|| onboardingDrafts > 0 || customFieldValues > 0;
	}
}
