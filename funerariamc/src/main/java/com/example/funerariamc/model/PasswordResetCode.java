package com.example.funerariamc.model;

import com.example.funerariamc.model.enums.NotificationChannel;
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

/** Codiog de recuperaccion de contraseña: solo guarda
 * su hash BCrypt
 */
@Entity 
@Table(name = "password_reset_code")
@Getter
@Setter
@NoArgsConstructor
public class PasswordResetCode {

    @Id 
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "App_user_id", nullable = false)
    private AppUser appUser;

    @Column(name = "code_hash", nullable = false, length = 65)
    private String codeHash;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 12)
    private NotificationChannel channel;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "attempts", nullable = false)
    private Integer attempts;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

}
