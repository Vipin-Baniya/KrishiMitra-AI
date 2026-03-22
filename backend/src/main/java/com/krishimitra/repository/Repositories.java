package com.krishimitra.repository;

import com.krishimitra.model.entity.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

// ─────────────────────────────────────────────────────────────
// FARMER
// ─────────────────────────────────────────────────────────────

@Repository
public interface FarmerRepository extends JpaRepository<Farmer, UUID> {

    Optional<Farmer> findByPhone(String phone);

    boolean existsByPhone(String phone);

    @Query("SELECT f FROM Farmer f WHERE f.state = :state AND f.isActive = true")
    List<Farmer> findActiveByState(@Param("state") String state);

    @Modifying
    @Query("UPDATE Farmer f SET f.isActive = false WHERE f.id = :id")
    void deactivate(@Param("id") UUID id);
}

// ─────────────────────────────────────────────────────────────
// MANDI
// ─────────────────────────────────────────────────────────────

@Repository
public interface MandiRepository extends JpaRepository<Mandi, UUID> {

    Optional<Mandi> findByNameIgnoreCaseAndStateIgnoreCase(String name, String state);

    List<Mandi> findByStateIgnoreCaseAndIsActiveTrue(String state);

    @Query(value = """
            SELECT m.*, (
                6371 * acos(cos(radians(:lat)) * cos(radians(m.latitude))
                * cos(radians(m.longitude) - radians(:lng))
                + sin(radians(:lat)) * sin(radians(m.latitude)))
            ) AS distance_km
            FROM mandis m
            WHERE m.is_active = true
            ORDER BY distance_km
            LIMIT :limit
            """, nativeQuery = true)
    List<Mandi> findNearestMandis(
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("limit") int limit);
}

// ─────────────────────────────────────────────────────────────
// MANDI PRICE
// ─────────────────────────────────────────────────────────────

@Repository
public interface MandiPriceRepository extends JpaRepository<MandiPrice, Long> {

    /** Used by AiChatService to inject live prices into the system context prompt. */
    Optional<MandiPrice> findTopByCommodityAndMandi_StateOrderByPriceDateDesc(
            String commodity, String state);

    /** Convenience alias used in AiChatService */
    default Optional<MandiPrice> findTopByCommodityAndStateOrderByPriceDateDesc(
            String commodity, String state) {
        return findTopByCommodityAndMandi_StateOrderByPriceDateDesc(commodity, state);
    }

    // Latest price for a commodity at a mandi
    @Query("""
            SELECT mp FROM MandiPrice mp
            WHERE mp.mandi.id = :mandiId
              AND LOWER(mp.commodity) = LOWER(:commodity)
            ORDER BY mp.priceDate DESC
            LIMIT 1
            """)
    Optional<MandiPrice> findLatest(
            @Param("mandiId") UUID mandiId,
            @Param("commodity") String commodity);

    // Historical series for ARIMA/LSTM features
    @Query("""
            SELECT mp FROM MandiPrice mp
            WHERE mp.mandi.id = :mandiId
              AND LOWER(mp.commodity) = LOWER(:commodity)
              AND mp.priceDate BETWEEN :from AND :to
            ORDER BY mp.priceDate ASC
            """)
    List<MandiPrice> findHistory(
            @Param("mandiId")   UUID mandiId,
            @Param("commodity") String commodity,
            @Param("from")      LocalDate from,
            @Param("to")        LocalDate to);

    // All mandis with prices for a commodity on a given date range
    @Query("""
            SELECT mp FROM MandiPrice mp
            JOIN FETCH mp.mandi
            WHERE LOWER(mp.commodity) = LOWER(:commodity)
              AND mp.priceDate >= :since
            ORDER BY mp.modalPrice DESC
            """)
    List<MandiPrice> findByCommoditySince(
            @Param("commodity") String commodity,
            @Param("since")     LocalDate since);

