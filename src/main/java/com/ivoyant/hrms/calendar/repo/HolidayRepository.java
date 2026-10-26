package com.ivoyant.hrms.calendar.repo;

import com.ivoyant.hrms.calendar.domain.Holiday;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HolidayRepository extends JpaRepository<Holiday, UUID> {

	List<Holiday> findByCalendarIdAndDayBetweenOrderByDay(UUID calendarId, LocalDate from, LocalDate to);
}
