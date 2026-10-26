package com.ivoyant.hrms.config.repo;

import com.ivoyant.hrms.config.domain.ConfigArea;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConfigAreaRepository extends JpaRepository<ConfigArea, String> {

	List<ConfigArea> findAllByOrderBySortAscNameAsc();
}