    // Aggregated average by state for heatmap
    @Query("""
            SELECT mp.mandi.state AS state,
                   AVG(mp.modalPrice) AS avgPrice,
                   MAX(mp.priceDate) AS latestDate
            FROM MandiPrice mp
            WHERE LOWER(mp.commodity) = LOWER(:commodity)
              AND mp.priceDate >= :since
            GROUP BY mp.mandi.state
            ORDER BY avgPrice DESC
            """)
    List<Object[]> getStateSummary(
            @Param("commodity") String commodity,
            @Param("since")     LocalDate since);

    // Bulk upsert check — avoid duplicate ingestion
    @Query("""
            SELECT mp.id FROM MandiPrice mp
            WHERE mp.mandi.id = :mandiId
              AND LOWER(mp.commodity) = LOWER(:commodity)
              AND mp.priceDate = :date
            """)
    Optional<Long> findIdForUpsert(
            @Param("mandiId")   UUID mandiId,
            @Param("commodity") String commodity,
            @Param("date")      LocalDate date);

    // Top mandis by modal price for a commodity today
    @Query("""
            SELECT mp FROM MandiPrice mp
            JOIN FETCH mp.mandi
            WHERE LOWER(mp.commodity) = LOWER(:commodity)
              AND mp.priceDate = :date
            ORDER BY mp.modalPrice DESC
            """)
    List<MandiPrice> findTopMandisByPrice(
            @Param("commodity") String commodity,
            @Param("date")      LocalDate date,
            Pageable pageable);
}

// ─────────────────────────────────────────────────────────────
// PRICE PREDICTION
// ─────────────────────────────────────────────────────────────

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

// ─────────────────────────────────────────────────────────────
// ALERT
// ─────────────────────────────────────────────────────────────

@Repository
public interface AlertRepository extends JpaRepository<Alert, UUID> {

    @Query("""
            SELECT a FROM Alert a
            WHERE (a.farmer.id = :farmerId OR a.farmer IS NULL)
              AND (a.expiresAt IS NULL OR a.expiresAt > :now)
            ORDER BY a.createdAt DESC
            """)
    Page<Alert> findForFarmer(
            @Param("farmerId") UUID farmerId,
            @Param("now")      java.time.Instant now,
            Pageable pageable);

    @Query("""
            SELECT COUNT(a) FROM Alert a
            WHERE a.farmer.id = :farmerId
              AND a.isRead = false
              AND (a.expiresAt IS NULL OR a.expiresAt > :now)
            """)
    long countUnread(
            @Param("farmerId") UUID farmerId,
            @Param("now")      java.time.Instant now);

    @Modifying
    @Query("UPDATE Alert a SET a.isRead = true WHERE a.farmer.id = :farmerId")
    int markAllRead(@Param("farmerId") UUID farmerId);

    @Modifying
    @Query("DELETE FROM Alert a WHERE a.expiresAt < :now")
    int deleteExpired(@Param("now") java.time.Instant now);
}

// ─────────────────────────────────────────────────────────────
// FARMER CROP
// ─────────────────────────────────────────────────────────────

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

// ─────────────────────────────────────────────────────────────
// SELL RECOMMENDATION
// ─────────────────────────────────────────────────────────────

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

// ─────────────────────────────────────────────────────────────
// PROFIT SIMULATION
// ─────────────────────────────────────────────────────────────

@Repository
public interface ProfitSimulationRepository extends JpaRepository<ProfitSimulation, UUID> {
    Page<ProfitSimulation> findByFarmerIdOrderByCreatedAtDesc(UUID farmerId, Pageable pageable);
}

// ─────────────────────────────────────────────────────────────
// REFRESH TOKEN
// ─────────────────────────────────────────────────────────────

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByToken(String token);

    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.farmer.id = :farmerId")
    int revokeAllForFarmer(@Param("farmerId") UUID farmerId);

    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.expiresAt < :now OR rt.revoked = true")
    int deleteExpiredAndRevoked(@Param("now") java.time.Instant now);
}
