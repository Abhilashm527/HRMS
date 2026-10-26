package com.ivoyant.hrms.common.storage;

/** Scans an upload before HR can see it. */
public interface VirusScanner {

	/** True when the content is clean. */
	boolean clean(byte[] content);
}
