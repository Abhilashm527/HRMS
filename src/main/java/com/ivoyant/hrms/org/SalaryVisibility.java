package com.ivoyant.hrms.org;

public enum SalaryVisibility {
	ADMINS_ONLY("Admins only"), HR_FINANCE("HR & finance"), MANAGERS_HR("Managers & HR"), EVERYONE("Everyone");

	private final String label;

	SalaryVisibility(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}
}
