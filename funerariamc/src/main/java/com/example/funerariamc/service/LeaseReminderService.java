package com.example.funerariamc.service;

import com.example.funerariamc.model.Client;
import com.example.funerariamc.model.LeaseNotification;
import com.example.funerariamc.model.Vault;
import com.example.funerariamc.persistence.LeaseNotificationRepository;
import com.example.funerariamc.persistence.VaultRepository;
import com.example.funerariamc.service.bot.ChatbotService;
import com.example.funerariamc.service.notification.EmailNotificationSender;
import com.example.funerariamc.utils.enums.NotificationChannel;
import com.example.funerariamc.utils.enums.NotificationStatus;
import com.example.funerariamc.utils.enums.NotificationType;
import com.example.funerariamc.utils.enums.VaultStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Recordatorio por correo antes de que venza el arriendo de una bóveda.
 * Se manda una sola vez por cada fecha de vencimiento: la fecha programada del aviso es el
 * vencimiento menos los días de anticipación. Al renovar, el vencimiento cambia y el siguiente
 * recordatorio sale cuando el nuevo vencimiento esté cerca. No hay aviso de mora.
 * Cada aviso queda en lease_notification y se ve en el historial de la bóveda.
 */
@Service
public class LeaseReminderService {

    /** Parámetro con los días de anticipación del recordatorio. */
    public static final String VAULT_LEASE_REMINDER_DAYS = "VAULT_LEASE_REMINDER_DAYS";
    /** Anticipación acordada con la funeraria mientras el parámetro no tenga valor. */
    public static final int DEFAULT_REMINDER_DAYS = 30;
    /** Largo máximo de la columna recipient_contact. */
    static final int CONTACT_MAX = 100;
    /** Largo máximo de la columna message_content; el correo sí lleva el texto completo. */
    static final int MESSAGE_MAX = 300;
    private static final Logger log = LoggerFactory.getLogger(LeaseReminderService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy",
            Locale.forLanguageTag("es-CO"));
    static final String SUBJECT = "El arriendo de su bóveda vence pronto";
    private final VaultRepository vaultRepository;
    private final LeaseNotificationRepository notificationRepository;
    private final SystemParameterService parameters;
    private final EmailNotificationSender email;
    private final Clock clock;
    private final BrandingService brandingService;

    public record Result(int sent, int failed) { }

    @Autowired
    public LeaseReminderService(VaultRepository vaultRepository, LeaseNotificationRepository notificationRepository,
                                SystemParameterService parameters, EmailNotificationSender email, BrandingService brandingService) {
        this(vaultRepository, notificationRepository, parameters, email, Clock.system(AccessLogService.APP_ZONE), brandingService);
    }

    public LeaseReminderService(VaultRepository vaultRepository, LeaseNotificationRepository notificationRepository,
                                SystemParameterService parameters, EmailNotificationSender email) {
        this(vaultRepository, notificationRepository, parameters, email, Clock.system(AccessLogService.APP_ZONE), null);
    }

    LeaseReminderService(VaultRepository vaultRepository, LeaseNotificationRepository notificationRepository,
                         SystemParameterService parameters, EmailNotificationSender email, Clock clock) {
        this(vaultRepository, notificationRepository, parameters, email, clock, null);
    }

    LeaseReminderService(VaultRepository vaultRepository, LeaseNotificationRepository notificationRepository,
                         SystemParameterService parameters, EmailNotificationSender email, Clock clock,
                         BrandingService brandingService) {
        this.vaultRepository = vaultRepository;
        this.notificationRepository = notificationRepository;
        this.parameters = parameters;
        this.email = email;
        this.clock = clock;
        this.brandingService = brandingService;
    }

    /**
     * Revisa las bóvedas cuyo arriendo vence dentro de los días de anticipación y manda el
     * recordatorio a las que aún no lo tienen enviado para ese vencimiento.
     * Un aviso fallido (sin correo o error de envío) se reintenta en la siguiente revisión
     * sobre el mismo registro, sin crear otro.
     */
    @Transactional
    public Result sendDueReminders() {
        LocalDate today = LocalDate.now(clock);
        int days = reminderDays();
        List<Vault> vaults = vaultRepository.findLeasesEndingBetween(today, today.plusDays(days), VaultStatus.AVAILABLE);
        int sent = 0;
        int failed = 0;
        for (Vault vault : vaults) {
            LocalDate scheduled = vault.getLeaseEndDate().minusDays(days);
            Optional<LeaseNotification> previous = notificationRepository
                    .findFirstByVaultIdAndNotificationTypeAndScheduledDate(vault.getId(), NotificationType.REMINDER,
                            scheduled);
            if (previous.isPresent() && previous.get().getStatus() == NotificationStatus.SENT) {
                continue;
            }
            LeaseNotification notice = previous.orElseGet(LeaseNotification::new);
            notice.setVault(vault);
            notice.setNotificationType(NotificationType.REMINDER);
            notice.setChannel(NotificationChannel.EMAIL);
            notice.setScheduledDate(scheduled);
            if (deliver(vault, notice)) {
                sent++;
            } else {
                failed++;
            }
            notificationRepository.save(notice);
        }
        return new Result(sent, failed);
    }

