package com.ivoyant.hrms.org.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ivoyant.hrms.common.event.DepartmentActivationChangedEvent;
import com.ivoyant.hrms.common.event.DepartmentCreatedEvent;
import com.ivoyant.hrms.common.event.DepartmentDeletedEvent;
import com.ivoyant.hrms.common.event.DepartmentHeadChangedEvent;
import com.ivoyant.hrms.common.event.DepartmentMovedEvent;
import com.ivoyant.hrms.common.event.DepartmentUpdatedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.org.DepartmentView;
import com.ivoyant.hrms.org.Departments;
import com.ivoyant.hrms.org.domain.Department;
import com.ivoyant.hrms.org.repo.DepartmentRepository;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The department facade. The whole tree is cached for five minutes (it is small) and dropped when anything changes. */
@Service
class DepartmentsService implements Departments {

	private final DepartmentRepository repo;
	private final CurrentUser currentUser;
	private final Cache<UUID, DeptTree> trees = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).build();

	DepartmentsService(DepartmentRepository repo, CurrentUser currentUser) {
		this.repo = repo;
		this.currentUser = currentUser;
	}

	@EventListener
	void onCreated(DepartmentCreatedEvent e) {
		trees.invalidateAll();
	}

	@EventListener
	void onUpdated(DepartmentUpdatedEvent e) {
		trees.invalidateAll();
	}

	@EventListener
	void onMoved(DepartmentMovedEvent e) {
		trees.invalidateAll();
	}

	@EventListener
	void onHead(DepartmentHeadChangedEvent e) {
		trees.invalidateAll();
	}

	@EventListener
	void onActivation(DepartmentActivationChangedEvent e) {
		trees.invalidateAll();
	}

	@EventListener
	void onDeleted(DepartmentDeletedEvent e) {
		trees.invalidateAll();
	}

	private DeptTree tree() {
		return trees.get(currentUser.orgId(), org -> new DeptTree(repo.findByOrgId(org)));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<DepartmentView> get(UUID id) {
		return Optional.ofNullable(tree().get(id)).map(d -> new DepartmentView(d.getId(), d.getName(), d.getCode(),
				d.getParentId(), d.getHeadEmployeeId(), d.getCostCentre(), d.getDefaultLocationId(), d.isActive()));
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, String> names(Collection<UUID> ids) {
		DeptTree t = tree();
		return ids.stream().map(t::get).filter(d -> d != null).collect(Collectors.toMap(Department::getId, Department::getName));
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isActive(UUID id) {
		Department d = tree().get(id);
		return d != null && d.isActive();
	}

	@Override
	@Transactional(readOnly = true)
	public List<UUID> ancestors(UUID id) {
		return tree().ancestors(id).stream().map(Department::getId).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Set<UUID> descendants(UUID id) {
		return tree().get(id) == null ? Set.of() : tree().descendants(id);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UUID> headOf(UUID departmentId) {
		Department d = tree().get(departmentId);
		return d == null ? Optional.empty() : Optional.ofNullable(d.getHeadEmployeeId());
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UUID> effectiveHead(UUID departmentId) {
		DeptTree t = tree();
		Department d = t.get(departmentId);
		if (d == null) {
			return Optional.empty();
		}
		if (d.getHeadEmployeeId() != null) {
			return Optional.of(d.getHeadEmployeeId());
		}
		return t.ancestors(departmentId).stream().map(Department::getHeadEmployeeId).filter(h -> h != null).findFirst();
	}

	@Override
	@Transactional(readOnly = true)
	public List<UUID> departmentsHeadedBy(UUID employeeId) {
		DeptTree t = tree();
		return t.roots().stream().flatMap(r -> t.descendants(r.getId()).stream()).map(t::get)
				.filter(d -> d.isActive() && employeeId.equals(d.getHeadEmployeeId())).map(Department::getId).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UUID> defaultLocation(UUID departmentId) {
		Department d = tree().get(departmentId);
		return d == null ? Optional.empty() : Optional.ofNullable(d.getDefaultLocationId());
	}

	@Override
	@Transactional(readOnly = true)
	public String path(UUID id, String separator) {
		return String.join(separator, tree().path(id));
	}
}
