package com.example.funerariamc.service.notification;

import com.example.funerariamc.config.TelegramProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Envío por Telegram a un chat_id y lectura de los mensajes que le escriben al bot.
 * Si no hay token, quien llama usa el correo (ver {@link #isConfigured()}).
 */
@Component
public class TelegramNotificationSender implements NotificationSender {

    /** Mensaje de texto que llegó al bot. */
    public record Update(long updateId, String chatId, String text) {
    }

    private final TelegramProperties properties;
    private final RestClient restClient;

    @Autowired
    public TelegramNotificationSender(TelegramProperties properties) {
        this(properties, RestClient.create());
    }

    TelegramNotificationSender(TelegramProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /** @param recipient chat_id de Telegram; lanza RestClientException si el envío falla */
    @Override
    public void send(String recipient, String message) {
        sendWithOptions(recipient, message, null);
    }

    /**
     * Envía el texto con botones debajo del cuadro de escribir (como los bots de las empresas).
     * options null deja el teclado como estaba; una lista vacía lo quita.
     */
    public void sendWithOptions(String chatId, String message, List<List<String>> options) {
        if (!isConfigured()) {
            throw new IllegalStateException("El bot de Telegram no está configurado.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", message);
        if (options != null) {
            body.put("reply_markup", options.isEmpty()
                    ? Map.of("remove_keyboard", true)
                    : Map.of("keyboard", keyboard(options), "resize_keyboard", true, "is_persistent", true));
        }
        Map<?, ?> response = restClient.post()
                .uri(properties.sendMessageUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        if (response == null || !Boolean.TRUE.equals(response.get("ok"))) {
            throw new IllegalStateException("Telegram no aceptó el envío del mensaje.");
        }
    }

    /**
     * Espera hasta timeoutSeconds a que alguien le escriba al bot (long polling) y devuelve
     * los mensajes de texto desde offset. Los que no son texto (fotos, stickers) vuelven con text vacío.
     */
    public List<Update> fetchUpdates(long offset, int timeoutSeconds) {
        if (!isConfigured()) {
            throw new IllegalStateException("El bot de Telegram no está configurado.");
        }
        Map<String, Object> body = Map.of("offset", offset, "timeout", timeoutSeconds,
                "allowed_updates", List.of("message"));
        Map<?, ?> response = restClient.post()
                .uri(properties.getUpdatesUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        if (response == null || !Boolean.TRUE.equals(response.get("ok"))) {
            throw new IllegalStateException("Telegram no entregó los mensajes del bot.");
        }
        List<Update> updates = new ArrayList<>();
        if (response.get("result") instanceof List<?> results) {
            for (Object item : results) {
                if (item instanceof Map<?, ?> update) {
                    updates.add(parse(update));
                }
            }
        }
        return updates;
    }

    static Update parse(Map<?, ?> update) {
        long id = ((Number) update.get("update_id")).longValue();
        String chatId = null;
        String text = "";
        if (update.get("message") instanceof Map<?, ?> message) {
            if (message.get("chat") instanceof Map<?, ?> chat && chat.get("id") != null) {
                Object raw = chat.get("id");
                chatId = raw instanceof Number number ? String.valueOf(number.longValue()) : String.valueOf(raw);
            }
            if (message.get("text") instanceof String value) {
                text = value;
            }
        }
        return new Update(id, chatId, text);
    }

    private static List<List<Map<String, String>>> keyboard(List<List<String>> options) {
        return options.stream()
                .map(row -> row.stream().map(label -> Map.of("text", label)).toList())
                .toList();
    }
}
