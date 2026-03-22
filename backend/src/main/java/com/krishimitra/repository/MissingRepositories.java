package com.krishimitra.repository;

import com.krishimitra.model.entity.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// ─────────────────────────────────────────────────────────────
// REFRESH TOKEN REPOSITORY
// ─────────────────────────────────────────────────────────────
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenAndRevokedFalse(String token);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.farmer.id = :farmerId")
    int revokeAllForFarmer(UUID farmerId);

    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.expiresAt < :cutoff OR r.revoked = true")
    int deleteExpiredAndRevoked(Instant cutoff);
}

// ─────────────────────────────────────────────────────────────
// CHAT SESSION REPOSITORY
// ─────────────────────────────────────────────────────────────
@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {

    List<ChatSession> findByFarmerIdOrderByLastMsgAtDesc(UUID farmerId);

    @Query("SELECT s FROM ChatSession s WHERE s.farmer.id = :farmerId ORDER BY s.lastMsgAt DESC")
    Page<ChatSession> findRecentByFarmer(UUID farmerId, Pageable pageable);

    @Query("SELECT COUNT(s) FROM ChatSession s WHERE s.farmer.id = :farmerId")
    long countByFarmerId(UUID farmerId);
}

// ─────────────────────────────────────────────────────────────
// CHAT MESSAGE REPOSITORY
// ─────────────────────────────────────────────────────────────
@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    @Query("""
        SELECT m FROM ChatMessage m
        WHERE m.session.id = :sessionId
        ORDER BY m.createdAt ASC
        """)
    List<ChatMessage> findConversationHistory(UUID sessionId);

    /** Last N messages for context window injection. */
    @Query(value = """
        SELECT * FROM chat_messages
        WHERE session_id = :sessionId
        ORDER BY created_at DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<ChatMessage> findLastNMessages(UUID sessionId, int limit);

    @Modifying
    @Query("DELETE FROM ChatMessage m WHERE m.session.id = :sessionId")
    int deleteBySessionId(UUID sessionId);
}

// ─────────────────────────────────────────────────────────────
// PROFIT SIMULATION REPOSITORY
// ─────────────────────────────────────────────────────────────
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
