package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.org.Departments;
import com.ivoyant.hrms.org.OrgSettings;
import com.ivoyant.hrms.org.TitleView;
import com.ivoyant.hrms.org.Titles;
import com.ivoyant.hrms.org.domain.Designation;
import com.ivoyant.hrms.org.domain.JobTitle;
import com.ivoyant.hrms.org.repo.DesignationRepository;
import com.ivoyant.hrms.org.repo.JobTitleRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The title facade. Titles are few and read often, but every call is cheap enough that nothing is cached (the display depends on the employee). */
@Service
class TitlesService implements Titles {

	private final JobTitleRepository repo;
	private final DesignationRepository positions;
	private final Departments departments;
	private final CurrentUser currentUser;
	private final OrgSettings settings;
	private final JdbcClient jdbc;

	TitlesService(JobTitleRepository repo, DesignationRepository positions, Departments departments,
			CurrentUser currentUser, OrgSettings settings, JdbcClient jdbc) {
		this.repo = repo;
		this.positions = positions;
		this.departments = departments;
		this.currentUser = currentUser;
		this.settings = settings;
		this.jdbc = jdbc;
	}

	private static TitleView view(JobTitle t) {
		return new TitleView(t.getId(), t.getName(), t.getCode(), t.getDesignationId(), t.getDepartmentId(), t.isActive());
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<TitleView> get(UUID id) {
		return repo.findByIdAndOrgId(id, currentUser.orgId()).map(TitlesService::view);
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, String> names(Collection<UUID> ids) {
		return ids.isEmpty() ? Map.of() : repo.findByOrgIdAndIdIn(currentUser.orgId(), ids).stream()
				.collect(Collectors.toMap(JobTitle::getId, JobTitle::getName));
	}

	@Override
	@Transactional(readOnly = true)
	public boolean validFor(UUID titleId, UUID departmentId) {
		JobTitle t = repo.findByIdAndOrgId(titleId, currentUser.orgId()).orElse(null);
		return t != null && fits(t, departmentId);
	}

	boolean fits(JobTitle t, UUID departmentId) {
		if (t.getDepartmentId() == null) {
			return true;
		}
		return departmentId != null
				&& (t.getDepartmentId().equals(departmentId) || departments.ancestors(departmentId).contains(t.getDepartmentId()));
	}

	@Override
	@Transactional(readOnly = true)
	public List<TitleView> optionsFor(UUID departmentId) {
		Map<UUID, Boolean> positionActive = positions.findByOrgId(currentUser.orgId()).stream()
				.collect(Collectors.toMap(Designation::getId, Designation::isActive));
		return repo.findByOrgId(currentUser.orgId()).stream()
				.filter(t -> t.isActive() && positionActive.getOrDefault(t.getDesignationId(), false)
						&& (departmentId == null || fits(t, departmentId)))
				.map(TitlesService::view).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public String display(UUID employeeId) {
		return displays(List.of(employeeId)).get(employeeId);
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, String> displays(Collection<UUID> employeeIds) {
		if (employeeIds.isEmpty()) {
			return Map.of();
		}
		String format = settings.titleFormat();
		Map<UUID, String> out = new HashMap<>();
		jdbc.sql("""
				select e.id, t.name, d.title, d.code
				from employee e join job_title t on t.id = e.title_id left join designation d on d.id = t.designation_id
				where e.id in (:ids)""").param("ids", employeeIds).query((rs, i) -> {
					out.put(rs.getObject(1, UUID.class),
							TitleDisplay.render(format, rs.getString(2), rs.getString(3), rs.getString(4)));
					return 0;
				}).list();
		return out;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UUID> positionOf(UUID titleId) {
		return repo.findByIdAndOrgId(titleId, currentUser.orgId()).map(JobTitle::getDesignationId);
	}
}
