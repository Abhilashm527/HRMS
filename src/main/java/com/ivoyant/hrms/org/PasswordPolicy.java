package com.ivoyant.hrms.org;

public enum PasswordPolicy {
	STANDARD("Standard · 8+ characters"), STRONG("Strong · 12+ with symbols"), ENTRA("Managed by Entra ID");

	private final String label;

	PasswordPolicy(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}
}
