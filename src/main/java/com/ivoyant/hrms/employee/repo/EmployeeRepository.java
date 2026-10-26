package com.ivoyant.hrms.employee.repo;

import com.ivoyant.hrms.employee.domain.Employee;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface EmployeeRepository extends JpaRepository<Employee, UUID>, JpaSpecificationExecutor<Employee> {

	Optional<Employee> findByIdAndOrgId(UUID id, UUID orgId);

	List<Employee> findByOrgIdAndManagerIdOrderByDisplayName(UUID orgId, UUID managerId);

	List<Employee> findByManagerId(UUID managerId);

	boolean existsByOrgIdAndEmployeeCode(UUID orgId, String employeeCode);

	boolean existsByWorkEmailIgnoreCase(String workEmail);

	boolean existsByWorkEmailIgnoreCaseAndIdNot(String workEmail, UUID id);

	boolean existsByOrgIdAndEmployeeCodeAndIdNot(UUID orgId, String employeeCode, UUID id);
}
