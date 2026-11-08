package com.example.funerariamc.controller;

import com.example.funerariamc.service.RouteMapService.MapData;
import com.example.funerariamc.model.enums.TransportStatus;
import com.example.funerariamc.model.enums.VehicleType;
import com.example.funerariamc.service.TransportService.Stop;
import com.example.funerariamc.service.TransportService.Transfer;
import com.example.funerariamc.service.TransportService.Unit;
import javafx.concurrent.Worker;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import tools.jackson.databind.json.JsonMapper;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mapa de un traslado dentro de la pantalla de Transporte: una WebView con la página
 * web/transport-map.html (Leaflet + OpenStreetMap). Se crea una sola vez por espacio y se reutiliza;
 * cada traslado nuevo solo manda sus datos a la página.
 */
class RouteMapPane extends StackPane {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final WebView webView = new WebView();
    private final WebEngine engine = webView.getEngine();
    private boolean loaded;
    private String pending;

    RouteMapPane() {
        getStyleClass().add("route-map");
        webView.setContextMenuEnabled(false);
        setMinHeight(260);
        getChildren().add(webView);
        engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == Worker.State.SUCCEEDED) {
                loaded = true;
                if (pending != null) {
                    run(pending);
                    pending = null;
                }
            }
        });
        engine.load(RouteMapPane.class.getResource("/web/transport-map.html").toExternalForm());
    }

    /** Dibuja las paradas, la ruta y, si va en ruta, el vehículo en la última parada a la que llegó. */
    void show(MapData data, Transfer transfer) {
        String script = "showRoute(" + JSON.writeValueAsString(JSON.writeValueAsString(payload(data, transfer))) + ")";
        if (loaded) {
            run(script);
        } else {
            pending = script;
        }
    }

    private void run(String script) {
        try {
            engine.executeScript(script);
        } catch (RuntimeException e) {
            // Si la página no pudo cargar Leaflet, el espacio queda con el fondo del mapa; la tabla de paradas sigue abajo.
        }
    }

    static Map<String, Object> payload(MapData data, Transfer transfer) {
        Stop next = transfer.nextStop();
        Stop lastArrived = null;
        List<Map<String, Object>> stops = new ArrayList<>();
        for (Stop s : data.stops()) {
            boolean done = s.arrivedAt() != null;
            if (done) {
                lastArrived = s;
            }
            Map<String, Object> stop = new LinkedHashMap<>();
            stop.put("lat", s.latitude());
            stop.put("lon", s.longitude());
            stop.put("name", s.placeName());
            stop.put("time", done ? "llegó " + HOUR.format(s.arrivedAt())
                    : s.plannedAt() == null ? null : HOUR.format(s.plannedAt()));
            stop.put("state", done ? "done" : s.equals(next) ? "current" : "pending");
            stops.add(stop);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stops", stops);
        result.put("line", data.route() == null ? null
                : data.route().line().stream().map(p -> new double[]{p.lat(), p.lon()}).toList());
        Map<String, Object> vehicle = null;
        if (lastArrived != null && next != null && transfer.status() == TransportStatus.IN_PROGRESS) {
            vehicle = new LinkedHashMap<>();
            vehicle.put("lat", lastArrived.latitude());
            vehicle.put("lon", lastArrived.longitude());
            vehicle.put("label", mainPlate(transfer) + " ▸ " + next.placeName());
        }
        result.put("vehicle", vehicle);
        return result;
    }

    private static String mainPlate(Transfer t) {
        return t.units().stream().filter(u -> u.type() == VehicleType.HEARSE)
                .map(Unit::plate).findFirst().orElse(t.units().isEmpty() ? "" : t.units().get(0).plate());
    }
}
