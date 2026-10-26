package com.ivoyant.hrms.org.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Band amounts as text: INR in lakhs and crores ("₹15–30 L"), other currencies in thousands ("$120k–150k"). */
final class BandFormat {

	private static final BigDecimal LAKH = BigDecimal.valueOf(100_000);
	private static final BigDecimal CRORE = BigDecimal.valueOf(10_000_000);
	private static final BigDecimal THOUSAND = BigDecimal.valueOf(1_000);
	private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

	/** An amount split into its number and unit suffix, e.g. ("15", " L"). */
	private record Part(String number, String suffix) {
	}

	private BandFormat() {
	}

	/** "₹15–30 L", "₹90 L–₹3 Cr" or null without a band. */
	static String range(BigDecimal min, BigDecimal max, String currency) {
		if (min == null || max == null) {
			return null;
		}
		Part lo = part(min, currency);
		Part hi = part(max, currency);
		if (lo.suffix().equals(hi.suffix())) {
			return symbol(currency) + lo.number() + "–" + hi.number() + hi.suffix();
		}
		return money(min, currency) + "–" + money(max, currency);
	}

	/** A single amount: "₹32 L", "₹1.2 Cr", "$120k". */
	static String money(BigDecimal amount, String currency) {
		Part p = part(amount, currency);
		return symbol(currency) + p.number() + p.suffix();
	}

	static String symbol(String currency) {
		return switch (currency == null ? "INR" : currency.toUpperCase(Locale.ROOT)) {
			case "INR" -> "₹";
			case "USD" -> "$";
			case "EUR" -> "€";
			case "GBP" -> "£";
			default -> currency + " ";
		};
	}

	private static Part part(BigDecimal amount, String currency) {
		BigDecimal a = amount.abs();
		if (currency == null || "INR".equalsIgnoreCase(currency)) {
			if (a.compareTo(CRORE) >= 0) {
				return new Part(trim(a.divide(CRORE, 2, RoundingMode.HALF_UP)), " Cr");
			}
			if (a.compareTo(LAKH) >= 0 || a.signum() == 0) {
				return new Part(trim(a.divide(LAKH, 2, RoundingMode.HALF_UP)), " L");
			}
			return new Part(trim(a.setScale(0, RoundingMode.HALF_UP)), "");
		}
		if (a.compareTo(MILLION) >= 0) {
			return new Part(trim(a.divide(MILLION, 2, RoundingMode.HALF_UP)), "M");
		}
		if (a.compareTo(THOUSAND) >= 0) {
			return new Part(trim(a.divide(THOUSAND, 1, RoundingMode.HALF_UP)), "k");
		}
		return new Part(trim(a.setScale(0, RoundingMode.HALF_UP)), "");
	}

	private static String trim(BigDecimal v) {
		return v.stripTrailingZeros().toPlainString();
	}
}
