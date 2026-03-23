package com.krishimitra.repository;

import com.krishimitra.model.entity.PricePrediction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PricePredictionRepository extends JpaRepository<PricePrediction, UUID> {

    @Query("""
            SELECT p FROM PricePrediction p
            WHERE p.mandi.id = :mandiId
              AND LOWER(p.commodity) = LOWER(:commodity)
              AND p.expiresAt > :now
            ORDER BY p.generatedAt DESC
            LIMIT 1
            """)
    Optional<PricePrediction> findFreshPrediction(
            @Param("mandiId")   UUID mandiId,
            @Param("commodity") String commodity,
            @Param("now")       java.time.Instant now);

    @Modifying
    @Query("DELETE FROM PricePrediction p WHERE p.expiresAt < :now")
    int deleteExpired(@Param("now") java.time.Instant now);
}
