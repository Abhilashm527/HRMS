package com.ivoyant.hrms.common.storage;

import org.springframework.stereotype.Component;

/** Treats everything as clean until a scanner (ClamAV) is wired in. */
@Component
class NoopVirusScanner implements VirusScanner {

	@Override
	public boolean clean(byte[] content) {
		return true;
	}
}
