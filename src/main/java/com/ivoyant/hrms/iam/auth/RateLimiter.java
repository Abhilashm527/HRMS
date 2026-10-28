package com.ivoyant.hrms.iam.auth;

import com.ivoyant.hrms.common.web.StatusException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** In-memory sliding windows (one instance). A key is something like {@code ip:1.2.3.4} or {@code forgot:user-id}. */
@Component
class RateLimiter {

	private final ConcurrentMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

	/** Counts one hit; throws 429 when the key has had {@code max} hits within the window. */
	void hit(String key, int max, Duration window) {
		if (!tryHit(key, max, window)) {
			throw new StatusException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many attempts. Try again later.", java.util.Map.of("retryAfter", window.toSeconds()));
		}
	}

	/** Like {@link #hit} but answers false instead of throwing. */
	boolean tryHit(String key, int max, Duration window) {
		long now = System.currentTimeMillis();
		Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
		synchronized (q) {
			while (!q.isEmpty() && now - q.peekFirst() > window.toMillis()) {
				q.pollFirst();
			}
			if (q.size() >= max) {
				return false;
			}
			q.addLast(now);
			return true;
		}
	}
}
