package com.ivoyant.hrms.iam.auth;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The public keys that check our access tokens. */
@RestController
public class JwksController {

	private final KeyService keys;

	public JwksController(KeyService keys) {
		this.keys = keys;
	}

	@GetMapping(value = "/.well-known/jwks.json", produces = "application/json")
	public ResponseEntity<Map<String, Object>> jwks() {
		return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePublic()).body(keys.publicSet().toJSONObject());
	}
}
