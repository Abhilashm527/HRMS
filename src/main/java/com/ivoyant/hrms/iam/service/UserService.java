package com.ivoyant.hrms.iam.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.authz.FgaOutbox;
import com.ivoyant.hrms.common.authz.FgaTuples;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.iam.domain.AppUser;
import com.ivoyant.hrms.iam.domain.Role;
import com.ivoyant.hrms.iam.domain.Scope;
import com.ivoyant.hrms.iam.domain.UserRole;
import com.ivoyant.hrms.iam.dto.AssignRoleRequest;
import com.ivoyant.hrms.iam.dto.UserResponse;
import com.ivoyant.hrms.iam.dto.UserRoleResponse;
import com.ivoyant.hrms.iam.mapper.UserMapper;
import com.ivoyant.hrms.org.OrgDirectory;
import com.ivoyant.hrms.iam.repo.AppUserRepository;
import com.ivoyant.hrms.iam.repo.RoleRepository;
import com.ivoyant.hrms.iam.repo.UserRoleRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

	/** Organisation-wide roles that are mirrored into OpenFGA as {@code org} relations. */
	static final Set<String> FGA_ORG_ROLES = Set.of("super_admin", "hr_admin");

	private final AppUserRepository users;
	private final UserRoleRepository userRoles;
	private final RoleRepository roles;
	private final UserMapper mapper;
	private final CurrentUser currentUser;
	private final FgaOutbox fga;
	private final Audit audit;
	private final OrgDirectory org;

	public UserService(AppUserRepository users, UserRoleRepository userRoles, RoleRepository roles, UserMapper mapper,
			CurrentUser currentUser, FgaOutbox fga, Audit audit, OrgDirectory org) {
		this.users = users;
		this.userRoles = userRoles;
		this.roles = roles;
		this.mapper = mapper;
		this.currentUser = currentUser;
		this.fga = fga;
		this.audit = audit;
		this.org = org;
	}

	@Transactional(readOnly = true)
	public List<UserResponse> list() {
		return users.findByOrgId(currentUser.orgId()).stream().map(mapper::toResponse).toList();
	}

	@Transactional(readOnly = true)
	public UserResponse get(UUID id) {
		return mapper.toResponse(find(id));
	}

	@Transactional(readOnly = true)
	public List<UserRoleResponse> rolesOf(UUID userId) {
		find(userId);
		List<UserRole> rows = userRoles.findByUserId(userId);
		Map<UUID, Role> byId = roles.findByIdIn(rows.stream().map(UserRole::getRoleId).toList()).stream()
				.collect(Collectors.toMap(Role::getId, Function.identity()));
		return rows.stream().map(ur -> toResponse(ur, byId.get(ur.getRoleId()))).toList();
	}

	@Transactional
	public UserRoleResponse assign(UUID userId, AssignRoleRequest request) {
		AppUser user = find(userId);
		Role role = roles.findByIdAndOrgId(request.roleId(), currentUser.orgId())
				.orElseThrow(() -> new NotFoundException("Role " + request.roleId() + " not found"));
		Scope scope = request.scope() != null ? request.scope() : Scope.ORGANIZATION;
		boolean needsRef = scope == Scope.DEPARTMENT || scope == Scope.LOCATION;
		if (needsRef && request.scopeRef() == null) {
			throw new ConflictException("scopeRef is required for scope " + scope);
		}
		UUID ref = needsRef ? request.scopeRef() : null;
		if (scope == Scope.LOCATION && !org.activeLocationNames(List.of(ref)).containsKey(ref)) {
			throw new ConflictException("scopeRef must be an active location");
		}
		if (scope == Scope.DEPARTMENT && !org.departmentNames(List.of(ref)).containsKey(ref)) {
			throw new ConflictException("scopeRef must be an existing department");
		}
		boolean duplicate = userRoles.findByUserId(userId).stream().anyMatch(
				ur -> ur.getRoleId().equals(role.getId()) && ur.getScope() == scope && Objects.equals(ur.getScopeRef(), ref));
		if (duplicate) {
			throw new ConflictException("The user already has this role with the same scope");
		}
		UserRole ur = new UserRole();
		ur.setUserId(userId);
		ur.setRoleId(role.getId());
		ur.setScope(scope);
		ur.setScopeRef(ref);
		userRoles.saveAndFlush(ur);
		fga.write(FgaTuples.roleMember(user.getEmployeeId(), role.getRoleKey()));
		if (isOrgWideAdmin(role, scope)) {
			fga.write(FgaTuples.orgRole(user.getOrgId(), user.getEmployeeId(), role.getRoleKey()));
		}
		UserRoleResponse response = toResponse(ur, role);
		audit.created("iam", "UserRole", ur.getId(), response);
		return response;
	}

	@Transactional
	public void remove(UUID userId, UUID userRoleId) {
		AppUser user = find(userId);
		UserRole ur = userRoles.findByIdAndUserId(userRoleId, userId)
				.orElseThrow(() -> new NotFoundException("Role assignment " + userRoleId + " not found"));
		Role role = roles.findById(ur.getRoleId()).orElseThrow();
		UserRoleResponse before = toResponse(ur, role);
		userRoles.delete(ur);
		userRoles.flush();
		if (isOrgWideAdmin(role, ur.getScope())) {
			fga.delete(FgaTuples.orgRole(user.getOrgId(), user.getEmployeeId(), role.getRoleKey()));
		}
		// membership of the role ends only when no other scope of the same role is left
		if (userRoles.findByUserId(userId).stream().noneMatch(other -> other.getRoleId().equals(role.getId()))) {
			fga.delete(FgaTuples.roleMember(user.getEmployeeId(), role.getRoleKey()));
		}
		audit.deleted("iam", "UserRole", userRoleId, before);
	}

	private static boolean isOrgWideAdmin(Role role, Scope scope) {
		return scope == Scope.ORGANIZATION && FGA_ORG_ROLES.contains(role.getRoleKey());
	}

	private static UserRoleResponse toResponse(UserRole ur, Role role) {
		return new UserRoleResponse(ur.getId(), ur.getRoleId(), role.getRoleKey(), role.getName(), ur.getScope(),
				ur.getScopeRef());
	}

	private AppUser find(UUID id) {
		return users.findByIdAndOrgId(id, currentUser.orgId())
				.orElseThrow(() -> new NotFoundException("User " + id + " not found"));
	}
}
