package com.krishimitra.repository;

import com.krishimitra.model.entity.ChatSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {

    List<ChatSession> findByFarmerIdOrderByLastMsgAtDesc(UUID farmerId);

    @Query("SELECT s FROM ChatSession s WHERE s.farmer.id = :farmerId ORDER BY s.lastMsgAt DESC")
    Page<ChatSession> findRecentByFarmer(UUID farmerId, Pageable pageable);

    @Query("SELECT COUNT(s) FROM ChatSession s WHERE s.farmer.id = :farmerId")
    long countByFarmerId(UUID farmerId);
}
