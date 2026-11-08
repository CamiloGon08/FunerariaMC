package com.example.funerariamc.service;

import com.example.funerariamc.config.OpenRouteServiceProperties;
import com.example.funerariamc.service.TransportService.Stop;
import com.example.funerariamc.service.TransportService.Transfer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mapa de Transporte con OpenRouteService: busca las coordenadas de cada parada una sola vez (quedan
 * guardadas en la parada) y calcula la ruta por las calles entre las paradas. Las rutas se guardan en
 * memoria para no repetir la consulta mientras la app está abierta.
 * Se llama fuera del hilo de la pantalla: si no hay clave o la consulta falla, la pantalla sigue con
 * el recorrido por paradas.
 */
@Service
public class RouteMapService {

    /** Centro de la búsqueda: Valledupar. Ayuda a que "Parroquia San José" no salga en otra ciudad. */
    static final double FOCUS_LAT = 10.4631;
    static final double FOCUS_LON = -73.2532;
    private static final int MAX_CACHED_ROUTES = 100;

    /** Punto del mapa (latitud, longitud). */
    public record Point(double lat, double lon) {
    }

    /** Ruta calculada: la línea por las calles, distancia en km y minutos de manejo. */
    public record Route(List<Point> line, double km, int minutes) {
    }

    /**
     * Lo que dibuja el mapa: las paradas con coordenadas, la ruta (null si no se pudo calcular; se
     * dibuja una línea recta punteada) y las paradas que no se encontraron.
     */
    public record MapData(List<Stop> stops, Route route, List<String> notFound) {
    }

    private final OpenRouteServiceProperties properties;
    private final TransportService transportService;
    private final RestClient restClient;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, Route> routeCache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Route> eldest) {
                    return size() > MAX_CACHED_ROUTES;
                }
            });

    @Autowired
    public RouteMapService(OpenRouteServiceProperties properties, TransportService transportService) {
        this(properties, transportService, RestClient.create(OpenRouteServiceProperties.API_URL));
    }

    /** Las pruebas pasan un RestClient falso. */
    RouteMapService(OpenRouteServiceProperties properties, TransportService transportService, RestClient restClient) {
        this.properties = properties;
        this.transportService = transportService;
        this.restClient = restClient;
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /**
     * Prepara el mapa de un traslado: busca las paradas que aún no tienen coordenadas, las guarda y
     * calcula la ruta. Hace consultas por internet: llamarlo desde un Task, nunca en el hilo de JavaFX.
     */
    public MapData prepare(Transfer transfer) {
        List<Stop> stops = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        for (Stop s : transfer.stops()) {
            if (s.located()) {
                stops.add(s);
                continue;
            }
            Optional<Point> found = isConfigured() ? geocode(s) : Optional.empty();
            if (found.isPresent()) {
                Point p = found.get();
                transportService.saveCoordinates(transfer.transportIds(), s.order(), p.lat(), p.lon());
                stops.add(new Stop(s.id(), s.order(), s.type(), s.placeName(), s.address(), s.plannedAt(),
                        s.arrivedAt(), p.lat(), p.lon()));
            } else {
                notFound.add(s.placeName());
            }
        }
        Route route = stops.size() >= 2 && isConfigured()
                ? route(stops.stream().map(s -> new Point(s.latitude(), s.longitude())).toList())
                : null;
        return new MapData(stops, route, notFound);
    }

    /** Busca la parada por su dirección (o por su nombre si no tiene) dentro de la ciudad. */
    Optional<Point> geocode(Stop stop) {
        String text = (stop.address() == null || stop.address().isBlank() ? stop.placeName() : stop.address())
                + ", " + properties.cityOrDefault();
        try {
            String body = restClient.get()
                    .uri("/geocode/search?api_key=" + enc(properties.apiKey()) + "&text=" + enc(text)
                            + "&boundary.country=CO&focus.point.lat=" + FOCUS_LAT + "&focus.point.lon=" + FOCUS_LON
                            + "&size=1")
                    .retrieve()
                    .body(String.class);
            return parseGeocode(body);
        } catch (RestClientException | IllegalArgumentException | JacksonException e) {
            return Optional.empty();
        }
    }

    /** Ruta en carro por las calles entre los puntos, en orden. null si no se pudo calcular. */
    Route route(List<Point> points) {
        String key = points.toString();
        Route cached = routeCache.get(key);
        if (cached != null) {
            return cached;
        }
        StringBuilder coordinates = new StringBuilder("{\"coordinates\":[");
        for (int i = 0; i < points.size(); i++) {
            Point p = points.get(i);
            coordinates.append(i == 0 ? "" : ",").append('[').append(p.lon()).append(',').append(p.lat()).append(']');
        }
        coordinates.append("]}");
        try {
            String body = restClient.post()
                    .uri("/v2/directions/driving-car/geojson")
                    .header("Authorization", properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(coordinates.toString())
                    .retrieve()
                    .body(String.class);
            Route route = parseRoute(body);
            if (route != null) {
                routeCache.put(key, route);
            }
            return route;
        } catch (RestClientException | IllegalArgumentException | JacksonException e) {
            return null;
        }
    }

    /** Primer resultado de la búsqueda (GeoJSON: las coordenadas vienen como [longitud, latitud]). */
    Optional<Point> parseGeocode(String body) {
        if (body == null) {
            return Optional.empty();
        }
        JsonNode root = json.readTree(body);
        if (root == null) {
            return Optional.empty();
        }
        JsonNode features = root.path("features");
        if (!features.isArray() || features.isEmpty()) {
            return Optional.empty();
        }
        return point(features.get(0).path("geometry").path("coordinates"));
    }

    /** Línea de la ruta, distancia (metros a km) y duración (segundos a minutos). */
    Route parseRoute(String body) {
        if (body == null) {
            return null;
        }
        JsonNode root = json.readTree(body);
        if (root == null) {
            return null;
        }
        JsonNode feature = root.path("features").path(0);
        JsonNode coordinates = feature.path("geometry").path("coordinates");
        if (!coordinates.isArray() || coordinates.size() < 2) {
            return null;
        }
        List<Point> line = new ArrayList<>();
        for (JsonNode c : coordinates) {
            Optional<Point> point = point(c);
            if (point.isEmpty()) {
                return null;
            }
            line.add(point.get());
        }
        JsonNode summary = feature.path("properties").path("summary");
        double km = Math.round(summary.path("distance").asDouble() / 100.0) / 10.0;
        int minutes = (int) Math.round(summary.path("duration").asDouble() / 60.0);
        return new Route(line, km, minutes);
    }

    private static Optional<Point> point(JsonNode coordinates) {
        if (!coordinates.isArray() || coordinates.size() < 2
                || !coordinates.get(0).isNumber() || !coordinates.get(1).isNumber()) {
            return Optional.empty();
        }
        double lon = coordinates.get(0).asDouble();
        double lat = coordinates.get(1).asDouble();
        if (!Double.isFinite(lat) || !Double.isFinite(lon)
                || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            return Optional.empty();
        }
        return Optional.of(new Point(lat, lon));
    }

    private static String enc(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
