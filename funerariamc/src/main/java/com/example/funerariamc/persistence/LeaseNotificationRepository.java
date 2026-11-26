package com.example.funerariamc.persistence;

import com.example.funerariamc.model.LeaseNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.time.LocalDate;
import com.example.funerariamc.utils.enums.NotificationType;

public interface LeaseNotificationRepository extends JpaRepository<LeaseNotification, Long> {

    /** Avisos de arriendo de una bóveda. */
    List<LeaseNotification> findByVaultIdOrderByScheduledDateDesc(Long vaultId);

    /** Busca el aviso del mismo vencimiento para reutilizar fallos y evitar otro envío. */
    Optional<LeaseNotification> findFirstByVaultIdAndNotificationTypeAndScheduledDate(
            Long vaultId, NotificationType notificationType, LocalDate scheduledDate);

    /** Indica si alguna bóveda del cementerio tiene avisos de arriendo. */
    boolean existsByVaultCemeteryId(Long cemeteryId);
}
