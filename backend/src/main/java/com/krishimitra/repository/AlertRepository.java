package com.krishimitra.repository;

import com.krishimitra.model.entity.Alert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

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
