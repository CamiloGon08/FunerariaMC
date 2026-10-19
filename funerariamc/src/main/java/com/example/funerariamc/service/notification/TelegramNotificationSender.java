package com.example.funerariamc.service.notification;

import com.example.funerariamc.config.TelegramProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * ACT-06: envío por Telegram a {@code person.telegram_chat_id}.
 * Si no hay token, quien llama usa el correo (ver {@link #isConfigured()}).
 */
@Component
public class TelegramNotificationSender implements NotificationSender {

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
        if (!isConfigured()) {
            throw new IllegalStateException("El bot de Telegram no está configurado.");
        }
        Map<?, ?> response = restClient.post()
                .uri(properties.sendMessageUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("chat_id", recipient, "text", message))
                .retrieve()
                .body(Map.class);
        if (response == null || !Boolean.TRUE.equals(response.get("ok"))) {
            throw new IllegalStateException("Telegram no aceptó el envío del mensaje.");
        }
    }
}
