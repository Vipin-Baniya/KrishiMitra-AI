package com.krishimitra.repository;

import com.krishimitra.model.entity.Mandi;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
