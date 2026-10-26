package com.ivoyant.hrms.iam.mapper;

import com.ivoyant.hrms.iam.domain.AppUser;
import com.ivoyant.hrms.iam.dto.UserResponse;
import org.mapstruct.Mapper;

@Mapper
public interface UserMapper {

	UserResponse toResponse(AppUser user);
}
