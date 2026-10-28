package com.ivoyant.hrms.iam.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** AES-GCM for secrets at rest (TOTP seeds, signing keys), HMAC for the short-lived sign-in state, SHA-256 for token hashes. */
@Component
public class AuthCrypto {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final SecretKeySpec aes;
	private final SecretKeySpec hmac;

	AuthCrypto(AuthProps props) {
		byte[] material = sha256(props.keyEnc().getBytes(StandardCharsets.UTF_8));
		this.aes = new SecretKeySpec(material, "AES");
		this.hmac = new SecretKeySpec(sha256(("hmac:" + props.keyEnc()).getBytes(StandardCharsets.UTF_8)), "HmacSHA256");
	}

	byte[] encrypt(byte[] plain) {
		try {
			byte[] iv = new byte[12];
			RANDOM.nextBytes(iv);
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.ENCRYPT_MODE, aes, new GCMParameterSpec(128, iv));
			byte[] ct = c.doFinal(plain);
			byte[] out = new byte[iv.length + ct.length];
			System.arraycopy(iv, 0, out, 0, iv.length);
			System.arraycopy(ct, 0, out, iv.length, ct.length);
			return out;
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	byte[] decrypt(byte[] sealed) {
		try {
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.DECRYPT_MODE, aes, new GCMParameterSpec(128, sealed, 0, 12));
			return c.doFinal(sealed, 12, sealed.length - 12);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Could not decrypt (is AUTH_KEY_ENC the key the data was written with?)", ex);
		}
	}

	String sign(String payload) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(hmac);
			return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	boolean verify(String payload, String signature) {
		return signature != null && MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
	}

	/** 32 random bytes as URL-safe text: the form of every link token and refresh token. */
	static String randomToken() {
		byte[] b = new byte[32];
		RANDOM.nextBytes(b);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
	}

	static byte[] sha256(byte[] in) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(in);
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	static String hash(String token) {
		return HexFormat.of().formatHex(sha256(token.getBytes(StandardCharsets.UTF_8)));
	}
}
