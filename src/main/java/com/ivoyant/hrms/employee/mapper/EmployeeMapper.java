package com.ivoyant.hrms.employee.mapper;

import com.ivoyant.hrms.employee.domain.Employee;
import com.ivoyant.hrms.employee.dto.EmployeeSummary;
import org.mapstruct.Mapper;

@Mapper
public interface EmployeeMapper {

	@org.mapstruct.Mapping(target = "titleDisplay", ignore = true)
	EmployeeSummary toSummary(Employee employee);
}
