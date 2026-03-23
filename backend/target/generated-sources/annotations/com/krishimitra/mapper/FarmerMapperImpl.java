package com.krishimitra.mapper;

import com.krishimitra.dto.FarmerProfileResponse;
import com.krishimitra.dto.UpdateProfileRequest;
import com.krishimitra.model.entity.Farmer;
import com.krishimitra.model.entity.FarmerCrop;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-23T17:02:08+0000",
    comments = "version: 1.5.5.Final, compiler: javac, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class FarmerMapperImpl implements FarmerMapper {

    @Override
    public FarmerProfileResponse toDto(Farmer farmer) {
        if ( farmer == null ) {
            return null;
        }

        UUID id = null;
        String name = null;
        String phone = null;
        String email = null;
        String village = null;
        String district = null;
        String state = null;
        String preferredLang = null;
        List<FarmerProfileResponse.FarmerCropDto> crops = null;

        id = farmer.getId();
        name = farmer.getName();
        phone = farmer.getPhone();
        email = farmer.getEmail();
        village = farmer.getVillage();
        district = farmer.getDistrict();
        state = farmer.getState();
        preferredLang = farmer.getPreferredLang();
        crops = farmerCropListToFarmerCropDtoList( farmer.getCrops() );

        int unreadAlerts = farmer.getAlerts() == null ? 0 : (int) farmer.getAlerts().stream().filter(a -> !a.getIsRead()).count();

        FarmerProfileResponse farmerProfileResponse = new FarmerProfileResponse( id, name, phone, email, village, district, state, preferredLang, crops, unreadAlerts );

        return farmerProfileResponse;
    }

    @Override
    public void updateFarmerFromDto(UpdateProfileRequest dto, Farmer farmer) {
        if ( dto == null ) {
            return;
        }

        farmer.setName( dto.name() );
        farmer.setEmail( dto.email() );
        farmer.setVillage( dto.village() );
        farmer.setDistrict( dto.district() );
        farmer.setState( dto.state() );
        farmer.setLatitude( dto.latitude() );
        farmer.setLongitude( dto.longitude() );
        farmer.setPreferredLang( dto.preferredLang() );
    }

    protected FarmerProfileResponse.FarmerCropDto farmerCropToFarmerCropDto(FarmerCrop farmerCrop) {
        if ( farmerCrop == null ) {
            return null;
        }

        UUID id = null;
        String commodity = null;
        String variety = null;
        BigDecimal quantityQuintal = null;
        String expectedHarvest = null;
        boolean storageAvailable = false;

        id = farmerCrop.getId();
        commodity = farmerCrop.getCommodity();
        variety = farmerCrop.getVariety();
        quantityQuintal = farmerCrop.getQuantityQuintal();
        if ( farmerCrop.getExpectedHarvest() != null ) {
            expectedHarvest = DateTimeFormatter.ISO_LOCAL_DATE.format( farmerCrop.getExpectedHarvest() );
        }
        if ( farmerCrop.getStorageAvailable() != null ) {
            storageAvailable = farmerCrop.getStorageAvailable();
        }

        FarmerProfileResponse.FarmerCropDto farmerCropDto = new FarmerProfileResponse.FarmerCropDto( id, commodity, variety, quantityQuintal, expectedHarvest, storageAvailable );

        return farmerCropDto;
    }

    protected List<FarmerProfileResponse.FarmerCropDto> farmerCropListToFarmerCropDtoList(List<FarmerCrop> list) {
        if ( list == null ) {
            return null;
        }

        List<FarmerProfileResponse.FarmerCropDto> list1 = new ArrayList<FarmerProfileResponse.FarmerCropDto>( list.size() );
        for ( FarmerCrop farmerCrop : list ) {
            list1.add( farmerCropToFarmerCropDto( farmerCrop ) );
        }

        return list1;
    }
}
