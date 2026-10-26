package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.org.OrgDirectory;
import com.ivoyant.hrms.org.domain.Department;
import com.ivoyant.hrms.org.domain.Designation;
import com.ivoyant.hrms.org.domain.Location;
import com.ivoyant.hrms.org.repo.DepartmentRepository;
import com.ivoyant.hrms.org.repo.DesignationRepository;
import com.ivoyant.hrms.org.repo.LocationRepository;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrgDirectoryService implements OrgDirectory {

	private final DepartmentRepository departments;
	private final DesignationRepository designations;
	private final LocationRepository locations;
	private final CurrentUser currentUser;

	OrgDirectoryService(DepartmentRepository departments, DesignationRepository designations,
			LocationRepository locations, CurrentUser currentUser) {
		this.departments = departments;
		this.designations = designations;
		this.locations = locations;
		this.currentUser = currentUser;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, String> departmentNames(Collection<UUID> ids) {
		return ids.isEmpty() ? Map.of() : departments.findByOrgIdAndIdIn(currentUser.orgId(), ids).stream()
				.collect(Collectors.toMap(Department::getId, Department::getName));
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, String> activeDepartmentNames(Collection<UUID> ids) {
		return ids.isEmpty() ? Map.of() : departments.findByOrgIdAndIdIn(currentUser.orgId(), ids).stream()
				.filter(Department::isActive).collect(Collectors.toMap(Department::getId, Department::getName));
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, String> designationNames(Collection<UUID> ids) {
		return ids.isEmpty() ? Map.of() : designations.findByOrgIdAndIdIn(currentUser.orgId(), ids).stream()
				.collect(Collectors.toMap(Designation::getId, Designation::getTitle));
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, String> activeDesignationNames(Collection<UUID> ids) {
		return ids.isEmpty() ? Map.of() : designations.findByOrgIdAndIdIn(currentUser.orgId(), ids).stream()
				.filter(Designation::isActive).collect(Collectors.toMap(Designation::getId, Designation::getTitle));
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, String> activeLocationNames(Collection<UUID> ids) {
		return ids.isEmpty() ? Map.of() : locations.findByOrgIdAndIdIn(currentUser.orgId(), ids).stream()
				.filter(Location::isActive).collect(Collectors.toMap(Location::getId, Location::getName));
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.Optional<UUID> defaultLocationOfDepartment(UUID departmentId) {
		return departments.findByIdAndOrgId(departmentId, currentUser.orgId()).map(Department::getDefaultLocationId);
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, String> locationNames(Collection<UUID> ids) {
		return ids.isEmpty() ? Map.of() : locations.findByOrgIdAndIdIn(currentUser.orgId(), ids).stream()
				.collect(Collectors.toMap(Location::getId, Location::getName));
	}
}
