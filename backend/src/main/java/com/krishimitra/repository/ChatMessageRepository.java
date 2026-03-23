package com.krishimitra.repository;

import com.krishimitra.model.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

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
