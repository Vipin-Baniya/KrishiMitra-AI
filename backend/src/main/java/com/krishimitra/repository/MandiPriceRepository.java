package com.krishimitra.repository;

import com.krishimitra.model.entity.MandiPrice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
