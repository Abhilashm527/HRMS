package com.ivoyant.hrms.iam.auth;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Authenticator-app codes (RFC 6238: SHA-1, 6 digits, 30 seconds, one step of drift) and the recovery codes. */
@Service
public class TotpService {

	private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
	private static final String CODE_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final long STEP = 30;

	private final JdbcClient jdbc;

	TotpService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	byte[] newSecret() {
		byte[] b = new byte[20];
		RANDOM.nextBytes(b);
		return b;
	}

	static String base32(byte[] data) {
		StringBuilder sb = new StringBuilder();
		int buffer = 0;
		int bits = 0;
		for (byte x : data) {
			buffer = (buffer << 8) | (x & 0xFF);
			bits += 8;
			while (bits >= 5) {
				sb.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
				bits -= 5;
			}
		}
		if (bits > 0) {
			sb.append(BASE32.charAt((buffer << (5 - bits)) & 31));
		}
		return sb.toString();
	}

	/** The key as groups of four for typing by hand. */
	static String grouped(String base32) {
		return base32.replaceAll("(.{4})", "$1 ").trim();
	}

	String otpauth(String email, byte[] secret) {
		String issuer = URLEncoder.encode("Ivoyant HRMS", StandardCharsets.UTF_8).replace("+", "%20");
		return "otpauth://totp/" + issuer + ":" + URLEncoder.encode(email, StandardCharsets.UTF_8) + "?secret=" + base32(secret) + "&issuer=" + issuer + "&digits=6&period=30";
	}

	String qrSvg(String text) {
		try {
			BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 2));
			StringBuilder path = new StringBuilder();
			for (int y = 0; y < m.getHeight(); y++) {
				for (int x = 0; x < m.getWidth(); x++) {
					if (m.get(x, y)) {
						path.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
					}
				}
			}
			return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + m.getWidth() + " " + m.getHeight() + "\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path d=\""
					+ path + "\" fill=\"#000\"/></svg>";
		}
		catch (WriterException ex) {
			throw new IllegalStateException(ex);
		}
	}

	static String code(byte[] secret, long step) {
		try {
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(secret, "HmacSHA1"));
			byte[] counter = new byte[8];
			for (int i = 7; i >= 0; i--) {
				counter[i] = (byte) (step & 0xFF);
				step >>= 8;
			}
			byte[] h = mac.doFinal(counter);
			int o = h[h.length - 1] & 0xF;
			int bin = ((h[o] & 0x7F) << 24) | ((h[o + 1] & 0xFF) << 16) | ((h[o + 2] & 0xFF) << 8) | (h[o + 3] & 0xFF);
			return String.format("%06d", bin % 1_000_000);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** The time step the code belongs to (now ± one step), or -1 when it does not match. */
	static long matchStep(byte[] secret, String code, long nowSeconds) {
		if (code == null || !code.matches("\\d{6}")) {
			return -1;
		}
		long current = nowSeconds / STEP;
		for (long s = current - 1; s <= current + 1; s++) {
			if (MessageDigestEqual(code(secret, s), code)) {
				return s;
			}
		}
		return -1;
	}

	private static boolean MessageDigestEqual(String a, String b) {
		return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}

	/** True once for a code that matches and was not used before (each code works only once). */
	boolean verify(UUID userId, byte[] secret, String code) {
		long step = matchStep(secret, code, System.currentTimeMillis() / 1000);
		if (step < 0) {
			return false;
		}
		int claimed = jdbc.sql("update auth_credential set totp_last_step = :s where user_id = :u and (totp_last_step is null or totp_last_step < :s)").param("s", step).param("u", userId).update();
		return claimed > 0;
	}

	/** Makes ten new recovery codes (old ones stop working) and returns them in clear, once. */
	List<String> newRecoveryCodes(UUID userId) {
		jdbc.sql("delete from auth_recovery_code where user_id = :u").param("u", userId).update();
		List<String> out = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			StringBuilder sb = new StringBuilder();
			for (int j = 0; j < 8; j++) {
				if (j == 4) {
					sb.append('-');
				}
				sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
			}
			out.add(sb.toString());
			jdbc.sql("insert into auth_recovery_code (user_id, code_hash) values (:u, :h)").param("u", userId).param("h", AuthCrypto.hash(userId + ":" + sb)).update();
		}
		return out;
	}

	/** Uses a recovery code if it is one of the user's unused ones. */
	boolean useRecovery(UUID userId, String code) {
		if (code == null || !code.trim().toLowerCase().matches("[a-z0-9]{4}-[a-z0-9]{4}")) {
			return false;
		}
		return jdbc.sql("update auth_recovery_code set used_at = now() where user_id = :u and code_hash = :h and used_at is null").param("u", userId)
				.param("h", AuthCrypto.hash(userId + ":" + code.trim().toLowerCase())).update() > 0;
	}

	int recoveryLeft(UUID userId) {
		return jdbc.sql("select count(*) from auth_recovery_code where user_id = :u and used_at is null").param("u", userId).query(Integer.class).single();
	}
}
