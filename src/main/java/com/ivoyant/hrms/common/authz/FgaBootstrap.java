package com.ivoyant.hrms.common.authz;

import com.fasterxml.jackson.databind.DeserializationFeature;
import dev.openfga.language.DslToJsonTransformer;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.model.CreateStoreRequest;
import dev.openfga.sdk.api.model.Store;
import dev.openfga.sdk.api.model.WriteAuthorizationModelRequest;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * Local profile only: if {@code FGA_STORE_ID} is empty, finds or creates the {@code hrms} store in OpenFGA and
 * writes {@code fga/model.fga} to it when the store has no model yet. Logs the ids to copy into {@code .env}.
 * Never fails startup: if OpenFGA is not reachable the app runs and denies every authorization check.
 */
@Component
@Profile({"local", "fga-setup"})
@Order(1)
class FgaBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(FgaBootstrap.class);
	static final String STORE_NAME = "hrms";

	private final OpenFgaClient client;
	private final AuthzService authz;
	private final FgaProperties props;

	FgaBootstrap(OpenFgaClient client, AuthzService authz, FgaProperties props) {
		this.client = client;
		this.authz = authz;
		this.props = props;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (props.configured()) {
			log.info("OpenFGA store {} configured via FGA_STORE_ID", props.storeId());
			return;
		}
		try {
			String storeId = findStore();
			if (storeId == null) {
				storeId = client.createStore(new CreateStoreRequest().name(STORE_NAME)).get().getId();
				log.info("Created OpenFGA store '{}' ({})", STORE_NAME, storeId);
				authz.markFreshStore();
			}
			authz.useStore(storeId, null);

			String json = modelJson();
			String modelId = latestModelId();
			if (modelId == null || !latestModelMatches(json)) {
				modelId = writeModel(json);
				log.info("Wrote OpenFGA authorization model {}", modelId);
				authz.markFreshStore();
			}
			else {
				log.info("OpenFGA authorization model {} is up to date", modelId);
			}
			authz.useStore(storeId, modelId);
			log.info("OpenFGA ready. To pin it, set in .env:  FGA_STORE_ID={}  FGA_MODEL_ID={}", storeId, modelId);
		}
		catch (Exception ex) {
			if (ex instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			log.warn("OpenFGA bootstrap failed: {}. Is OpenFGA running (`docker compose up -d`)? Until it is set up "
					+ "every authorization check is denied.", rootMessage(ex));
		}
	}

	private String findStore() throws Exception {
		for (Store store : client.listStores().get().getStores()) {
			if (STORE_NAME.equals(store.getName())) {
				return store.getId();
			}
		}
		return null;
	}

	private String latestModelId() {
		try {
			var model = client.readLatestAuthorizationModel().get().getAuthorizationModel();
			return model != null ? model.getId() : null;
		}
		catch (Exception ex) {
			return null; // a brand-new store has no model yet
		}
	}

	private String modelJson() throws Exception {
		String dsl = StreamUtils.copyToString(new ClassPathResource("fga/model.fga").getInputStream(),
				StandardCharsets.UTF_8);
		return new DslToJsonTransformer().transform(dsl);
	}

	/** Does the store's latest model already have exactly the types and conditions of {@code model.fga}? */
	private boolean latestModelMatches(String json) {
		try {
			var mapper = MAPPER;
			var wanted = mapper.readTree(json);
			var current = mapper.valueToTree(client.readLatestAuthorizationModel().get().getAuthorizationModel());
			return Objects.equals(normalised(wanted), normalised(current));
		}
		catch (Exception ex) {
			return false;
		}
	}

	private static com.fasterxml.jackson.databind.JsonNode normalised(com.fasterxml.jackson.databind.JsonNode model) {
		var out = MAPPER.createObjectNode();
		var types = MAPPER.createArrayNode();
		var list = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
		model.path("type_definitions").forEach(list::add);
		list.sort(java.util.Comparator.comparing(n -> n.path("type").asText()));
		list.forEach(n -> types.add(prune(n)));
		out.set("type_definitions", types);
		out.set("conditions", prune(model.path("conditions")));
		return out;
	}

	/** Drops nulls, empty strings and empty containers, so "absent", null and "" compare equal. */
	private static com.fasterxml.jackson.databind.JsonNode prune(com.fasterxml.jackson.databind.JsonNode node) {
		if (node.isObject()) {
			var out = MAPPER.createObjectNode();
			node.properties().forEach(e -> {
				var child = prune(e.getValue());
				if (child != null) {
					out.set(e.getKey(), child);
				}
			});
			return out.isEmpty() ? null : out;
		}
		if (node.isArray()) {
			var out = MAPPER.createArrayNode();
			node.forEach(c -> {
				var child = prune(c);
				if (child != null) {
					out.add(child);
				}
			});
			return out.isEmpty() ? null : out;
		}
		return node.isNull() || node.isMissingNode() || (node.isTextual() && node.asText().isEmpty()) ? null : node;
	}

	private String writeModel(String json) throws Exception {
		WriteAuthorizationModelRequest request = MAPPER.readValue(json, WriteAuthorizationModelRequest.class);
		return client.writeAuthorizationModel(request).get().getAuthorizationModelId();
	}

	private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper()
			.findAndRegisterModules()
			.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	private static String rootMessage(Throwable ex) {
		Throwable t = ex;
		while (t.getCause() != null) {
			t = t.getCause();
		}
		return t.getClass().getSimpleName() + ": " + t.getMessage();
	}
}
