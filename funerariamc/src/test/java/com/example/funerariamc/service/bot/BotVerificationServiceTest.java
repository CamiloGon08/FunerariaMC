package com.example.funerariamc.service.bot;

import com.example.funerariamc.model.Client;
import com.example.funerariamc.model.ClientVerificationCode;
import com.example.funerariamc.persistence.ClientVerificationCodeRepository;
import com.example.funerariamc.service.notification.EmailNotificationSender;
import com.example.funerariamc.service.BrandingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BotVerificationServiceTest {

    private final ClientVerificationCodeRepository codes = mock(ClientVerificationCodeRepository.class);
    private final EmailNotificationSender email = mock(EmailNotificationSender.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-15T15:00:00Z"), ZoneId.of("America/Bogota"));
    private BotVerificationService service;
    private Client client;

    @BeforeEach
    void setUp() {
        service = new BotVerificationService(codes, email, encoder, 10, 5, clock);
        client = new Client();
        client.setId(7L);
        client.setName("María");
        client.setEmail("maria@example.test");
    }

    @Test
    void sendCodeMailsSixDigitsAndStoresOnlyTheHash() {
        when(encoder.encode(anyString())).thenReturn("hash");
        service.sendCode("555", client);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(email).send(eq("maria@example.test"), anyString(), body.capture());
        assertTrue(body.getValue().matches("(?s).*\\b\\d{6}\\b.*"));
        verify(codes).invalidatePending(eq("555"), any());
        ArgumentCaptor<ClientVerificationCode> saved = ArgumentCaptor.forClass(ClientVerificationCode.class);
        verify(codes).save(saved.capture());
        assertEquals("hash", saved.getValue().getCodeHash());
        assertEquals(LocalDateTime.now(clock).plusMinutes(10), saved.getValue().getExpiresAt());
    }

    @Test
    void failedMailSavesNoCode() {
        doThrow(new IllegalStateException("smtp")).when(email).send(anyString(), anyString(), anyString());
        assertThrows(IllegalStateException.class, () -> service.sendCode("555", client));
        verify(codes, never()).save(any());
    }

    @Test
    void wrongCodesCountUntilTheyRunOut() {
        ClientVerificationCode pending = pending(LocalDateTime.now(clock).plusMinutes(5));
        when(codes.findFirstByChatIdAndUsedAtIsNullOrderByCreatedAtDesc("555")).thenReturn(Optional.of(pending));
        when(encoder.matches(anyString(), anyString())).thenReturn(false);
        for (int left = 4; left >= 1; left--) {
            assertEquals(left, service.verify("555", "000000").attemptsLeft());
        }
        assertEquals(BotVerificationService.Outcome.EXHAUSTED, service.verify("555", "000000").outcome());
        assertNotNull(pending.getUsedAt());
    }

    @Test
    void validCodeReturnsTheHolder() {
        ClientVerificationCode pending = pending(LocalDateTime.now(clock).plusMinutes(5));
        when(codes.findFirstByChatIdAndUsedAtIsNullOrderByCreatedAtDesc("555")).thenReturn(Optional.of(pending));
        when(encoder.matches("482913", "hash")).thenReturn(true);
        BotVerificationService.Check check = service.verify("555", " 482 913 ");
        assertEquals(BotVerificationService.Outcome.VALID, check.outcome());
        assertEquals(7L, check.clientId());
    }

    @Test
    void expiredCodeIsRejected() {
        ClientVerificationCode pending = pending(LocalDateTime.now(clock).minusMinutes(1));
        when(codes.findFirstByChatIdAndUsedAtIsNullOrderByCreatedAtDesc("555")).thenReturn(Optional.of(pending));
        assertEquals(BotVerificationService.Outcome.EXPIRED, service.verify("555", "482913").outcome());
    }

    @Test
    void maskHidesTheEmail() {
        assertEquals("m***s@gmail.com", BotVerificationService.maskEmail("mariarojas@gmail.com"));
        assertEquals("su correo registrado", BotVerificationService.maskEmail(null));
    }


    @Test
    void usedCodeCannotBeAcceptedAgain() {
        ClientVerificationCode used = pending(LocalDateTime.now(clock).plusMinutes(5));
        used.setUsedAt(LocalDateTime.now(clock));
        when(codes.findFirstByChatIdAndUsedAtIsNullOrderByCreatedAtDesc("555")).thenReturn(Optional.of(used));
        assertEquals(BotVerificationService.Outcome.EXPIRED, service.verify("555", "482913").outcome());
        verify(encoder, never()).matches(anyString(), anyString());
    }

    @Test
    void codeExpiresAtTheExactBoundary() {
        var expired = pending(LocalDateTime.now(clock));
        when(codes.findFirstByChatIdAndUsedAtIsNullOrderByCreatedAtDesc("555")).thenReturn(Optional.of(expired));
        assertEquals(BotVerificationService.Outcome.EXPIRED, service.verify("555", "482913").outcome());
    }

    @Test
    void malformedCodesConsumeAnAttemptWithoutCallingTheEncoder() {
        var code = pending(LocalDateTime.now(clock).plusMinutes(5));
        when(codes.findFirstByChatIdAndUsedAtIsNullOrderByCreatedAtDesc("555")).thenReturn(Optional.of(code));
        assertEquals(BotVerificationService.Outcome.WRONG, service.verify("555", "abc482913").outcome());
        assertEquals(1, code.getAttempts());
        verify(encoder, never()).matches(anyString(), anyString());
    }

    @Test
    void malformedEmailHasASafeFallback() {
        assertEquals("su correo registrado", BotVerificationService.maskEmail("@example.test"));
        assertEquals("su correo registrado", BotVerificationService.maskEmail("name@"));
    }

    @Test
    void verificationEmailUsesTheConfiguredBrand() {
        BrandingService branding = mock(BrandingService.class);
        when(branding.fullName()).thenReturn("Funeraria Jardines de Paz");
        service = new BotVerificationService(codes, email, encoder, 10, 5, clock, branding);
        service.sendCode("555", client);
        verify(email).send(eq("maria@example.test"),
                eq("Código para consultar su plan · Funeraria Jardines de Paz"), contains("Funeraria Jardines de Paz"));
    }

    private ClientVerificationCode pending(LocalDateTime expires) {
        ClientVerificationCode code = new ClientVerificationCode();
        code.setClient(client);
        code.setChatId("555");
        code.setCodeHash("hash");
        code.setAttempts(0);
        code.setExpiresAt(expires);
        return code;
    }
}
