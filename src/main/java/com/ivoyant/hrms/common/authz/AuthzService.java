package com.ivoyant.hrms.common.authz;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ivoyant.hrms.common.security.CurrentUser;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.openfga.sdk.api.client.model.ClientListObjectsRequest;
import dev.openfga.sdk.api.client.model.ClientRelationshipCondition;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.openfga.sdk.api.client.model.ClientTupleKeyWithoutCondition;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
import dev.openfga.sdk.api.configuration.ClientWriteOptions;
import dev.openfga.sdk.api.model.WriteRequestDeletes;
import dev.openfga.sdk.api.model.WriteRequestWrites;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Thin wrapper over the OpenFGA SDK. Registered as bean {@code authz} for use in SpEL:
 * {@code @PreAuthorize("@authz.can('can_view_basic', 'employee:' + #id)")}.
 * <p>
 * Fails closed: if OpenFGA is not configured or a check fails, access is denied. Writes are meant to be
 * called from the outbox worker, never inside a business transaction.
 */
@Service("authz")
public class AuthzService {

	private static final Logger log = LoggerFactory.getLogger(AuthzService.class);

	private final OpenFgaClient client;
	private final CurrentUser currentUser;
	private final Cache<String, Boolean> checkCache = Caffeine.newBuilder()
			.expireAfterWrite(Duration.ofSeconds(45))
			.maximumSize(10_000)
			.build();
	private volatile String storeId;
	private volatile boolean freshStore;

	public AuthzService(OpenFgaClient client, FgaProperties props, CurrentUser currentUser) {
		this.client = client;
		this.currentUser = currentUser;
		this.storeId = props.configured() ? props.storeId() : null;
	}

	public boolean configured() {
		return StringUtils.hasText(storeId);
	}

	/** The store in use (configured or created by the local bootstrap); null when none. */
	public String storeId() {
		return storeId;
	}

	/** Point the client at a store (and optionally a model); used by the local bootstrap. */
	public void useStore(String storeId, String authorizationModelId) {
		this.storeId = storeId;
		client.setStoreId(storeId);
		if (StringUtils.hasText(authorizationModelId)) {
			client.setAuthorizationModelId(authorizationModelId);
		}
		checkCache.invalidateAll();
	}

	/** True when the local bootstrap just created the store or its model, so tuples must be (re)loaded. */
	public boolean isFreshStore() {
		return freshStore;
	}

	void markFreshStore() {
		this.freshStore = true;
	}

	// ---- checks for the current user (used from @PreAuthorize) ----

	/** Does the current user have {@code relation} on {@code object} (e.g. {@code employee:<uuid>})? */
	public boolean can(String relation, String object) {
		UUID employeeId = currentUser.employeeId();
		return employeeId != null && check(FgaTuples.user(employeeId), relation, object);
	}

	public boolean isHrAdmin() {
		return can("hr_admin", FgaTuples.org(currentUser.orgId()));
	}

	public boolean isSuperAdmin() {
		return can("super_admin", FgaTuples.org(currentUser.orgId()));
	}

	public boolean isMember() {
		return can("member", FgaTuples.org(currentUser.orgId()));
	}

	// ---- raw operations ----

	public boolean check(String user, String relation, String object) {
		if (!configured()) {
			log.warn("OpenFGA is not configured (openfga.store-id empty); denying {} {} {}", user, relation, object);
			return false;
		}
		String key = user + '|' + relation + '|' + object;
		Boolean cached = checkCache.getIfPresent(key);
		if (cached != null) {
			return cached;
		}
		try {
			ClientCheckRequest request = new ClientCheckRequest().user(user).relation(relation)._object(object)
					.context(Map.of("current_time", Instant.now().toString()));
			boolean allowed = retrying(() -> Boolean.TRUE.equals(client.check(request).get().getAllowed()));
			checkCache.put(key, allowed);
			return allowed;
		}
		catch (Exception ex) {
			interruptIfNeeded(ex);
			log.error("OpenFGA check failed for {} {} {}; denying", user, relation, object, ex);
			return false;
		}
	}

	/** How long a check waits for OpenFGA to come back (a sleeping host takes 10–30 s to wake) before denying. */
	private static final Duration CHECK_PATIENCE = Duration.ofSeconds(20);

	private interface Call<T> {
		T run() throws Exception;
	}

