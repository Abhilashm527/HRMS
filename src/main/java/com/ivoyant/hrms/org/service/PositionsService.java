package com.ivoyant.hrms.org.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ivoyant.hrms.common.event.PositionActivationChangedEvent;
import com.ivoyant.hrms.common.event.PositionApprovesChangedEvent;
import com.ivoyant.hrms.common.event.PositionBandChangedEvent;
import com.ivoyant.hrms.common.event.PositionCreatedEvent;
import com.ivoyant.hrms.common.event.PositionDeletedEvent;
import com.ivoyant.hrms.common.event.PositionLevelChangedEvent;
import com.ivoyant.hrms.common.event.PositionReportsToChangedEvent;
import com.ivoyant.hrms.common.event.PositionUpdatedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.org.BandCheck;
import com.ivoyant.hrms.org.PositionView;
import com.ivoyant.hrms.org.Positions;
import com.ivoyant.hrms.org.domain.Designation;
import com.ivoyant.hrms.org.repo.DesignationRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The position facade. The whole ladder is cached for five minutes (it is small) and dropped when anything changes. */
@Service
class PositionsService implements Positions {

	private final DesignationRepository repo;
	private final CurrentUser currentUser;
	private final JdbcClient jdbc;
	private final PositionQueryService query;
	private final Cache<UUID, PosTree> trees = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).build();

	PositionsService(DesignationRepository repo, CurrentUser currentUser, JdbcClient jdbc, PositionQueryService query) {
		this.repo = repo;
		this.currentUser = currentUser;
		this.jdbc = jdbc;
		this.query = query;
	}

	@EventListener
	void onCreated(PositionCreatedEvent e) {
		trees.invalidateAll();
	}

	@EventListener
	void onUpdated(PositionUpdatedEvent e) {
		trees.invalidateAll();
	}

	@EventListener
	void onReportsTo(PositionReportsToChangedEvent e) {
		trees.invalidateAll();
	}

	@EventListener
	void onLevel(PositionLevelChangedEvent e) {
		trees.invalidateAll();
	}

	@EventListener
	void onBand(PositionBandChangedEvent e) {
		trees.invalidateAll();
	}

	@EventListener
	void onApproves(PositionApprovesChangedEvent e) {
		trees.invalidateAll();
	}

	@EventListener
	void onActivation(PositionActivationChangedEvent e) {
		trees.invalidateAll();
	}

	@EventListener
	void onDeleted(PositionDeletedEvent e) {
		trees.invalidateAll();
	}

	private PosTree tree() {
		return trees.get(currentUser.orgId(), org -> new PosTree(repo.findByOrgId(org)));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<PositionView> get(UUID id) {
		return Optional.ofNullable(tree().get(id)).map(d -> new PositionView(d.getId(), d.getTitle(), d.getCode(),
				d.getLevel(), d.getTrack(), d.getBandMin(), d.getBandMax(), d.getReportsToId(), d.isApproves(),
				d.isActive(), d.getJobLevelId()));
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, String> names(Collection<UUID> ids) {
		PosTree t = tree();
		return ids.stream().map(t::get).filter(d -> d != null).collect(Collectors.toMap(Designation::getId, Designation::getTitle));
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isActive(UUID id) {
		Designation d = tree().get(id);
		return d != null && d.isActive();
	}

	@Override
	@Transactional(readOnly = true)
	public List<UUID> ladder(UUID id) {
		return tree().ancestors(id).stream().map(Designation::getId).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UUID> top() {
		List<Designation> tops = tree().activeTops();
		return tops.size() == 1 ? Optional.of(tops.get(0).getId()) : Optional.empty();
	}

	@Override
	@Transactional(readOnly = true)
	public boolean approves(UUID id) {
		Designation d = tree().get(id);
		return d != null && d.isApproves();
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isTop(UUID id) {
		return top().map(id::equals).orElse(false);
	}

	@Override
	@Transactional(readOnly = true)
	public long countByLevel(UUID jobLevelId) {
		return jdbc.sql("select count(*) from designation where job_level_id = :l").param("l", jobLevelId)
				.query(Long.class).single();
	}

	@Override
	@Transactional(readOnly = true)
	public BandCheck checkBand(UUID id, BigDecimal annualAmount) {
		return query.bandCheck(id, annualAmount);
	}
}
