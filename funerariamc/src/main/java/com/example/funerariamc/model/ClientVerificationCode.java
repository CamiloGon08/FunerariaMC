package com.example.funerariamc.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**Codigo que envia el bot al correo para verofocar que es el cliente vinculado 
 * a nuestros servicios*/
@Entity
@Table(name = "client_verification_code")
@Getter
@Setter
@NoArgsConstructor
public class ClientVerificationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;
    @Column(name = "chat_id", length = 40, nullable = false)
    private String chatId;
    @Column(name = "code_hash", length = 100, nullable = false)
    private String codeHash;
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
    @Column(name = "used_at")
    private LocalDateTime usedAt;
    @Column(name = "attempts", nullable = false)
    private Integer attempts;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
