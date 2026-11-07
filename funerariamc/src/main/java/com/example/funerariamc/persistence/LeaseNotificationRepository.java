package com.example.funerariamc.persistence;

import com.example.funerariamc.model.LeaseNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LeaseNotificationRepository extends JpaRepository<LeaseNotification, Long> {

    /** Avisos de arriendo de una bóveda. */
    List<LeaseNotification> findByVaultIdOrderByScheduledDateDesc(Long vaultId);
}
