package com.example.funerariamc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Clave de OpenRouteService para el mapa de Transporte (buscar coordenadas y calcular rutas).*/
@ConfigurationProperties(prefix = "ors")
public record OpenRouteServiceProperties(String apiKey, String city) {

    public static final String API_URL = "https://api.openrouteservice.org";

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String cityOrDefault() {
        return city == null || city.isBlank() ? "Valledupar, Cesar" : city.trim();
    }
}
