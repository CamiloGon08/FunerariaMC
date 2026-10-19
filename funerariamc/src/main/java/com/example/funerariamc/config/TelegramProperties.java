package com.example.funerariamc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

//*Para implementar un bot de Telegram enfocado en recordatorios de alquiler y avisos de facturas vencidas  */

@ConfigurationProperties(prefix = "telegram.bot")
public record TelegramProperties(String token) {

    private static final String API_URL = "https://api.telegram.org/bot";

    public boolean isConfigured(){
        return token != null && !token.isBlank();
    }

    public String sendMessageUrl() {
        return API_URL + token + "/sendMessage";
    }
}
