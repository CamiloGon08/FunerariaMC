package com.example.funerariamc.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Aviso automático de vencimiento/mora de arriendo de una bóveda. Tabla: lease_notification */
@Entity
@Table(name = "lease_notification")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LeaseNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vault_id", nullable = false)
    private Vault vault;

    // REMINDER u OVERDUE_NOTICE
    @Column(name = "notification_type", length = 20, nullable = false)
    private String notificationType = "REMINDER";

    // EMAIL o TELEGRAM
    @Column(name = "channel", length = 15, nullable = false)
    private String channel = "EMAIL";

    @Column(name = "scheduled_date", nullable = false)
    private LocalDate scheduledDate;

    @Column(name = "sent_date")
    private LocalDateTime sentDate;

    // PENDING, SENT, FAILED
    @Column(name = "status", length = 15)
    private String status = "PENDING";

    @Column(name = "recipient_contact", length = 100)
    private String recipientContact;

    @Column(name = "message_content", length = 300)
    private String messageContent;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
