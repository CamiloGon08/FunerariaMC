package com.example.funerariamc.persistence;

import com.example.funerariamc.model.ClientVerificationCode;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ClientVerificationCodeRepository extends JpaRepository<ClientVerificationCode, Long> {

    /** Último código sin usar que pidió el chat, con su titular.*/
    @EntityGraph(attributePaths = "client")
    Optional<ClientVerificationCode> findFirstByChatIdAndUsedAtIsNullOrderByCreatedAtDesc(String chatId);

    /** Solo vale el último código del chat. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ClientVerificationCode c
            set c.usedAt = :now
            where c.chatId = :chatId and c.usedAt is null
            """)
    int invalidatePending(@Param("chatId") String chatId, @Param("now") LocalDateTime now);
}
