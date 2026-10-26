package com.ivoyant.hrms.iam.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.org.OrgSettings;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.iam.domain.Permission;
import com.ivoyant.hrms.iam.domain.Role;
import com.ivoyant.hrms.iam.domain.RolePermission;
import com.ivoyant.hrms.iam.dto.CreateRoleRequest;
import com.ivoyant.hrms.iam.dto.RolePermissionRequest;
import com.ivoyant.hrms.iam.dto.RolePermissionResponse;
import com.ivoyant.hrms.iam.dto.RoleResponse;
import com.ivoyant.hrms.iam.dto.UpdateRoleRequest;
import com.ivoyant.hrms.iam.mapper.RoleMapper;
import com.ivoyant.hrms.iam.repo.PermissionRepository;
import com.ivoyant.hrms.iam.repo.RolePermissionRepository;
import com.ivoyant.hrms.iam.repo.RoleRepository;
import com.ivoyant.hrms.iam.repo.UserRoleRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoleService {

	private static final String ENTITY = "Role";

	private final RoleRepository roles;
	private final RolePermissionRepository rolePermissions;
	private final PermissionRepository permissions;
	private final UserRoleRepository userRoles;
	private final RoleMapper mapper;
	private final CurrentUser currentUser;
	private final Audit audit;
	private final OrgSettings orgSettings;

	public RoleService(RoleRepository roles, RolePermissionRepository rolePermissions,
			PermissionRepository permissions, UserRoleRepository userRoles, RoleMapper mapper,
			CurrentUser currentUser, Audit audit, OrgSettings orgSettings) {
		this.orgSettings = orgSettings;
		this.roles = roles;
		this.rolePermissions = rolePermissions;
		this.permissions = permissions;
		this.userRoles = userRoles;
		this.mapper = mapper;
		this.currentUser = currentUser;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public List<RoleResponse> list() {
		return roles.findByOrgId(currentUser.orgId(), Sort.by("name")).stream().map(mapper::toResponse).toList();
	}

	@Transactional(readOnly = true)
	public RoleResponse get(UUID id) {
		return mapper.toResponse(find(id));
	}

	@Transactional
	public RoleResponse create(CreateRoleRequest request) {
		if (!orgSettings.customRoles()) {
			throw new ConflictException("Custom roles are off in Settings → Organization");
		}
		UUID orgId = currentUser.orgId();
		if (roles.existsByOrgIdAndRoleKey(orgId, request.key())) {
			throw new ConflictException("A role with key '" + request.key() + "' already exists");
		}
		Role role = mapper.toEntity(request);
		role.setOrgId(orgId);
		roles.saveAndFlush(role);
		RoleResponse response = mapper.toResponse(role);
		audit.created("iam", ENTITY, role.getId(), response);
		return response;
	}

	@Transactional
	public RoleResponse update(UUID id, UpdateRoleRequest request) {
		Role role = find(id);
		RoleResponse before = mapper.toResponse(role);
		mapper.update(role, request);
		roles.saveAndFlush(role);
		RoleResponse after = mapper.toResponse(role);
		audit.updated("iam", ENTITY, id, before, after);
		return after;
	}

	@Transactional
	public void delete(UUID id) {
		Role role = find(id);
		if (role.isSystem()) {
			throw new ConflictException("System roles cannot be deleted");
		}
		if (userRoles.existsByRoleId(id)) {
			throw new ConflictException("The role is still assigned to users");
		}
		RoleResponse before = mapper.toResponse(role);
		rolePermissions.deleteByRoleId(id);
		roles.delete(role);
		roles.flush();
		audit.deleted("iam", ENTITY, id, before);
	}

	@Transactional(readOnly = true)
	public List<RolePermissionResponse> permissionsOf(UUID roleId) {
		find(roleId);
		List<RolePermission> rows = rolePermissions.findByRoleId(roleId);
		Map<UUID, Permission> byId = permissions.findByIdIn(rows.stream().map(RolePermission::getPermissionId).toList())
				.stream().collect(Collectors.toMap(Permission::getId, Function.identity()));
		return rows.stream()
				.map(rp -> new RolePermissionResponse(rp.getPermissionId(), byId.get(rp.getPermissionId()).getModule(),
						byId.get(rp.getPermissionId()).getAction(), rp.getScope()))
				.toList();
	}

	/** Replaces the role's permission set; unknown module/action pairs are created on the fly. */
	@Transactional
	public List<RolePermissionResponse> replacePermissions(UUID roleId, List<RolePermissionRequest> requested) {
		find(roleId);
		List<RolePermissionResponse> before = permissionsOf(roleId);
		rolePermissions.deleteByRoleId(roleId);
		rolePermissions.flush();
		LinkedHashSet<RolePermission> toSave = new LinkedHashSet<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (RolePermissionRequest r : requested) {
			Permission permission = permissions.findByModuleAndAction(r.module(), r.action()).orElseGet(() -> {
				Permission p = new Permission();
				p.setModule(r.module());
				p.setAction(r.action());
				return permissions.saveAndFlush(p);
			});
			if (seen.add(permission.getId() + "|" + r.scope())) {
				toSave.add(new RolePermission(roleId, permission.getId(), r.scope()));
			}
		}
		rolePermissions.saveAll(toSave);
		rolePermissions.flush();
		List<RolePermissionResponse> after = permissionsOf(roleId);
		audit.updated("iam", "RolePermissions", roleId, before, after);
		return after;
	}

	private Role find(UUID id) {
		return roles.findByIdAndOrgId(id, currentUser.orgId())
				.orElseThrow(() -> new NotFoundException("Role " + id + " not found"));
	}
}
