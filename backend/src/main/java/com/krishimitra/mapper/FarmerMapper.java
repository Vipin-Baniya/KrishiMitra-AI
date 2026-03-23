package com.krishimitra.mapper;

import com.krishimitra.dto.FarmerProfileResponse;
import com.krishimitra.dto.UpdateProfileRequest;
import com.krishimitra.model.entity.Farmer;
import org.mapstruct.*;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface FarmerMapper {

    @Mapping(target = "unreadAlerts",
             expression = "java(farmer.getAlerts() == null ? 0 : (int) farmer.getAlerts().stream().filter(a -> !a.getIsRead()).count())")
    FarmerProfileResponse toDto(Farmer farmer);

    @Mapping(target = "id",          ignore = true)
    @Mapping(target = "passwordHash", ignore = true)
    @Mapping(target = "createdAt",   ignore = true)
    @Mapping(target = "updatedAt",   ignore = true)
    @Mapping(target = "alerts",      ignore = true)
    @Mapping(target = "crops",       ignore = true)
    void updateFarmerFromDto(UpdateProfileRequest dto, @MappingTarget Farmer farmer);
}
