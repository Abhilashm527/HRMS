package com.ivoyant.hrms.common.storage;

import java.io.IOException;

/** Where uploaded files live. The local-disk store is for development; swap in S3/Azure Blob by implementing this. */
public interface FileStore {

	void put(String key, byte[] content) throws IOException;

	byte[] get(String key) throws IOException;
}
