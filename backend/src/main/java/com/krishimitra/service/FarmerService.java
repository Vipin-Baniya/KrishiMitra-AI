package com.krishimitra.service;

import com.krishimitra.dto.FarmerProfileResponse;
import com.krishimitra.dto.UpdateFarmerRequest;
import com.krishimitra.exception.ApiException;
import com.krishimitra.model.entity.Farmer;
import com.krishimitra.model.entity.FarmerCrop;
import com.krishimitra.repository.AlertRepository;
import com.krishimitra.repository.FarmerCropRepository;
import com.krishimitra.repository.FarmerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FarmerService {

    private final FarmerRepository farmerRepo;
    private final FarmerCropRepository cropRepo;
    private final AlertRepository alertRepo;

    @Cacheable(value = "farmer-profile", key = "#farmerId")
    public FarmerProfileResponse getProfile(UUID farmerId) {
        Farmer farmer = farmerRepo.findById(farmerId)
                .orElseThrow(() -> ApiException.notFound("Farmer not found"));

        List<FarmerProfileResponse.FarmerCropDto> crops = cropRepo
                .findByFarmerIdOrderByCreatedAtDesc(farmerId).stream()
                .map(c -> new FarmerProfileResponse.FarmerCropDto(
                        c.getId(), c.getCommodity(), c.getVariety(),
                        c.getQuantityQuintal(),
                        c.getExpectedHarvest() != null ? c.getExpectedHarvest().toString() : null,
                        c.getStorageAvailable()))
                .toList();

        long unread = alertRepo.countUnread(farmerId, Instant.now());

        return new FarmerProfileResponse(
                farmer.getId(), farmer.getName(), farmer.getPhone(),
                farmer.getEmail(), farmer.getVillage(), farmer.getDistrict(),
                farmer.getState(), farmer.getPreferredLang(), crops, (int) unread
        );
    }

    @Transactional
    @CacheEvict(value = "farmer-profile", key = "#farmerId")
    public FarmerProfileResponse updateProfile(UUID farmerId, UpdateFarmerRequest req) {
        Farmer farmer = farmerRepo.findById(farmerId)
                .orElseThrow(() -> ApiException.notFound("Farmer not found"));

        if (req.name()         != null) farmer.setName(req.name());
        if (req.email()        != null) farmer.setEmail(req.email());
        if (req.village()      != null) farmer.setVillage(req.village());
        if (req.district()     != null) farmer.setDistrict(req.district());
        if (req.state()        != null) farmer.setState(req.state());
        if (req.latitude()     != null) farmer.setLatitude(req.latitude());
        if (req.longitude()    != null) farmer.setLongitude(req.longitude());
        if (req.preferredLang() != null) farmer.setPreferredLang(req.preferredLang());

        farmerRepo.save(farmer);
        return getProfile(farmerId);
    }

    @Transactional
    @CacheEvict(value = "farmer-profile", key = "#farmerId")
    public FarmerProfileResponse.FarmerCropDto addCrop(UUID farmerId, FarmerCropDto req) {
        Farmer farmer = farmerRepo.findById(farmerId)
                .orElseThrow(() -> ApiException.notFound("Farmer not found"));

        FarmerCrop crop = FarmerCrop.builder()
                .farmer(farmer)
                .commodity(req.commodity())
                .variety(req.variety())
                .quantityQuintal(BigDecimal.valueOf(req.quantityQuintal()))
                .storageAvailable(req.storageAvailable())
                .build();

        crop = cropRepo.save(crop);
        return new FarmerProfileResponse.FarmerCropDto(
                crop.getId(), crop.getCommodity(), crop.getVariety(),
                crop.getQuantityQuintal(), null, crop.getStorageAvailable());
    }

    public record FarmerCropDto(
            String commodity, String variety,
            double quantityQuintal, boolean storageAvailable) {}
}
