package com.example.funerariamc.service;

import com.example.funerariamc.config.OpenRouteServiceProperties;
import com.example.funerariamc.service.TransportService.Stop;
import com.example.funerariamc.service.TransportService.Transfer;
import com.example.funerariamc.service.bot.ChatbotService;
import com.example.funerariamc.utils.enums.StopType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    /**
     * Resultados demasiado generales (un país, un departamento, una ciudad entera). Si la búsqueda solo
     * encuentra "Valledupar", el punto caería en el centro de la ciudad y la ruta sería falsa.
     */
    private static final Set<String> REGION_LAYERS = Set.of("country", "macroregion", "region", "macrocounty",
            "county");
    /** Un pueblo entero: sirve para una parada en otro municipio, no para una dentro de la ciudad. */
    private static final Set<String> TOWN_LAYERS = Set.of("localadmin", "locality", "borough");

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
    public record MapData(List<Stop> stops, Route route, List<String> notFound, String problem) {

        public MapData(List<Stop> stops, Route route, List<String> notFound) {
            this(stops, route, notFound, null);
        }
    }

    /** Resultado de una búsqueda: el punto, o el motivo por el que el servicio no respondió. */
    private record Lookup(Optional<Point> point, String problem) {
    }

    /** La clave fue rechazada o no hubo conexión: no se sigue buscando el resto de paradas. */
    static final String KEY_REJECTED = "OpenRouteService rechazó la clave del mapa (revise ORS_API_KEY)";
    static final String NO_CONNECTION = "no hubo conexión con el servicio del mapa";

    private final OpenRouteServiceProperties properties;
    private final TransportService transportService;
    private final SystemParameterService parameterService;
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
    public RouteMapService(OpenRouteServiceProperties properties, TransportService transportService,
                           SystemParameterService parameterService) {
        this(properties, transportService, parameterService, RestClient.create(OpenRouteServiceProperties.API_URL));
    }

    /** Las pruebas pasan un RestClient falso. */
    RouteMapService(OpenRouteServiceProperties properties, TransportService transportService,
                    SystemParameterService parameterService, RestClient restClient) {
        this.properties = properties;
        this.transportService = transportService;
        this.parameterService = parameterService;
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
        String problem = null;
        for (Stop s : transfer.stops()) {
            if (s.located()) {
                stops.add(s);
                continue;
            }
            Lookup found = isConfigured() && problem == null ? geocode(s) : new Lookup(Optional.empty(), null);
            problem = problem == null ? found.problem() : problem;
            if (found.point().isPresent()) {
                Point p = found.point().get();
                transportService.saveCoordinates(transfer.transportIds(), s.order(), p.lat(), p.lon());
                stops.add(new Stop(s.id(), s.order(), s.type(), s.placeName(), s.address(), s.plannedAt(),
                        s.arrivedAt(), p.lat(), p.lon()));
            } else if (isConfigured() && isHeadquarters(s) && blankToNull(s.address()) == null
                    && headquartersAddress() == null) {
                notFound.add(s.placeName() + " (registre la Dirección de la sede en Parámetros)");
            } else {
                notFound.add(s.placeName());
            }
        }
        Route route = stops.size() >= 2 && isConfigured()
                ? route(stops.stream().map(s -> new Point(s.latitude(), s.longitude())).toList())
                : null;
        return new MapData(stops, route, notFound, problem);
    }

    /**
     * Busca la parada con varios textos, del más preciso al más general: nombre y dirección, solo la
     * dirección y solo el nombre, siempre dentro de la ciudad. La sede sin dirección usa la dirección
     * guardada en Parámetros (Dirección de la sede). Se descartan los resultados que solo señalan la
     * ciudad, porque pondrían la parada en el centro de Valledupar.
     */
    Lookup geocode(Stop stop) {
        for (String text : searchTexts(stop)) {
            try {
                // La clave y el texto van como variables de la plantilla: RestClient los codifica una sola vez.
                // Si se codifican antes, el "=" final de la clave llega como "%253D" y el servicio la rechaza.
                String body = restClient.get()
                        .uri("/geocode/search?api_key={key}&text={text}&boundary.country=CO"
                                        + "&focus.point.lat={lat}&focus.point.lon={lon}&size=5",
                                properties.apiKey(), text, FOCUS_LAT, FOCUS_LON)
                        .retrieve()
                        .body(String.class);
                Optional<Point> point = parseGeocode(body);
                if (point.isPresent()) {
                    return new Lookup(point, null);
                }
            } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
                return new Lookup(Optional.empty(), KEY_REJECTED);
            } catch (HttpClientErrorException e) {
                // Texto que el servicio no acepta: se prueba el siguiente.
            } catch (RestClientException e) {
                return new Lookup(Optional.empty(), NO_CONNECTION);
            } catch (IllegalArgumentException | JacksonException e) {
                // Respuesta incompleta: se prueba el siguiente texto.
            }
        }
        return new Lookup(Optional.empty(), null);
    }

    /** Textos de búsqueda sin repetir, cada uno con la ciudad al final si no la trae. */
    List<String> searchTexts(Stop stop) {
        String city = properties.cityOrDefault();
        String name = blankToNull(stop.placeName());
        String address = blankToNull(stop.address());
        if (address == null && isHeadquarters(stop)) {
            address = headquartersAddress();
            name = null; // "Sede El Edén · Sala Eden" no existe en el mapa; la dirección sí
        }
        if (address != null && isJustTheCity(address, city)) {
            address = null; // "Valledupar, Cesar" no dice dónde queda la parada
        }
        // Si la dirección ya trae su lugar ("Cra. 12 #8-40, Valledupar" o "Bosconia, Cesar") no se le
        // agrega la ciudad; si no, se busca dentro de la ciudad configurada.
        String place = address != null && address.contains(",") ? "" : ", " + city;
        Set<String> texts = new LinkedHashSet<>();
        if (name != null && address != null) {
            texts.add(name + ", " + address + place);
        }
        if (address != null) {
            texts.add(address + place);
        }
        if (name != null) {
            texts.add(name + ", " + city);
        }
        return new ArrayList<>(texts);
    }

    private static boolean isHeadquarters(Stop stop) {
        String name = stop.placeName() == null ? "" : plain(stop.placeName());
        return name.startsWith("sede") || (stop.type() == StopType.ORIGIN && name.isBlank());
    }

    private String headquartersAddress() {
        if (parameterService == null) {
            return null;
        }
        try {
            String address = blankToNull(parameterService.getString(ChatbotService.FUNERAL_HOME_ADDRESS));
            // El script del bot deja "ESCRIBE AQUÍ LA DIRECCIÓN" hasta que alguien la llena.
            return address == null || plain(address).startsWith("escribe aqui") ? null : address;
        } catch (RuntimeException missing) {
            return null; // el parámetro aún no está registrado
        }
    }

    /** "Valledupar", "Valledupar, Cesar" o "Valledupar - Cesar": solo la ciudad. */
    static boolean isJustTheCity(String address, String city) {
        String a = words(address);
        String c = words(city);
        String town = town(city);
        return a.equals(c) || a.equals(town) || a.equals(town + " colombia") || a.equals(c + " colombia");
    }

    /** Nombre de la ciudad sin el departamento: "Valledupar, Cesar" → "valledupar". */
    private static String town(String city) {
        String first = city.contains(",") ? city.substring(0, city.indexOf(',')) : city;
        return words(first);
    }

    private static String words(String text) {
        return plain(text).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String plain(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
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
        // radiuses -1: si un punto queda lejos de una calle (una finca, el fondo de un cementerio), la ruta
        // arranca en la calle más cercana en vez de fallar.
        coordinates.append("],\"radiuses\":[");
        for (int i = 0; i < points.size(); i++) {
            coordinates.append(i == 0 ? "" : ",").append("-1");
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
        String town = town(properties.cityOrDefault());
        for (JsonNode feature : features) {
            JsonNode props = feature.path("properties");
            String layer = props.path("layer").asString("");
            boolean region = REGION_LAYERS.contains(layer);
            boolean ownCity = TOWN_LAYERS.contains(layer) && plain(props.path("name").asString("")).equals(town);
            if (!region && !ownCity) {
                return point(feature.path("geometry").path("coordinates"));
            }
        }
        return Optional.empty();
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
}
