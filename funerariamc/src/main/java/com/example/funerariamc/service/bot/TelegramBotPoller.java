package com.example.funerariamc.service.bot;

import com.example.funerariamc.config.TelegramProperties;
import com.example.funerariamc.service.AccessLogService;
import com.example.funerariamc.service.notification.TelegramNotificationSender;
import com.example.funerariamc.service.notification.TelegramNotificationSender.Update;
import com.example.funerariamc.utils.enums.BotTopic;
import com.example.funerariamc.utils.enums.NotificationChannel;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Escucha el chat del bot mientras la app está abierta (long polling de Telegram: una petición
 * que espera hasta 25 segundos a que alguien escriba). Corre en su propio hilo, nunca en el de la
 * pantalla. Solo arranca en el equipo con TELEGRAM_BOT_POLLING=true, para que dos computadores
 * no respondan el mismo chat.
 */
@Component
public class TelegramBotPoller {

    /** Estado que muestra la pantalla "Mensajes del bot". */
    public enum State {
        NOT_CONFIGURED,
        OTHER_COMPUTER,
        RUNNING,
        RETRYING
    }

    public record Status(State state, LocalDateTime lastCheck) {
    }

    private static final Logger log = LoggerFactory.getLogger(TelegramBotPoller.class);
    private static final int WAIT_SECONDS = 25;
    private static final long RETRY_MILLIS = 5_000;
    /** Telegram responde 409 si otro equipo ya está escuchando el mismo bot. */
    private static final long CONFLICT_RETRY_MILLIS = 30_000;
    private final TelegramProperties properties;
    private final TelegramNotificationSender telegram;
    private final ChatbotService chatbot;
    private final BotMessageService messages;
    private volatile boolean running;
    private volatile boolean failing;
    private volatile LocalDateTime lastCheck;
    private Thread thread;

    public TelegramBotPoller(TelegramProperties properties, TelegramNotificationSender telegram,
                             ChatbotService chatbot, BotMessageService messages) {
        this.properties = properties;
        this.telegram = telegram;
        this.chatbot = chatbot;
        this.messages = messages;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!properties.shouldPoll() || running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "telegram-bot");
        thread.setDaemon(true);
        thread.start();
        log.info("Bot de Telegram escuchando en este equipo.");
    }

    @PreDestroy
    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    public Status status() {
        if (!properties.isConfigured()) {
            return new Status(State.NOT_CONFIGURED, null);
        }
        if (!properties.polling()) {
            return new Status(State.OTHER_COMPUTER, null);
        }
        return new Status(failing ? State.RETRYING : State.RUNNING, lastCheck);
    }

    private void loop() {
        long offset = 0;
        while (running) {
            try {
                List<Update> updates = telegram.fetchUpdates(offset, WAIT_SECONDS);
                failing = false;
                lastCheck = LocalDateTime.now(AccessLogService.APP_ZONE);
                for (Update update : updates) {
                    offset = Math.max(offset, update.updateId() + 1);
                    if (update.chatId() != null) {
                        attend(update);
                    }
                }
            } catch (HttpClientErrorException.Conflict conflict) {
                failing = true;
                log.warn("Otro equipo está escuchando el mismo bot; se reintenta en 30 segundos.");
                pause(CONFLICT_RETRY_MILLIS);
            } catch (RuntimeException failure) {
                failing = true;
                log.warn("No se pudo leer el chat del bot ({}); se reintenta.", failure.getClass().getSimpleName());
                pause(RETRY_MILLIS);
            }
        }
    }

    /** Un mensaje: se guarda, se responde y se guarda cada respuesta con su resultado. */
    void attend(Update update) {
        String chatId = update.chatId();
        String text = update.text() == null || update.text().isBlank() ? "" : update.text();
        ChatbotService.Answer answer = text.isEmpty()
                ? new ChatbotService.Answer("(mensaje sin texto)", BotTopic.UNKNOWN,
                null, List.of(new ChatbotService.Reply("Por ahora solo entiendo mensajes de texto. Escriba su"
                + " cédula o el número de una opción.", BotTopic.UNKNOWN, null)))
                : chatbot.handle(chatId, text);
        try {
            messages.incoming(chatId, answer.clientId(), answer.loggedText(), answer.incomingTopic());
        } catch (RuntimeException failure) {
            // El commit de REQUIRES_NEW también puede fallar fuera del cuerpo del servicio.
            log.warn("No se pudo registrar el mensaje recibido ({}).", failure.getClass().getSimpleName());
        }
        for (ChatbotService.Reply reply : answer.replies()) {
            boolean sent;
            try {
                telegram.sendWithOptions(chatId, reply.text(), reply.options());
                sent = true;
            } catch (RuntimeException failure) {
                log.warn("Telegram no recibió la respuesta del bot ({}).", failure.getClass().getSimpleName());
                sent = false;
            }
            try {
                messages.outgoing(chatId, answer.clientId(), NotificationChannel.TELEGRAM, reply.text(), reply.topic(), sent);
            } catch (RuntimeException failure) {
                log.warn("No se pudo registrar la respuesta del bot ({}).", failure.getClass().getSimpleName());
            }
        }
    }

    private void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
