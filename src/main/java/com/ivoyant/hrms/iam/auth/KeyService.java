package com.ivoyant.hrms.iam.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The RS256 keys that sign access tokens. A new key is made on first start and every 90 days; the old one stays valid for a day so
 * tokens already out keep working. The private half is stored encrypted.
 */
@Component
@Order(1)
public class KeyService implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(KeyService.class);
	private static final long ROTATE_DAYS = 90;
	private static final long CACHE_MS = 60_000;

	private final JdbcClient jdbc;
	private final AuthCrypto crypto;
	private volatile List<RSAKey> cache = List.of();
	private volatile long loadedAt;

	KeyService(JdbcClient jdbc, AuthCrypto crypto) {
		this.jdbc = jdbc;
		this.crypto = crypto;
	}

	@Override
	public void run(ApplicationArguments args) {
		rotateIfDue();
	}

	/** Creates the first key, or a new one when the newest is older than 90 days; retires superseded keys a day later. */
	public synchronized void rotateIfDue() {
		Boolean due = jdbc.sql("select coalesce(max(created_at) < now() - make_interval(days => :d), true) from auth_signing_key where retire_at is null").param("d", (int) ROTATE_DAYS)
				.query(Boolean.class).single();
		if (Boolean.TRUE.equals(due)) {
			try {
				KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
				g.initialize(2048);
				KeyPair kp = g.generateKeyPair();
				String kid = UUID.randomUUID().toString();
				RSAKey pub = new RSAKey.Builder((RSAPublicKey) kp.getPublic()).keyID(kid).algorithm(com.nimbusds.jose.JWSAlgorithm.RS256).keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE).build();
				jdbc.sql("update auth_signing_key set retire_at = now() + interval '1 day' where retire_at is null").update();
				jdbc.sql("insert into auth_signing_key (kid, private_enc, public_jwk) values (:k, :p, cast(:j as jsonb))").param("k", kid)
						.param("p", crypto.encrypt(kp.getPrivate().getEncoded())).param("j", pub.toJSONString()).update();
				log.info("New token signing key {}", kid);
			}
			catch (java.security.GeneralSecurityException ex) {
				throw new IllegalStateException(ex);
			}
		}
		jdbc.sql("delete from auth_signing_key where retire_at is not null and retire_at < now()").update();
		loadedAt = 0;
	}

	private List<RSAKey> keys() {
		if (System.currentTimeMillis() - loadedAt < CACHE_MS && !cache.isEmpty()) {
			return cache;
		}
		List<RSAKey> out = new ArrayList<>();
		jdbc.sql("select kid, private_enc, public_jwk::text, retire_at from auth_signing_key order by created_at desc").query((rs, i) -> {
			try {
				RSAKey pub = (RSAKey) JWK.parse(rs.getString(3));
				boolean retired = rs.getObject(4) != null;
				if (retired) {
					out.add(pub);
				}
				else {
					PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(crypto.decrypt(rs.getBytes(2)));
					RSAPrivateKey priv = (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(spec);
					out.add(new RSAKey.Builder(pub).privateKey(priv).build());
				}
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
			return 0;
		}).list();
		cache = out;
		loadedAt = System.currentTimeMillis();
		return out;
	}

	/** The key new tokens are signed with. */
	RSAKey signingKey() {
		return keys().stream().filter(RSAKey::isPrivate).findFirst().orElseThrow(() -> new IllegalStateException("No signing key"));
	}

	/** Public keys of every key still valid (the JWKS document). */
	public JWKSet publicSet() {
		try {
			List<JWK> pubs = new ArrayList<>();
			for (RSAKey k : keys()) {
				pubs.add(k.toPublicJWK());
			}
			return new JWKSet(pubs);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}
}