	/**
	 * Runs an OpenFGA call, retrying while the failure is temporary (OpenFGA unreachable, 5xx, timeouts) for up to
	 * {@link #CHECK_PATIENCE}. Anything else, or running out of time, is thrown to the caller.
	 */
	private <T> T retrying(Call<T> call) throws Exception {
		long deadline = System.nanoTime() + CHECK_PATIENCE.toNanos();
		long pause = 500;
		while (true) {
			try {
				return call.run();
			}
			catch (Exception ex) {
				if (!isTransient(ex) || System.nanoTime() + pause * 1_000_000 > deadline) {
					throw ex;
				}
				log.warn("OpenFGA unavailable ({}); retrying in {} ms", rootMessage(ex), pause);
				Thread.sleep(pause);
				pause = Math.min(pause * 2, 4000);
			}
		}
	}

	/** True when the failure means "OpenFGA is not answering right now" rather than "OpenFGA said no". */
	static boolean isTransient(Throwable ex) {
		for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
			String name = t.getClass().getSimpleName();
			if (name.equals("FgaApiInternalError") || name.equals("FgaApiRateLimitExceededError") || t instanceof java.io.IOException
					|| t instanceof java.util.concurrent.TimeoutException) {
				return true;
			}
			String m = t.getMessage();
			if (m != null && (m.contains("HTTP 502") || m.contains("HTTP 503") || m.contains("HTTP 504"))) {
				return true;
			}
		}
		return false;
	}

	/** Object ids of {@code type} the user has {@code relation} on, e.g. {@code employee:<uuid>}. */
	public List<String> listObjects(String user, String relation, String type) {
		if (!configured()) {
			return List.of();
		}
		try {
			ClientListObjectsRequest request = new ClientListObjectsRequest().user(user).relation(relation).type(type)
					.context(Map.of("current_time", Instant.now().toString()));
			List<String> objects = retrying(() -> client.listObjects(request).get().getObjects());
			return objects != null ? objects : List.of();
		}
		catch (Exception ex) {
			interruptIfNeeded(ex);
			throw new IllegalStateException("OpenFGA listObjects failed", ex);
		}
	}

	public void write(List<FgaTuple> tuples) {
		if (tuples.isEmpty()) {
			return;
		}
		// OpenFGA rejects a request that repeats a tuple; the outbox can hold the same one twice
		List<ClientTupleKey> keys = tuples.stream().distinct().map(AuthzService::toKey).toList();
		send(ClientWriteRequest.ofWrites(keys));
	}

	public void delete(List<FgaTuple> tuples) {
		if (tuples.isEmpty()) {
			return;
		}
		List<ClientTupleKeyWithoutCondition> keys = tuples.stream().distinct()
				.map(t -> new ClientTupleKeyWithoutCondition().user(t.user()).relation(t.relation())._object(t.object()))
				.toList();
		send(ClientWriteRequest.ofDeletes(keys));
	}

	private static ClientTupleKey toKey(FgaTuple t) {
		ClientTupleKey key = new ClientTupleKey().user(t.user()).relation(t.relation())._object(t.object());
		if (t.condition() != null) {
			key.condition(new ClientRelationshipCondition().name(t.condition()).context(t.context()));
		}
		return key;
	}

	private void send(ClientWriteRequest request) {
		try {
			ClientWriteOptions options = new ClientWriteOptions()
					.onDuplicate(WriteRequestWrites.OnDuplicateEnum.IGNORE)
					.onMissing(WriteRequestDeletes.OnMissingEnum.IGNORE);
			client.write(request, options).get();
			checkCache.invalidateAll();
		}
		catch (Exception ex) {
			interruptIfNeeded(ex);
			throw new IllegalStateException("OpenFGA write failed: " + rootMessage(ex), ex);
		}
	}

	private static String rootMessage(Throwable ex) {
		Throwable t = ex;
		while (t.getCause() != null && t.getCause() != t) {
			t = t.getCause();
		}
		String m = t.getMessage();
		return t.getClass().getSimpleName() + (m == null ? "" : ": " + (m.length() > 300 ? m.substring(0, 300) : m));
	}

	private static void interruptIfNeeded(Exception ex) {
		if (ex instanceof InterruptedException) {
			Thread.currentThread().interrupt();
		}
	}
}
