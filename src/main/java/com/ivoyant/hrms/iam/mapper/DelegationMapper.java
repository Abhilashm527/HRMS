package com.ivoyant.hrms.iam.mapper;

import com.ivoyant.hrms.iam.domain.Delegation;
import com.ivoyant.hrms.iam.dto.CreateDelegationRequest;
import com.ivoyant.hrms.iam.dto.DelegationResponse;
import org.mapstruct.Mapper;

@Mapper
public interface DelegationMapper {

	DelegationResponse toResponse(Delegation delegation);

	Delegation toEntity(CreateDelegationRequest request);
}
