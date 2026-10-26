package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidResetCodeException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Employee;
import com.example.funerariamc.model.enums.NotificationChannel;
import com.example.funerariamc.model.PasswordResetCode;
import com.example.funerariamc.persistence.AppUserRepository;
import com.example.funerariamc.persistence.PasswordResetCodeRepository;
import com.example.funerariamc.service.notification.EmailNotificationSender;
import com.example.funerariamc.service.notification.TelegramNotificationSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * "¿Olvidó su contraseña?": envía un código de 6 dígitos al Telegram o correo
 * del empleado y cambia la contraseña si el código es correcto.
 * El código se guarda con BCrypt, vence en 10 minutos y admite 5 intentos.
 */
@Service
public class PasswordResetService {

    public static final int MIN_PASSWORD_LENGTH = 8;

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final AppUserRepository appUserRepository;
    private final PasswordResetCodeRepository resetCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessLogService accessLogService;
    private final TelegramNotificationSender telegramSender;
    private final EmailNotificationSender emailSender;
    private final int expirationMinutes;
    private final int maxAttempts;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetService(AppUserRepository appUserRepository,
                                PasswordResetCodeRepository resetCodeRepository,
                                PasswordEncoder passwordEncoder,
                                AccessLogService accessLogService,
                                TelegramNotificationSender telegramSender,
                                EmailNotificationSender emailSender,
                                @Value("${app.password-reset.expiration-minutes:10}") int expirationMinutes,
                                @Value("${app.password-reset.max-attempts:5}") int maxAttempts) {
        if (expirationMinutes <= 0 || maxAttempts <= 0) {
            throw new IllegalArgumentException("La vigencia y los intentos del código deben ser mayores que cero.");
        }
        this.appUserRepository = appUserRepository;
        this.resetCodeRepository = resetCodeRepository;
        this.passwordEncoder = passwordEncoder;
        this.accessLogService = accessLogService;
        this.telegramSender = telegramSender;
        this.emailSender = emailSender;
        this.expirationMinutes = expirationMinutes;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Paso 1 (pantalla 03). Si el usuario no existe, está deshabilitado o no
     * tiene correo ni Telegram, no hace nada: la pantalla siempre dice
     * "Si existe, enviaremos un código", para no revelar qué usuarios hay.
     */
    @Transactional
    public void requestCode(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        Optional<AppUser> found = appUserRepository.findByUsernameWithEmployeeAndRole(username.trim());
        if (found.isEmpty() || !canRecover(found.get())) {
            return;
        }
        AppUser user = found.get();
        Employee employee = user.getEmployee();
        boolean hasEmail = employee.getEmail() != null && !employee.getEmail().isBlank();
        boolean hasTelegram = telegramSender.isConfigured()
                && employee.getTelegramChatId() != null && !employee.getTelegramChatId().isBlank();
        if (!hasEmail && !hasTelegram) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();

        // Solo vale el último código enviado.
        resetCodeRepository.invalidatePending(user.getId(), now);

        String code = "%06d".formatted(random.nextInt(1_000_000));
        String message = "Su código de verificación es " + code
                + ". Vence en " + expirationMinutes + " minutos. Si usted no lo pidió, ignore este mensaje.";

        // Telegram primero; si no hay chat_id o falla, correo.
        NotificationChannel channel = deliver(employee, message);

        PasswordResetCode resetCode = new PasswordResetCode();
        resetCode.setAppUser(user);
        resetCode.setCodeHash(passwordEncoder.encode(code));
        resetCode.setChannel(channel);
        resetCode.setExpiresAt(now.plusMinutes(expirationMinutes));
        resetCode.setAttempts(0);
        resetCode.setCreatedAt(now);
        resetCodeRepository.save(resetCode);

        accessLogService.record(user.getId(), AccessLogService.PASSWORD_RESET_REQUESTED + channel.name());
    }

    /**
     * Paso 2 (pantalla 04). Valida el código y guarda la nueva contraseña con BCrypt.
     * noRollbackFor: el intento fallido debe quedar contado aunque se lance la excepción.
     */
    @Transactional(noRollbackFor = InvalidResetCodeException.class)
    public void resetPassword(String username, String code, String newPassword) {
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw InvalidResetCodeException.weakPassword(MIN_PASSWORD_LENGTH);
        }
        if (username == null || username.isBlank() || code == null || code.isBlank()) {
            throw InvalidResetCodeException.invalidCode();
        }
        AppUser user = appUserRepository.findByUsernameWithEmployeeAndRole(username.trim())
                .orElseThrow(InvalidResetCodeException::expired);
        if (!canRecover(user)) {
            throw InvalidResetCodeException.expired();
        }
        PasswordResetCode resetCode = resetCodeRepository
                .findFirstByAppUser_IdAndUsedAtIsNullOrderByCreatedAtDesc(user.getId())
                .orElseThrow(InvalidResetCodeException::expired);

        LocalDateTime now = LocalDateTime.now();
        if (!now.isBefore(resetCode.getExpiresAt()) || resetCode.getAttempts() >= maxAttempts) {
            resetCode.setUsedAt(now);
            throw InvalidResetCodeException.expired();
        }

        if (!passwordEncoder.matches(code.trim(), resetCode.getCodeHash())) {
            resetCode.setAttempts(resetCode.getAttempts() + 1);
            if (resetCode.getAttempts() >= maxAttempts) {
                resetCode.setUsedAt(now);
            }
            accessLogService.record(user.getId(), AccessLogService.PASSWORD_RESET_FAILED);
            throw InvalidResetCodeException.invalidCode();
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // La contraseña ya fue elegida por el usuario, deja de ser temporal.
        user.setMustChangePassword(false);
        resetCode.setUsedAt(now);
        // Por si quedó otro pendiente: ningún código sirve después del cambio.
        resetCodeRepository.invalidatePending(user.getId(), now);
        accessLogService.record(user.getId(), AccessLogService.PASSWORD_RESET_COMPLETED);
    }

    /** Devuelve el canal utilizado o lanza la excepción de entrega fallida. */
    private NotificationChannel deliver(Employee employee, String message) {
        String chatId = employee.getTelegramChatId();
        if (telegramSender.isConfigured() && chatId != null && !chatId.isBlank()) {
            try {
                telegramSender.send(chatId, message);
                return NotificationChannel.TELEGRAM;
            } catch (RuntimeException e) {
                log.warn("Falló el envío por Telegram ({}).", e.getClass().getSimpleName());
            }
        }
        String email = employee.getEmail();
        if (email != null && !email.isBlank()) {
            try {
                emailSender.send(email, message);
                return NotificationChannel.EMAIL;
            } catch (RuntimeException e) {
                log.warn("Falló el envío por correo ({}).", e.getClass().getSimpleName());
            }
        }
        throw InvalidResetCodeException.deliveryFailed();
    }

    /** Cuenta habilitada y empleado activo; los demás no pueden recuperar acceso. */
    private boolean canRecover(AppUser user) {
        return !Boolean.FALSE.equals(user.getIsEnabled())
                && user.getEmployee() != null && user.getEmployee().isActive();
    }
}
