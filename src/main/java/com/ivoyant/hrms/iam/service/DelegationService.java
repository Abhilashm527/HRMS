package com.ivoyant.hrms.iam.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.authz.AuthzService;
import com.ivoyant.hrms.common.event.DelegationChangedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.iam.domain.AppUser;
import com.ivoyant.hrms.iam.domain.Delegation;
import com.ivoyant.hrms.iam.dto.CreateDelegationRequest;
import com.ivoyant.hrms.iam.dto.DelegationResponse;
import com.ivoyant.hrms.iam.mapper.DelegationMapper;
import com.ivoyant.hrms.iam.repo.AppUserRepository;
import com.ivoyant.hrms.iam.repo.DelegationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DelegationService {

	private static final String ENTITY = "Delegation";

	private final DelegationRepository delegations;
	private final AppUserRepository users;
	private final DelegationMapper mapper;
	private final CurrentUser currentUser;
	private final AuthzService authz;
	private final ApplicationEventPublisher events;
	private final Audit audit;

	public DelegationService(DelegationRepository delegations, AppUserRepository users, DelegationMapper mapper,
			CurrentUser currentUser, AuthzService authz, ApplicationEventPublisher events, Audit audit) {
		this.delegations = delegations;
		this.users = users;
		this.mapper = mapper;
		this.currentUser = currentUser;
		this.authz = authz;
		this.events = events;
		this.audit = audit;
	}

	/** HR admins see every delegation; everyone else sees the ones they gave or received. */
	@Transactional(readOnly = true)
	public List<DelegationResponse> list() {
		UUID orgId = currentUser.orgId();
		List<Delegation> rows;
		if (authz.isHrAdmin()) {
			rows = delegations.findByOrgIdOrderByStartsAtDesc(orgId);
		}
		else {
			UUID me = callerUser().getId();
			rows = delegations.findByOrgIdAndFromUserIdOrOrgIdAndToUserIdOrderByStartsAtDesc(orgId, me, orgId, me);
		}
		return rows.stream().map(mapper::toResponse).toList();
	}

	@Transactional
	public DelegationResponse create(CreateDelegationRequest request) {
		UUID orgId = currentUser.orgId();
		AppUser caller = callerUser();
		UUID fromId = request.fromUserId() != null ? request.fromUserId() : caller.getId();
		if (!fromId.equals(caller.getId()) && !authz.isHrAdmin()) {
			throw new ForbiddenException("Only HR admins can delegate on someone else's behalf");
		}
		if (fromId.equals(request.toUserId())) {
			throw new ConflictException("Cannot delegate to yourself");
		}
		if (!request.endsAt().isAfter(request.startsAt())) {
			throw new ConflictException("endsAt must be after startsAt");
		}
		AppUser from = users.findByIdAndOrgId(fromId, orgId)
				.orElseThrow(() -> new NotFoundException("User " + fromId + " not found"));
		AppUser to = users.findByIdAndOrgId(request.toUserId(), orgId)
				.orElseThrow(() -> new NotFoundException("User " + request.toUserId() + " not found"));
		Delegation delegation = mapper.toEntity(request);
		delegation.setOrgId(orgId);
		delegation.setFromUserId(from.getId());
		delegations.saveAndFlush(delegation);
		events.publishEvent(new DelegationChangedEvent(delegation.getId(), from.getEmployeeId(), to.getEmployeeId(),
				delegation.getModule(), delegation.getStartsAt(), delegation.getEndsAt(), false));
		DelegationResponse response = mapper.toResponse(delegation);
		audit.created("iam", ENTITY, delegation.getId(), response);
		return response;
	}

	@Transactional
	public void delete(UUID id) {
		Delegation delegation = delegations.findByIdAndOrgId(id, currentUser.orgId())
				.orElseThrow(() -> new NotFoundException("Delegation " + id + " not found"));
		AppUser caller = callerUser();
		if (!delegation.getFromUserId().equals(caller.getId()) && !authz.isHrAdmin()) {
			throw new ForbiddenException("Only the delegator or an HR admin can remove a delegation");
		}
		AppUser from = users.findById(delegation.getFromUserId()).orElseThrow();
		AppUser to = users.findById(delegation.getToUserId()).orElseThrow();
		DelegationResponse before = mapper.toResponse(delegation);
		delegations.delete(delegation);
		delegations.flush();
		events.publishEvent(new DelegationChangedEvent(id, from.getEmployeeId(), to.getEmployeeId(),
				delegation.getModule(), delegation.getStartsAt(), delegation.getEndsAt(), true));
		audit.deleted("iam", ENTITY, id, before);
	}

	private AppUser callerUser() {
		return users.findByEmployeeId(currentUser.requireEmployeeId())
				.orElseThrow(() -> new ForbiddenException("The caller has no user account"));
	}
}
