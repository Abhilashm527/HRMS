package com.ivoyant.hrms.common.storage;

import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Uploaded files in Postgres ({@code stored_file}), so they survive redeploys. Reads fall back to the old local-disk folder for files
 * saved before the move; a file found there is copied into the database on first read.
 */
@Component
class DatabaseFileStore implements FileStore {

	private final JdbcClient jdbc;
	private final LocalFileStore disk;

	DatabaseFileStore(JdbcClient jdbc, @Value("${hrms.onboarding.storage-dir:./data/onboarding}") String dir) {
		this.jdbc = jdbc;
		this.disk = new LocalFileStore(dir);
	}

	@Override
	public void put(String key, byte[] content) throws IOException {
		jdbc.sql("""
				insert into stored_file (key, content, size_bytes) values (:k, :c, :s)
				on conflict (key) do update set content = excluded.content, size_bytes = excluded.size_bytes, created_at = now()""")
				.param("k", key).param("c", content).param("s", content.length).update();
	}

	@Override
	public byte[] get(String key) throws IOException {
		byte[] b = jdbc.sql("select content from stored_file where key = :k").param("k", key).query(byte[].class).optional().orElse(null);
		if (b != null) {
			return b;
		}
		byte[] old = disk.get(key);
		put(key, old);
		return old;
	}
}
