package com.example.funerariamc.persistence;

import com.example.funerariamc.model.PasswordResetCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PasswordResetCodeRepository extends JpaRepository<PasswordResetCode, Long> {

    /** Último código sin usar del usuario; el servicio revisa si ya venció o agotó intentos. */
    Optional<PasswordResetCode> findFirstByAppUser_IdAndUsedAtIsNullOrderByCreatedAtDesc(Long appUserId);

    /**
     * Marca como usados los códigos pendientes del usuario, para que solo valga
     * el último que se envió y ninguno sirva después de cambiar la contraseña.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PasswordResetCode c
            set c.usedAt = :now
            where c.appUser.id = :appUserId and c.usedAt is null
            """)
    int invalidatePending(@Param("appUserId") Long appUserId, @Param("now") LocalDateTime now);
}
