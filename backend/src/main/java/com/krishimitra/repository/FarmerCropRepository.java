package com.krishimitra.repository;

import com.krishimitra.model.entity.FarmerCrop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface FarmerCropRepository extends JpaRepository<FarmerCrop, UUID> {

    List<FarmerCrop> findByFarmerIdOrderByCreatedAtDesc(UUID farmerId);

    @Query("""
            SELECT fc FROM FarmerCrop fc
            WHERE LOWER(fc.commodity) = LOWER(:commodity)
              AND fc.expectedHarvest BETWEEN :from AND :to
            """)
    List<FarmerCrop> findHarvestingBetween(
            @Param("commodity") String commodity,
            @Param("from")      LocalDate from,
            @Param("to")        LocalDate to);
}
