package com.ivoyant.hrms.calendar.repo;

import com.ivoyant.hrms.calendar.domain.HolidayCalendar;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HolidayCalendarRepository extends JpaRepository<HolidayCalendar, UUID> {

	List<HolidayCalendar> findByOrgIdOrderByName(UUID orgId);

	List<HolidayCalendar> findByOrgIdAndIdIn(UUID orgId, Collection<UUID> ids);

	Optional<HolidayCalendar> findByIdAndOrgId(UUID id, UUID orgId);

	Optional<HolidayCalendar> findFirstByOrgIdAndStateIgnoreCaseAndActiveTrue(UUID orgId, String state);

	Optional<HolidayCalendar> findFirstByOrgIdAndStateIsNullAndActiveTrueOrderByName(UUID orgId);
}