    /** Envía el correo y deja en el aviso el destinatario, el texto y el resultado. */
    private boolean deliver(Vault vault, LeaseNotification notice) {
        Client client = vault.getClient();
        notice.setSentDate(null);
        String recipient = client == null || client.getEmail() == null ? "" : client.getEmail().trim();
        String message = message(vault);
        notice.setMessageContent(message.length() > MESSAGE_MAX ? message.substring(0, MESSAGE_MAX) : message);
        if (recipient.isEmpty()) {
            notice.setRecipientContact(null);
            notice.setStatus(NotificationStatus.FAILED);
            return false;
        }
        notice.setRecipientContact(recipient.length() > CONTACT_MAX ? recipient.substring(0, CONTACT_MAX) : recipient);
        try {
            email.send(recipient, SUBJECT + " · " + BrandingService.fullNameOf(brandingService), message);
            notice.setStatus(NotificationStatus.SENT);
            notice.setSentDate(LocalDateTime.now(clock));
            return true;
        } catch (RuntimeException failure) {
            log.warn("No se pudo enviar el recordatorio de la bóveda {} ({}).", vault.getCode(),
                    failure.getClass().getSimpleName());
            notice.setStatus(NotificationStatus.FAILED);
            return false;
        }
    }

    /** Texto del correo; los datos de contacto salen de Parámetros solo si están definidos. */
    String message(Vault vault) {
        Client client = vault.getClient();
        String name = client == null ? "" : String.join(" ", nonBlank(client.getName()), nonBlank(client.getLastname())).trim();
        StringBuilder text = new StringBuilder();
        text.append("Estimado(a) ").append(name.isEmpty() ? "titular" : name).append(":\n\n");
        text.append("Le recordamos que el arriendo de la bóveda ").append(vault.getCode());
        if (vault.getCemetery() != null && vault.getCemetery().getName() != null) {
            text.append(" en ").append(vault.getCemetery().getName());
        }
        text.append(" vence el ").append(DATE.format(vault.getLeaseEndDate())).append(".\n");
        text.append("Para renovarlo, acérquese a la sede de ").append(BrandingService.fullNameOf(brandingService))
                .append(" antes de esa fecha.\n");
        String address = contact(ChatbotService.FUNERAL_HOME_ADDRESS);
        String phone = contact(ChatbotService.FUNERAL_HOME_PHONE);
        String hours = contact(ChatbotService.FUNERAL_HOME_HOURS);
        if (address != null || phone != null || hours != null) {
            text.append('\n');
            if (address != null) {
                text.append("Dirección: ").append(address).append('\n');
            }
            if (phone != null) {
                text.append("Teléfono: ").append(phone).append('\n');
            }
            if (hours != null) {
                text.append("Horario: ").append(hours).append('\n');
            }
        }
        text.append('\n').append(BrandingService.fullNameOf(brandingService));
        return text.toString();
    }

    /** Días de anticipación: el parámetro si está definido y es válido; si no, 30. */
    int reminderDays() {
        try {
            if (parameters.exists(VAULT_LEASE_REMINDER_DAYS)) {
                long days = parameters.getLong(VAULT_LEASE_REMINDER_DAYS);
                if (days >= 0 && days <= 365) {
                    return (int) days;
                }
            }
        } catch (RuntimeException failure) {
            log.warn("El parámetro {} no es válido; se usan {} días.", VAULT_LEASE_REMINDER_DAYS, DEFAULT_REMINDER_DAYS);
        }
        return DEFAULT_REMINDER_DAYS;
    }

    private String contact(String name) {
        try {
            if (!parameters.exists(name)) {
                return null;
            }
            String value = parameters.getString(name);
            return value == null || value.isBlank() ? null : value.trim();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static String nonBlank(String value) {
        return value == null ? "" : value.trim();
    }
}
