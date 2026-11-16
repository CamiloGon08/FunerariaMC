package com.example.funerariamc.service;

import com.example.funerariamc.model.Cemetery;
import com.example.funerariamc.model.Client;
import com.example.funerariamc.model.LeaseNotification;
import com.example.funerariamc.model.Vault;
import com.example.funerariamc.persistence.LeaseNotificationRepository;
import com.example.funerariamc.persistence.VaultRepository;
import com.example.funerariamc.service.notification.EmailNotificationSender;
import com.example.funerariamc.utils.enums.NotificationChannel;
import com.example.funerariamc.utils.enums.NotificationStatus;
import com.example.funerariamc.utils.enums.NotificationType;
import com.example.funerariamc.utils.enums.VaultStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Recordatorio del arriendo de bóvedas: un solo correo 30 días antes de cada vencimiento.
 */
class LeaseReminderServiceTest {

    private static final ZoneId ZONE = ZoneId.of("America/Bogota");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);
    private final VaultRepository vaults = mock(VaultRepository.class);
    private final LeaseNotificationRepository notices = mock(LeaseNotificationRepository.class);
    private final SystemParameterService parameters = mock(SystemParameterService.class);
    private final EmailNotificationSender email = mock(EmailNotificationSender.class);
    private final LeaseReminderService service = new LeaseReminderService(vaults, notices, parameters, email,
            Clock.fixed(TODAY.atStartOfDay(ZONE).toInstant(), ZONE));

    @BeforeEach
    void setUp() {
        when(notices.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(notices.findFirstByVaultIdAndNotificationTypeAndScheduledDate(any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    void usesThirtyDaysWhenParameterIsNotDefined() {
        when(parameters.exists(LeaseReminderService.VAULT_LEASE_REMINDER_DAYS)).thenReturn(false);
        when(vaults.findLeasesEndingBetween(any(), any(), any())).thenReturn(List.of());

        service.sendDueReminders();

        verify(vaults).findLeasesEndingBetween(TODAY, TODAY.plusDays(30), VaultStatus.AVAILABLE);
    }

    @Test
    void usesParameterWhenDefined() {
        when(parameters.exists(LeaseReminderService.VAULT_LEASE_REMINDER_DAYS)).thenReturn(true);
        when(parameters.getLong(LeaseReminderService.VAULT_LEASE_REMINDER_DAYS)).thenReturn(15L);

        assertEquals(15, service.reminderDays());
    }

    @Test
    void sendsReminderOnceByEmailAndSavesIt() {
        Vault vault = vault("muno@correo.com", TODAY.plusDays(20));
        when(vaults.findLeasesEndingBetween(any(), any(), any())).thenReturn(List.of(vault));

        LeaseReminderService.Result result = service.sendDueReminders();

        assertEquals(1, result.sent());
        verify(email).send(eq("muno@correo.com"), eq(LeaseReminderService.SUBJECT + " · Funeraria El Edén"), anyString());
        ArgumentCaptor<LeaseNotification> saved = ArgumentCaptor.forClass(LeaseNotification.class);
        verify(notices).save(saved.capture());
        LeaseNotification notice = saved.getValue();
        assertEquals(NotificationType.REMINDER, notice.getNotificationType());
        assertEquals(NotificationChannel.EMAIL, notice.getChannel());
        assertEquals(NotificationStatus.SENT, notice.getStatus());
        assertEquals(TODAY.plusDays(20).minusDays(30), notice.getScheduledDate());
        assertEquals("muno@correo.com", notice.getRecipientContact());
        assertTrue(notice.getMessageContent().length() <= LeaseReminderService.MESSAGE_MAX);
    }

    @Test
    void doesNotSendAgainForTheSameExpiry() {
        Vault vault = vault("muno@correo.com", TODAY.plusDays(20));
        when(vaults.findLeasesEndingBetween(any(), any(), any())).thenReturn(List.of(vault));
        LeaseNotification sent = new LeaseNotification();
        sent.setStatus(NotificationStatus.SENT);
        when(notices.findFirstByVaultIdAndNotificationTypeAndScheduledDate(7L, NotificationType.REMINDER,
                TODAY.plusDays(20).minusDays(30))).thenReturn(Optional.of(sent));

        LeaseReminderService.Result result = service.sendDueReminders();

        assertEquals(0, result.sent());
        verify(email, never()).send(anyString(), anyString(), anyString());
        verify(notices, never()).save(any());
    }

    @Test
    void renewedLeaseGetsANewReminderForTheNewExpiry() {
        // El aviso anterior quedó con la fecha del vencimiento viejo; el nuevo vencimiento busca otra fecha.
        Vault vault = vault("muno@correo.com", TODAY.plusDays(10));
        when(vaults.findLeasesEndingBetween(any(), any(), any())).thenReturn(List.of(vault));
        LeaseNotification old = new LeaseNotification();
        old.setStatus(NotificationStatus.SENT);
        when(notices.findFirstByVaultIdAndNotificationTypeAndScheduledDate(7L, NotificationType.REMINDER,
                TODAY.plusDays(10).minusYears(1).minusDays(30))).thenReturn(Optional.of(old));

        assertEquals(1, service.sendDueReminders().sent());
    }

    @Test
    void clientWithoutEmailIsSavedAsFailed() {
        Vault vault = vault(null, TODAY.plusDays(5));
        when(vaults.findLeasesEndingBetween(any(), any(), any())).thenReturn(List.of(vault));

        LeaseReminderService.Result result = service.sendDueReminders();

        assertEquals(1, result.failed());
        verify(email, never()).send(anyString(), anyString(), anyString());
        ArgumentCaptor<LeaseNotification> saved = ArgumentCaptor.forClass(LeaseNotification.class);
        verify(notices).save(saved.capture());
        assertEquals(NotificationStatus.FAILED, saved.getValue().getStatus());
        assertNull(saved.getValue().getRecipientContact());
    }

    @Test
    void failedReminderIsRetriedOnTheSameRecord() {
        Vault vault = vault("muno@correo.com", TODAY.plusDays(5));
        when(vaults.findLeasesEndingBetween(any(), any(), any())).thenReturn(List.of(vault));
        LeaseNotification failed = new LeaseNotification();
        failed.setStatus(NotificationStatus.FAILED);
        when(notices.findFirstByVaultIdAndNotificationTypeAndScheduledDate(any(), any(), any()))
                .thenReturn(Optional.of(failed));

        service.sendDueReminders();

        ArgumentCaptor<LeaseNotification> saved = ArgumentCaptor.forClass(LeaseNotification.class);
        verify(notices).save(saved.capture());
        assertSame(failed, saved.getValue());
        assertEquals(NotificationStatus.SENT, failed.getStatus());
    }

    @Test
    void mailErrorIsSavedAsFailed() {
        Vault vault = vault("muno@correo.com", TODAY.plusDays(5));
        when(vaults.findLeasesEndingBetween(any(), any(), any())).thenReturn(List.of(vault));
        doThrow(new MailSendException("sin conexión")).when(email).send(anyString(), anyString(), anyString());

        assertEquals(1, service.sendDueReminders().failed());
    }

    @Test
    void messageIncludesVaultExpiryAndContactWhenDefined() {
        when(parameters.exists(anyString())).thenReturn(false);
        when(parameters.exists(com.example.funerariamc.service.bot.ChatbotService.FUNERAL_HOME_PHONE)).thenReturn(true);
        when(parameters.getString(com.example.funerariamc.service.bot.ChatbotService.FUNERAL_HOME_PHONE))
                .thenReturn("605 555 0000");

        String text = service.message(vault("muno@correo.com", LocalDate.of(2026, 12, 1)));

        assertTrue(text.contains("A-07"));
        assertTrue(text.contains("1 de diciembre de 2026"));
        assertTrue(text.contains("Teléfono: 605 555 0000"));
        assertTrue(!text.contains("Dirección:"));
    }

    @Test
    void reminderUsesTheConfiguredBrand() {
        BrandingService branding = mock(BrandingService.class);
        when(branding.fullName()).thenReturn("Funeraria Jardines de Paz");
        LeaseReminderService branded = new LeaseReminderService(vaults, notices, parameters, email,
                Clock.fixed(TODAY.atStartOfDay(ZONE).toInstant(), ZONE), branding);
        when(vaults.findLeasesEndingBetween(any(), any(), any())).thenReturn(
                List.of(vault("muno@correo.com", TODAY.plusDays(2))));
        branded.sendDueReminders();
        verify(email).send(eq("muno@correo.com"),
                eq(LeaseReminderService.SUBJECT + " · Funeraria Jardines de Paz"),
                org.mockito.ArgumentMatchers.contains("Funeraria Jardines de Paz"));
    }

    @Test
    void invalidConfiguredDaysUseTheZipDefault() {
        when(parameters.exists(LeaseReminderService.VAULT_LEASE_REMINDER_DAYS)).thenReturn(true);
        when(parameters.getLong(LeaseReminderService.VAULT_LEASE_REMINDER_DAYS)).thenReturn(-1L);
        assertEquals(30, service.reminderDays());
        when(parameters.getLong(LeaseReminderService.VAULT_LEASE_REMINDER_DAYS)).thenReturn(366L);
        assertEquals(30, service.reminderDays());
    }

    @Test
    void failedRetryDoesNotRetainASentTimestamp() {
        LeaseNotification failed = new LeaseNotification();
        failed.setStatus(NotificationStatus.FAILED);
        failed.setSentDate(TODAY.minusDays(1).atStartOfDay());
        when(notices.findFirstByVaultIdAndNotificationTypeAndScheduledDate(any(), any(), any()))
                .thenReturn(Optional.of(failed));
        when(vaults.findLeasesEndingBetween(any(), any(), any())).thenReturn(
                List.of(vault(null, TODAY.plusDays(2))));
        service.sendDueReminders();
        assertNull(failed.getSentDate());
    }

    private static Vault vault(String clientEmail, LocalDate leaseEnd) {
        Client client = new Client();
        client.setName("María");
        client.setLastname("Muñoz");
        client.setEmail(clientEmail);
        Cemetery cemetery = new Cemetery();
        cemetery.setName("Cementerio Central");
        Vault vault = new Vault();
        vault.setId(7L);
        vault.setCode("A-07");
        vault.setClient(client);
        vault.setCemetery(cemetery);
        vault.setStatus(VaultStatus.OCCUPIED);
        vault.setLeaseStartDate(leaseEnd.minusYears(4));
        vault.setLeaseEndDate(leaseEnd);
        return vault;
    }
}
