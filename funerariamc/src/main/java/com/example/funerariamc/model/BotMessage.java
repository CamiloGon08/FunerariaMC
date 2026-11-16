package com.example.funerariamc.model;

import com.example.funerariamc.utils.enums.BotMessageDirection;
import com.example.funerariamc.utils.enums.BotMessageStatus;
import com.example.funerariamc.utils.enums.BotTopic;
import com.example.funerariamc.utils.enums.NotificationChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Un mensaje del bot de Telegram o un aviso por correo al titular de un plan.*/
@Entity
@Table(name = "bot_message")
@Getter
@Setter
@NoArgsConstructor
public class BotMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "chat_id", length = 40)
    private String chatId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    private Client client;
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", length = 10, nullable = false)
    private BotMessageDirection direction;
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 10, nullable = false)
    private NotificationChannel channel;
    @Enumerated(EnumType.STRING)
    @Column(name = "topic", length = 20, nullable = false)
    private BotTopic topic;
    @Column(name = "message_text", length = 2000, nullable = false)
    private String messageText;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private BotMessageStatus status;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
