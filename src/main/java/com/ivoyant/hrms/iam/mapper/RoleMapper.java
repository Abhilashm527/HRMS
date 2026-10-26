package com.ivoyant.hrms.iam.mapper;

import com.ivoyant.hrms.iam.domain.Role;
import com.ivoyant.hrms.iam.dto.CreateRoleRequest;
import com.ivoyant.hrms.iam.dto.RoleResponse;
import com.ivoyant.hrms.iam.dto.UpdateRoleRequest;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper
public interface RoleMapper {

	@Mapping(target = "key", source = "roleKey")
	RoleResponse toResponse(Role role);

	@Mapping(target = "roleKey", source = "key")
	Role toEntity(CreateRoleRequest request);

	@BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
	void update(@MappingTarget Role role, UpdateRoleRequest request);
}
