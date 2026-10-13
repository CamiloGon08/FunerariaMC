
package com.example.funerariamc.model;

import com.example.funerariamc.model.enums.NotificationType;
import com.example.funerariamc.model.enums.NotificationChannel;
import com.example.funerariamc.model.enums.NotificationStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Represents a notification for upcoming or overdue vault payments.
 */
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

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false)
    private NotificationType notificationType = NotificationType.REMINDER;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false)
    private NotificationChannel channel = NotificationChannel.EMAIL;

    @Column(name = "scheduled_date", nullable = false)
    private LocalDate scheduledDate;

    @Column(name = "sent_date")
    private LocalDateTime sentDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private NotificationStatus status = NotificationStatus.PENDING;

    @Column(name = "recipient_contact")
    private String recipientContact;

    @Column(name = "message_content")
    private String messageContent;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
