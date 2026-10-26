package com.ivoyant.hrms.org;

public enum AttendanceMethod {
	BIOMETRIC("Biometric"), WEB("Web check-in"), MOBILE_GPS("Mobile GPS"), QR("QR code");

	private final String label;

	AttendanceMethod(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}
}
