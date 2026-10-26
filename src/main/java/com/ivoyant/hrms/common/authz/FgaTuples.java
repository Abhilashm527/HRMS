package com.ivoyant.hrms.common.authz;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builders for the tuples the HRMS model uses (see {@code fga/model.fga}). */
public final class FgaTuples {

	private FgaTuples() {
	}

	public static String user(UUID employeeId) {
		return "user:" + employeeId;
	}

	public static String org(UUID id) {
		return "org:" + id;
	}

	public static String employee(UUID id) {
		return "employee:" + id;
	}

	public static String department(UUID id) {
		return "department:" + id;
	}

	public static String location(UUID id) {
		return "location:" + id;
	}

	public static String configArea(String key) {
		return "config_area:" + key;
	}

	/** Every employee belongs to the org. */
	public static FgaTuple orgMember(UUID orgId, UUID employeeId) {
		return new FgaTuple(user(employeeId), "member", org(orgId));
	}

	/** {@code relation} is {@code hr_admin} or {@code super_admin}. */
	public static FgaTuple orgRole(UUID orgId, UUID employeeId, String relation) {
		return new FgaTuple(user(employeeId), relation, org(orgId));
	}

	public static FgaTuple departmentOrg(UUID orgId, UUID departmentId) {
		return new FgaTuple(org(orgId), "org", department(departmentId));
	}

	/** {@code parent} is the department above {@code child}; heads inherit downwards. */
	public static FgaTuple departmentParent(UUID childId, UUID parentId) {
		return new FgaTuple(department(parentId), "parent", department(childId));
	}

	public static FgaTuple departmentHead(UUID departmentId, UUID headEmployeeId) {
		return new FgaTuple(user(headEmployeeId), "head", department(departmentId));
	}

	public static FgaTuple locationOrg(UUID orgId, UUID locationId) {
		return new FgaTuple(org(orgId), "org", location(locationId));
	}

	/** The location's head: may see the location's people and manage the location. */
	public static FgaTuple locationHead(UUID locationId, UUID headEmployeeId) {
		return new FgaTuple(user(headEmployeeId), "head", location(locationId));
	}

	public static FgaTuple configAreaOrg(UUID orgId, String areaKey) {
		return new FgaTuple(org(orgId), "org", configArea(areaKey));
	}

	public static FgaTuple configAreaOwner(String areaKey, UUID ownerEmployeeId) {
		return new FgaTuple(user(ownerEmployeeId), "owner", configArea(areaKey));
	}

	public static String role(String roleKey) {
		return "role:" + roleKey;
	}

	/** The employee holds the role (any scope); lets role-based owners of a config area be resolved in OpenFGA. */
	public static FgaTuple roleMember(UUID employeeId, String roleKey) {
		return new FgaTuple(user(employeeId), "member", role(roleKey));
	}

	/** Everyone holding the role may configure the area. */
	public static FgaTuple areaOwnerRole(String areaKey, String roleKey) {
		return new FgaTuple(role(roleKey) + "#member", "owner", configArea(areaKey));
	}

	/** Tuples that always hold for an employee: org link and self. */
	public static List<FgaTuple> employeeBase(UUID orgId, UUID employeeId) {
		return List.of(
				new FgaTuple(org(orgId), "org", employee(employeeId)),
				new FgaTuple(user(employeeId), "is_self", employee(employeeId)),
				orgMember(orgId, employeeId));
	}

	public static List<FgaTuple> manager(UUID employeeId, UUID managerId) {
		return List.of(
				new FgaTuple(user(managerId), "manager", employee(employeeId)),
				new FgaTuple(employee(managerId), "manager_emp", employee(employeeId)));
	}

	/** A dotted-line manager may see the employee's basics (never salary or documents) and has no other authority. */
	public static FgaTuple dotted(UUID employeeId, UUID dottedManagerId) {
		return new FgaTuple(user(dottedManagerId), "dotted_manager", employee(employeeId));
	}

	public static List<FgaTuple> department(UUID employeeId, UUID departmentId) {
		return List.of(
				new FgaTuple(department(departmentId), "dept", employee(employeeId)),
				new FgaTuple(user(employeeId), "member", department(departmentId)));
	}

	public static List<FgaTuple> location(UUID employeeId, UUID locationId) {
		return List.of(
				new FgaTuple(location(locationId), "location", employee(employeeId)),
				new FgaTuple(user(employeeId), "member", location(locationId)));
	}

	/** The {@code to} user may act as delegate for {@code reportId} between the two instants. */
	public static FgaTuple delegate(UUID toEmployeeId, UUID reportId, Instant startsAt, Instant endsAt) {
		return new FgaTuple(user(toEmployeeId), "delegate", employee(reportId))
				.withCondition("active_window", Map.of("starts_at", startsAt.toString(), "ends_at", endsAt.toString()));
	}

	/** All tuples describing one employee's position (used for create and for full sync). */
	public static List<FgaTuple> employeeAll(UUID orgId, UUID employeeId, UUID managerId, UUID departmentId,
			UUID locationId) {
		List<FgaTuple> all = new ArrayList<>(employeeBase(orgId, employeeId));
		if (managerId != null) {
			all.addAll(manager(employeeId, managerId));
		}
		if (departmentId != null) {
			all.addAll(department(employeeId, departmentId));
		}
		if (locationId != null) {
			all.addAll(location(employeeId, locationId));
		}
		return all;
	}
}
