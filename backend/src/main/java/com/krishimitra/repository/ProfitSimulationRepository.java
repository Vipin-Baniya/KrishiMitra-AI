package com.krishimitra.repository;

import com.krishimitra.model.entity.ProfitSimulation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ProfitSimulationRepository extends JpaRepository<ProfitSimulation, UUID> {

    List<ProfitSimulation> findByFarmerIdOrderByCreatedAtDesc(UUID farmerId);

    @Query("""
        SELECT p FROM ProfitSimulation p
        WHERE p.farmer.id = :farmerId
          AND p.commodity = :commodity
        ORDER BY p.createdAt DESC
        """)
    List<ProfitSimulation> findByCommodityForFarmer(UUID farmerId, String commodity);
}
