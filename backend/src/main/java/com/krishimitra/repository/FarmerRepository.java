package com.krishimitra.repository;

import com.krishimitra.model.entity.Farmer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
