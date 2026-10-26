package com.ivoyant.hrms.iam.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.authz.FgaOutbox;
import com.ivoyant.hrms.common.authz.FgaTuples;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.iam.domain.AppUser;
import com.ivoyant.hrms.iam.domain.AreaOwner;
import com.ivoyant.hrms.iam.dto.AreaOwnerResponse;
import com.ivoyant.hrms.iam.dto.CreateAreaOwnerRequest;
import com.ivoyant.hrms.iam.repo.AppUserRepository;
import com.ivoyant.hrms.iam.repo.AreaOwnerRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AreaOwnerService {

	private static final String ENTITY = "AreaOwner";

	private final AreaOwnerRepository owners;
	private final AppUserRepository users;
	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final FgaOutbox fga;
	private final Audit audit;

	public AreaOwnerService(AreaOwnerRepository owners, AppUserRepository users, JdbcClient jdbc,
			CurrentUser currentUser, FgaOutbox fga, Audit audit) {
		this.owners = owners;
		this.users = users;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.fga = fga;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public List<AreaOwnerResponse> list() {
		List<AreaOwner> rows = owners.findByOrgId(currentUser.orgId());
		Map<UUID, AppUser> byId = users.findByIdIn(rows.stream().map(AreaOwner::getUserId).toList()).stream()
				.collect(Collectors.toMap(AppUser::getId, Function.identity()));
		return rows.stream().map(o -> toResponse(o, byId.get(o.getUserId()))).toList();
	}

	@Transactional
	public AreaOwnerResponse create(CreateAreaOwnerRequest request) {
		UUID orgId = currentUser.orgId();
		// a plain query rather than the config module: config already depends on iam, and a cycle is not allowed
		boolean known = jdbc.sql("select count(*) from config_area where key = :key").param("key", request.areaKey())
				.query(Long.class).single() > 0;
		if (!known) {
			throw new NotFoundException("Unknown area '" + request.areaKey() + "'");
		}
		AppUser user = users.findByIdAndOrgId(request.userId(), orgId)
				.orElseThrow(() -> new NotFoundException("User " + request.userId() + " not found"));
		if (owners.existsByOrgIdAndAreaKeyAndUserId(orgId, request.areaKey(), user.getId())) {
			throw new ConflictException("The user already owns this area");
		}
		AreaOwner owner = new AreaOwner();
		owner.setOrgId(orgId);
		owner.setAreaKey(request.areaKey());
		owner.setUserId(user.getId());
		owners.saveAndFlush(owner);
		fga.write(FgaTuples.configAreaOrg(orgId, request.areaKey()));
		fga.write(FgaTuples.configAreaOwner(request.areaKey(), user.getEmployeeId()));
		AreaOwnerResponse response = toResponse(owner, user);
		audit.created("iam", ENTITY, owner.getId(), response);
		return response;
	}

	@Transactional
	public void delete(UUID id) {
		AreaOwner owner = owners.findByIdAndOrgId(id, currentUser.orgId())
				.orElseThrow(() -> new NotFoundException("Area owner " + id + " not found"));
		AppUser user = users.findById(owner.getUserId()).orElseThrow();
		AreaOwnerResponse before = toResponse(owner, user);
		owners.delete(owner);
		owners.flush();
		fga.delete(FgaTuples.configAreaOwner(owner.getAreaKey(), user.getEmployeeId()));
		audit.deleted("iam", ENTITY, id, before);
	}

	private static AreaOwnerResponse toResponse(AreaOwner owner, AppUser user) {
		return new AreaOwnerResponse(owner.getId(), owner.getAreaKey(), owner.getUserId(), user.getEmployeeId());
	}
}
