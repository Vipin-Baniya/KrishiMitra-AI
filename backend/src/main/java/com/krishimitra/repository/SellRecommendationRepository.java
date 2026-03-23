package com.krishimitra.repository;

import com.krishimitra.model.entity.SellRecommendation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SellRecommendationRepository extends JpaRepository<SellRecommendation, UUID> {

    Page<SellRecommendation> findByFarmerIdOrderByCreatedAtDesc(UUID farmerId, Pageable pageable);

    @Query("""
            SELECT sr FROM SellRecommendation sr
            WHERE sr.farmer.id = :farmerId
              AND LOWER(sr.commodity) = LOWER(:commodity)
            ORDER BY sr.createdAt DESC
            LIMIT 1
            """)
    Optional<SellRecommendation> findLatestForCrop(
            @Param("farmerId")  UUID farmerId,
            @Param("commodity") String commodity);
}
