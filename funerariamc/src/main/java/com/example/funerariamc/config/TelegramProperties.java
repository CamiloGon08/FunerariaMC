package com.example.funerariamc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Datos del bot de Telegram de la funeraria (credentials.properties).
 * token: el que entrega BotFather. link: enlace público del bot para el correo y el QR.
 * polling: true solo en el computador que debe responder el chat, para que dos equipos no se peleen los mensajes.
 */
@ConfigurationProperties(prefix = "telegram.bot")
public record TelegramProperties(String token, String link, boolean polling) {

    private static final String API_URL = "https://api.telegram.org/bot";

    @ConstructorBinding
    public TelegramProperties {
    }

    /** Solo con el token (pruebas y quien aún no usa el chat). */
    public TelegramProperties(String token) {
        this(token, null, false);
    }

    public boolean isConfigured() {
        return token != null && !token.isBlank();
    }

    public boolean hasLink() {
        return link != null && !link.isBlank();
    }

    /** El chat solo se atiende si hay token y este equipo es el elegido. */
    public boolean shouldPoll() {
        return isConfigured() && polling;
    }

    public String sendMessageUrl() {
        return API_URL + token + "/sendMessage";
    }

    public String getUpdatesUrl() {
        return API_URL + token + "/getUpdates";
    }
}
