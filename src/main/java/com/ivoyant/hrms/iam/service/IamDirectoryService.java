package com.ivoyant.hrms.iam.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.iam.IamDirectory;
import com.ivoyant.hrms.iam.PermLevel;
import com.ivoyant.hrms.iam.RoleRef;
import com.ivoyant.hrms.iam.domain.AppUser;
import com.ivoyant.hrms.iam.domain.Delegation;
import com.ivoyant.hrms.iam.domain.Permission;
import com.ivoyant.hrms.iam.domain.Role;
import com.ivoyant.hrms.iam.domain.RolePermission;
import com.ivoyant.hrms.iam.domain.UserRole;
import com.ivoyant.hrms.iam.repo.AppUserRepository;
import com.ivoyant.hrms.iam.repo.DelegationRepository;
import com.ivoyant.hrms.iam.repo.PermissionRepository;
import com.ivoyant.hrms.iam.repo.RolePermissionRepository;
import com.ivoyant.hrms.iam.repo.RoleRepository;
import com.ivoyant.hrms.iam.repo.UserRoleRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class IamDirectoryService implements IamDirectory {

	private final AppUserRepository users;
	private final UserRoleRepository userRoles;
	private final RoleRepository roles;
	private final DelegationRepository delegations;
	private final PermissionRepository permissions;
	private final RolePermissionRepository rolePermissions;
	private final CurrentUser currentUser;

	IamDirectoryService(AppUserRepository users, UserRoleRepository userRoles, RoleRepository roles,
			DelegationRepository delegations, PermissionRepository permissions,
			RolePermissionRepository rolePermissions, CurrentUser currentUser) {
		this.users = users;
		this.userRoles = userRoles;
		this.roles = roles;
		this.delegations = delegations;
		this.permissions = permissions;
		this.rolePermissions = rolePermissions;
		this.currentUser = currentUser;
	}

	@Override
	@Transactional(readOnly = true)
	public Set<UUID> roleIdsOf(UUID employeeId) {
		Set<UUID> ids = new HashSet<>();
		users.findByEmployeeId(employeeId).ifPresent(
				u -> userRoles.findByUserId(u.getId()).stream().map(UserRole::getRoleId).forEach(ids::add));
		roles.findByOrgIdAndRoleKey(currentUser.orgId(), "employee").ifPresent(r -> ids.add(r.getId()));
		return ids;
	}

	@Override
	@Transactional(readOnly = true)
	public List<RoleRef> roles() {
		return roles.findByOrgId(currentUser.orgId(), Sort.by("name")).stream().map(IamDirectoryService::ref).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<RoleRef> roleByKey(String key) {
		return roles.findByOrgIdAndRoleKey(currentUser.orgId(), key).map(IamDirectoryService::ref);
	}

	@Override
	@Transactional(readOnly = true)
	public Set<RoleRef> effectiveRoles(UUID employeeId, String module) {
		Set<UUID> ids = new HashSet<>(roleIdsOf(employeeId));
		Optional<AppUser> me = users.findByEmployeeId(employeeId);
		if (me.isPresent() && module != null) {
			Instant now = Instant.now();
			for (Delegation d : delegations.findByToUserIdAndModuleAndStartsAtLessThanEqualAndEndsAtGreaterThan(
					me.get().getId(), module, now, now)) {
				userRoles.findByUserId(d.getFromUserId()).stream().map(UserRole::getRoleId).forEach(ids::add);
			}
		}
		return roles.findByIdIn(ids).stream().map(IamDirectoryService::ref)
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, PermLevel> permissionLevels(String module) {
		Map<UUID, Permission> byId = permissions.findByModule(module).stream()
				.collect(Collectors.toMap(Permission::getId, Function.identity()));
		Map<UUID, PermLevel> out = new HashMap<>();
		if (byId.isEmpty()) {
			return out;
		}
		for (RolePermission rp : rolePermissions.findByPermissionIdIn(byId.keySet())) {
			PermLevel level = PermLevel.ofAction(byId.get(rp.getPermissionId()).getAction());
			out.merge(rp.getRoleId(), level, PermLevel::max);
		}
		return out;
	}

	private static RoleRef ref(Role r) {
		return new RoleRef(r.getId(), r.getRoleKey(), r.getName());
	}
}
