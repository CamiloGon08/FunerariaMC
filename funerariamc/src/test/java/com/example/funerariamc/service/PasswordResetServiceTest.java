package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidResetCodeException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Employee;
import com.example.funerariamc.model.PasswordResetCode;
import com.example.funerariamc.model.enums.NotificationChannel;
import com.example.funerariamc.persistence.AppUserRepository;
import com.example.funerariamc.persistence.PasswordResetCodeRepository;
import com.example.funerariamc.service.notification.EmailNotificationSender;
import com.example.funerariamc.service.notification.TelegramNotificationSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PasswordResetServiceTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final PasswordResetCodeRepository codes = mock(PasswordResetCodeRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AccessLogService audit = mock(AccessLogService.class);
    private final TelegramNotificationSender telegram = mock(TelegramNotificationSender.class);
    private final EmailNotificationSender email = mock(EmailNotificationSender.class);
    private PasswordResetService service;
    private AppUser user;
    private PasswordResetCode pending;

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(users, codes, encoder, audit, telegram, email, 10, 5);
        Employee employee = new Employee();
        employee.setEmail("employee@example.test");
        employee.setTelegramChatId("123");
        user = new AppUser();
        user.setId(42L);
        user.setEmployee(employee);
        user.setPasswordHash("previous-password-hash");
        pending = new PasswordResetCode();
        pending.setAppUser(user);
        pending.setCodeHash("stored-code-hash");
        pending.setAttempts(0);
        pending.setExpiresAt(LocalDateTime.now().plusMinutes(10));
    }

    @Test
    void unknownUserAndBlankUsernamesHaveNoRecoverySideEffects() {
        when(users.findByUsernameWithEmployeeAndRole("missing")).thenReturn(Optional.empty());

        service.requestCode(null);
        service.requestCode(" ");
        service.requestCode("missing");

        verifyNoInteractions(codes, encoder, audit, telegram, email);
    }

    @Test
    void disabledAccountCannotRequestACode() {
        knownUser();
        user.setIsEnabled(false);

        service.requestCode("user");

        verifyNoInteractions(codes, encoder, audit, telegram, email);
    }

    @Test
    void inactiveEmployeeCannotRequestACode() {
        knownUser();
        user.getEmployee().deactivate();

        service.requestCode("user");

        verifyNoInteractions(codes, encoder, audit, telegram, email);
    }

    @Test
    void missingDeliveryContactsDoNotInvalidateAnExistingCode() {
        knownUser();
        user.getEmployee().setEmail(null);
        user.getEmployee().setTelegramChatId(null);

        service.requestCode("user");

        verifyNoInteractions(codes, encoder, audit, email);
        verify(telegram, never()).send(anyString(), anyString());
    }

    @Test
    void emailRequestStoresOnlyTheHashOfTheDeliveredSixDigitCode() {
        knownUser();
        when(encoder.encode(anyString())).thenReturn("encoded-reset-code");

        service.requestCode(" user ");

        ArgumentCaptor<String> rawCode = ArgumentCaptor.forClass(String.class);
        verify(encoder).encode(rawCode.capture());
        assertTrue(rawCode.getValue().matches("[0-9]{6}"));
        ArgumentCaptor<PasswordResetCode> saved = ArgumentCaptor.forClass(PasswordResetCode.class);
        verify(codes).save(saved.capture());
        assertAll(
                () -> assertEquals("encoded-reset-code", saved.getValue().getCodeHash()),
                () -> assertNotEquals(rawCode.getValue(), saved.getValue().getCodeHash()),
                () -> assertSame(user, saved.getValue().getAppUser()),
                () -> assertEquals(NotificationChannel.EMAIL, saved.getValue().getChannel()),
                () -> assertEquals(0, saved.getValue().getAttempts()),
                () -> assertEquals(Duration.ofMinutes(10), Duration.between(
                        saved.getValue().getCreatedAt(), saved.getValue().getExpiresAt()))
        );
        verify(email).send(eq("employee@example.test"), contains(rawCode.getValue()));
        var order = inOrder(codes, email, audit);
        order.verify(codes).invalidatePending(eq(42L), any(LocalDateTime.class));
        order.verify(email).send(eq("employee@example.test"), anyString());
        order.verify(codes).save(any(PasswordResetCode.class));
        order.verify(audit).record(42L, "PASSWORD_RESET_REQUESTED: EMAIL");
    }

    @Test
    void configuredTelegramIsPreferredOverEmail() {
        knownUser();
        when(telegram.isConfigured()).thenReturn(true);
        when(encoder.encode(anyString())).thenReturn("encoded-reset-code");

        service.requestCode("user");

        verify(telegram).send(eq("123"), anyString());
        verifyNoInteractions(email);
        ArgumentCaptor<PasswordResetCode> saved = ArgumentCaptor.forClass(PasswordResetCode.class);
        verify(codes).save(saved.capture());
        assertEquals(NotificationChannel.TELEGRAM, saved.getValue().getChannel());
        verify(audit).record(42L, "PASSWORD_RESET_REQUESTED: TELEGRAM");
    }

    @Test
    void telegramFailureFallsBackToEmail() {
        knownUser();
        when(telegram.isConfigured()).thenReturn(true);
        doThrow(new IllegalStateException("Transport unavailable"))
                .when(telegram).send(anyString(), anyString());

        service.requestCode("user");

        var order = inOrder(telegram, email);
        order.verify(telegram).send(eq("123"), anyString());
        order.verify(email).send(eq("employee@example.test"), anyString());
        verify(audit).record(42L, "PASSWORD_RESET_REQUESTED: EMAIL");
    }

    @Test
    void failedDeliveryDoesNotSaveACodeOrRecordSuccessfulDelivery() {
        knownUser();
        doThrow(new IllegalStateException("Transport unavailable"))
                .when(email).send(anyString(), anyString());

        assertThrows(InvalidResetCodeException.class, () -> service.requestCode("user"));

        verify(codes, never()).save(any());
        verifyNoInteractions(encoder, audit);
    }

    @Test
    void validCodeChangesThePasswordAndInvalidatesAllPendingCodes() {
        recoverableCode();
        when(encoder.matches("123456", "stored-code-hash")).thenReturn(true);
        when(encoder.encode("new-password")).thenReturn("new-password-hash");

        service.resetPassword(" user ", " 123456 ", "new-password");

        assertEquals("new-password-hash", user.getPasswordHash());
        assertNotNull(pending.getUsedAt());
        verify(codes).invalidatePending(eq(42L), any(LocalDateTime.class));
        verify(audit).record(42L, "PASSWORD_RESET_COMPLETED");
    }

    @Test
    void wrongCodeIncrementsTheCounterWithoutChangingThePassword() {
        recoverableCode();

        assertThrows(InvalidResetCodeException.class,
                () -> service.resetPassword("user", "wrong-code", "new-password"));

        assertEquals(1, pending.getAttempts());
        assertNull(pending.getUsedAt());
        assertEquals("previous-password-hash", user.getPasswordHash());
        verify(audit).record(42L, "PASSWORD_RESET_FAILED: INVALID_CODE");
        verify(encoder, never()).encode(anyString());
    }

    @Test
    void fifthWrongAttemptConsumesTheCode() {
        recoverableCode();
        pending.setAttempts(4);

        assertThrows(InvalidResetCodeException.class,
                () -> service.resetPassword("user", "wrong-code", "new-password"));

        assertEquals(5, pending.getAttempts());
        assertNotNull(pending.getUsedAt());
        assertEquals("previous-password-hash", user.getPasswordHash());
    }

    @Test
    void expiredCodeCannotChangeThePassword() {
        recoverableCode();
        pending.setExpiresAt(LocalDateTime.now().minusSeconds(1));

        assertThrows(InvalidResetCodeException.class,
                () -> service.resetPassword("user", "123456", "new-password"));

        assertNotNull(pending.getUsedAt());
        verifyNoInteractions(encoder);
    }

    @Test
    void exhaustedCodeIsRejectedEvenIfItsHashWouldMatch() {
        recoverableCode();
        pending.setAttempts(5);

        assertThrows(InvalidResetCodeException.class,
                () -> service.resetPassword("user", "123456", "new-password"));

        assertNotNull(pending.getUsedAt());
        verifyNoInteractions(encoder);
    }

    @Test
    void disabledAccountCannotRedeemAnEarlierCode() {
        knownUser();
        user.setIsEnabled(false);

        assertThrows(InvalidResetCodeException.class,
                () -> service.resetPassword("user", "123456", "new-password"));

        verifyNoInteractions(codes, encoder, audit);
    }

    @Test
    void inactiveEmployeeCannotRedeemAnEarlierCode() {
        knownUser();
        user.getEmployee().deactivate();

        assertThrows(InvalidResetCodeException.class,
                () -> service.resetPassword("user", "123456", "new-password"));

        verifyNoInteractions(codes, encoder, audit);
    }

    @Test
    void missingPendingCodeCannotChangeThePassword() {
        knownUser();
        when(codes.findFirstByAppUser_IdAndUsedAtIsNullOrderByCreatedAtDesc(42L))
                .thenReturn(Optional.empty());

        assertThrows(InvalidResetCodeException.class,
                () -> service.resetPassword("user", "123456", "new-password"));

        assertEquals("previous-password-hash", user.getPasswordHash());
        verifyNoInteractions(encoder, audit);
    }

    @Test
    void invalidPasswordAndBlankCredentialsAreRejectedBeforePersistence() {
        assertThrows(InvalidResetCodeException.class,
                () -> service.resetPassword("user", "123456", "short"));
        assertThrows(InvalidResetCodeException.class,
                () -> service.resetPassword("user", "123456", null));
        assertThrows(InvalidResetCodeException.class,
                () -> service.resetPassword(null, "123456", "new-password"));
        assertThrows(InvalidResetCodeException.class,
                () -> service.resetPassword("user", " ", "new-password"));
        verifyNoInteractions(users, codes, encoder, audit);
    }

    @Test
    void invalidCodeCommitsTheAttemptCounterThroughTheSpringTransactionProxy() {
        recoverableCode();
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        SimpleTransactionStatus status = new SimpleTransactionStatus();
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        PasswordResetService proxy = transactionalProxy(transactions);

        assertThrows(InvalidResetCodeException.class,
                () -> proxy.resetPassword("user", "wrong-code", "new-password"));

        assertEquals(1, pending.getAttempts());
        verify(transactions).commit(status);
        verify(transactions, never()).rollback(any());
    }

    @Test
    void deliveryFailureRollsBackTheTransactionThatInvalidatedPreviousCodes() {
        knownUser();
        doThrow(new IllegalStateException("Transport unavailable"))
                .when(email).send(anyString(), anyString());
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        SimpleTransactionStatus status = new SimpleTransactionStatus();
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(status);

        assertThrows(InvalidResetCodeException.class,
                () -> transactionalProxy(transactions).requestCode("user"));

        verify(codes).invalidatePending(eq(42L), any(LocalDateTime.class));
        verify(transactions).rollback(status);
        verify(transactions, never()).commit(any());
    }

    @Test
    void failedAuditRollsBackThePasswordChange() {
        recoverableCode();
        when(encoder.matches("123456", "stored-code-hash")).thenReturn(true);
        when(encoder.encode("new-password")).thenReturn("new-password-hash");
        doThrow(new IllegalStateException("Audit unavailable"))
                .when(audit).record(42L, "PASSWORD_RESET_COMPLETED");
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        SimpleTransactionStatus status = new SimpleTransactionStatus();
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(status);

        assertThrows(IllegalStateException.class,
                () -> transactionalProxy(transactions).resetPassword("user", "123456", "new-password"));

        verify(transactions).rollback(status);
        verify(transactions, never()).commit(any());
    }

    private PasswordResetService transactionalProxy(PlatformTransactionManager transactions) {
        TransactionInterceptor advice = new TransactionInterceptor();
        advice.setTransactionManager(transactions);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(service);
        factory.addAdvice(advice);
        return (PasswordResetService) factory.getProxy();
    }

    private void knownUser() {
        when(users.findByUsernameWithEmployeeAndRole("user")).thenReturn(Optional.of(user));
    }

    private void recoverableCode() {
        knownUser();
        when(codes.findFirstByAppUser_IdAndUsedAtIsNullOrderByCreatedAtDesc(42L))
                .thenReturn(Optional.of(pending));
    }
}
