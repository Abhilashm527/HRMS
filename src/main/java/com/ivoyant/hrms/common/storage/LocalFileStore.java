package com.ivoyant.hrms.common.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class LocalFileStore implements FileStore {

	private final Path root;

	LocalFileStore(@Value("${hrms.onboarding.storage-dir:./data/onboarding}") String dir) {
		this.root = Path.of(dir).toAbsolutePath().normalize();
	}

	private Path resolve(String key) {
		Path p = root.resolve(key).normalize();
		if (!p.startsWith(root)) {
			throw new IllegalArgumentException("Bad storage key");
		}
		return p;
	}

	@Override
	public void put(String key, byte[] content) throws IOException {
		Path p = resolve(key);
		Files.createDirectories(p.getParent());
		Files.write(p, content);
	}

	@Override
	public byte[] get(String key) throws IOException {
		return Files.readAllBytes(resolve(key));
	}
}
