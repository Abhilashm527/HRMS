package com.ivoyant.hrms.onboarding.api;

import com.ivoyant.hrms.common.web.StatusException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** In-memory limits per link: 60 requests a minute and 20 uploads an hour. */
@Component
class PortalRateLimiter {

	private final ConcurrentMap<String, Deque<Long>> requests = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, Deque<Long>> uploads = new ConcurrentHashMap<>();

	void request(String token) {
		hit(requests, token, 60, Duration.ofMinutes(1));
	}

	void upload(String token) {
		hit(requests, token, 60, Duration.ofMinutes(1));
		hit(uploads, token, 20, Duration.ofHours(1));
	}

	private static void hit(ConcurrentMap<String, Deque<Long>> map, String token, int max, Duration window) {
		long now = System.currentTimeMillis();
		Deque<Long> q = map.computeIfAbsent(token, k -> new ArrayDeque<>());
		synchronized (q) {
			while (!q.isEmpty() && now - q.peekFirst() > window.toMillis()) {
				q.pollFirst();
			}
			if (q.size() >= max) {
				throw new StatusException(HttpStatus.TOO_MANY_REQUESTS, "TOO_SOON", "Too many requests. Try again in a moment.");
			}
			q.addLast(now);
		}
	}
}
