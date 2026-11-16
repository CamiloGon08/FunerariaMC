package com.example.funerariamc.service.bot;

import com.example.funerariamc.model.Client;
import com.example.funerariamc.model.ClientVerificationCode;
import com.example.funerariamc.persistence.ClientVerificationCodeRepository;
import com.example.funerariamc.service.AccessLogService;
import com.example.funerariamc.service.notification.EmailNotificationSender;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Código de 6 dígitos que el bot envía al correo del titular antes de mostrarle su plan.
 * Igual que la recuperación de contraseña: se guarda con BCrypt, vence en los mismos minutos
 * y admite los mismos intentos (app.password-reset.* en application.properties).
 */
@Service
public class BotVerificationService {

    /** Resultado de revisar el código que escribió el titular. */
    public enum Outcome {
        VALID,
        WRONG,
        EXHAUSTED,
        EXPIRED
    }

    public record Check(Outcome outcome, Long clientId, int attemptsLeft) {
    }

    private final ClientVerificationCodeRepository codeRepository;
    private final EmailNotificationSender emailSender;
    private final PasswordEncoder passwordEncoder;
    private final int expirationMinutes;
    private final int maxAttempts;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public BotVerificationService(ClientVerificationCodeRepository codeRepository, EmailNotificationSender emailSender,
                                  PasswordEncoder passwordEncoder,
                                  @Value("${app.password-reset.expiration-minutes:10}") int expirationMinutes,
                                  @Value("${app.password-reset.max-attempts:5}") int maxAttempts) {
        this(codeRepository, emailSender, passwordEncoder, expirationMinutes, maxAttempts,
                Clock.system(AccessLogService.APP_ZONE));
    }

    BotVerificationService(ClientVerificationCodeRepository codeRepository, EmailNotificationSender emailSender,
                           PasswordEncoder passwordEncoder, int expirationMinutes, int maxAttempts, Clock clock) {
        if (expirationMinutes <= 0 || maxAttempts <= 0) {
            throw new IllegalArgumentException("La vigencia y los intentos del código deben ser mayores que cero.");
        }
        this.codeRepository = codeRepository;
        this.emailSender = emailSender;
        this.passwordEncoder = passwordEncoder;
        this.expirationMinutes = expirationMinutes;
        this.maxAttempts = maxAttempts;
        this.clock = clock;
    }

    public int expirationMinutes() {
        return expirationMinutes;
    }

    /**
     * Envía un código nuevo al correo del titular y anula los anteriores del chat.
     * Lanza la excepción del correo si no se pudo enviar; en ese caso no queda código guardado.
     */
    @Transactional
    public void sendCode(String chatId, Client client) {
        LocalDateTime now = LocalDateTime.now(clock);
        codeRepository.invalidatePending(chatId, now);
        String code = "%06d".formatted(random.nextInt(1_000_000));
        emailSender.send(client.getEmail(), "Código para consultar su plan · Funeraria El Edén",
                "Hola, " + client.getName() + ".\n\n"
                        + "Su código para consultar el plan exequial en Telegram es " + code + ".\n"
                        + "Vence en " + expirationMinutes + " minutos. Si usted no lo pidió, ignore este correo.\n\n"
                        + "Funeraria El Edén");
        ClientVerificationCode saved = new ClientVerificationCode();
        saved.setClient(client);
        saved.setChatId(chatId);
        saved.setCodeHash(passwordEncoder.encode(code));
        saved.setExpiresAt(now.plusMinutes(expirationMinutes));
        saved.setAttempts(0);
        saved.setCreatedAt(now);
        codeRepository.save(saved);
    }

    /** noRollbackFor no hace falta: los intentos fallidos se guardan sin lanzar excepción. */
    @Transactional
    public Check verify(String chatId, String typed) {
        Optional<ClientVerificationCode> found = codeRepository.findFirstByChatIdAndUsedAtIsNullOrderByCreatedAtDesc(chatId);
        if (found.isEmpty()) {
            return new Check(Outcome.EXPIRED, null, 0);
        }
        ClientVerificationCode code = found.get();
        LocalDateTime now = LocalDateTime.now(clock);
        if (code.getUsedAt() != null || !now.isBefore(code.getExpiresAt()) || code.getAttempts() >= maxAttempts) {
            code.setUsedAt(now);
            return new Check(Outcome.EXPIRED, null, 0);
        }
        String clean = typed == null ? "" : typed.replaceAll("\\s", "");
        if (clean.matches("\\d{6}") && passwordEncoder.matches(clean, code.getCodeHash())) {
            code.setUsedAt(now);
            return new Check(Outcome.VALID, code.getClient().getId(), 0);
        }
        code.setAttempts(code.getAttempts() + 1);
        int left = maxAttempts - code.getAttempts();
        if (left <= 0) {
            code.setUsedAt(now);
            return new Check(Outcome.EXHAUSTED, null, 0);
        }
        return new Check(Outcome.WRONG, null, left);
    }

    /** "m***s@gmail.com": el bot dice a dónde llegó el código sin mostrar el correo completo. */
    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "su correo registrado";
        }
        int at = email.indexOf('@');
        if (at <= 0 || at == email.length() - 1) {
            return "su correo registrado";
        }
        String user = email.substring(0, at);
        String shown = user.length() <= 2 ? user.substring(0, 1) + "***"
                : user.charAt(0) + "***" + user.charAt(user.length() - 1);
        return shown + email.substring(at);
    }
}
