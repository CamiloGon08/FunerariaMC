package com.example.funerariamc.service;

import com.example.funerariamc.config.OpenRouteServiceProperties;
import com.example.funerariamc.model.enums.StopType;
import com.example.funerariamc.model.enums.TransportStatus;
import com.example.funerariamc.model.enums.VehicleType;
import com.example.funerariamc.service.RouteMapService.MapData;
import com.example.funerariamc.service.TransportService.Stop;
import com.example.funerariamc.service.TransportService.Transfer;
import com.example.funerariamc.service.TransportService.Unit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.containsString;

/** Mapa de Transporte: coordenadas una sola vez por parada y ruta por las calles con OpenRouteService. */
class RouteMapServiceTest {

    private static final LocalDateTime NINE = LocalDateTime.of(2026, 11, 6, 9, 30);
    private static final String GEOCODE = """
            {"features":[{"geometry":{"type":"Point","coordinates":[-73.2501,10.4702]}}]}""";
    private static final String ROUTE = """
            {"features":[{"geometry":{"coordinates":[[-73.2532,10.4631],[-73.2520,10.4660],[-73.2501,10.4702]]},
              "properties":{"summary":{"distance":4630.0,"duration":740.0}}}]}""";

    private final TransportService transports = mock(TransportService.class);
    private final RestClient.Builder builder = RestClient.builder().baseUrl(OpenRouteServiceProperties.API_URL);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private RouteMapService service(String key) {
        return new RouteMapService(new OpenRouteServiceProperties(key, "Valledupar, Cesar"), transports, builder.build());
    }

    @Test
    void stopWithoutCoordinatesIsSearchedOnceAndSavedForAllVehicles() {
        server.expect(requestTo(containsString("/geocode/search"))).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(GEOCODE, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v2/directions/driving-car/geojson"))).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(ROUTE, MediaType.APPLICATION_JSON));

        MapData data = service("clave").prepare(transfer(
                stop(1, "Sede El Edén", 10.4631, -73.2532), stop(2, "Parroquia San José", null, null)));

        assertEquals(2, data.stops().size());
        assertEquals(10.4702, data.stops().get(1).latitude(), 1e-6);
        verify(transports).saveCoordinates(List.of(100L, 101L), 2, 10.4702, -73.2501);
        assertEquals(3, data.route().line().size());
        assertEquals(4.6, data.route().km(), 1e-9);
        assertEquals(12, data.route().minutes());
        assertTrue(data.notFound().isEmpty());
        server.verify();
    }

    @Test
    void stopsAlreadyLocatedAreNotSearchedAgain() {
        server.expect(requestTo(containsString("/v2/directions"))).andRespond(withSuccess(ROUTE, MediaType.APPLICATION_JSON));
        service("clave").prepare(transfer(stop(1, "Sede", 10.46, -73.25), stop(2, "Cementerio", 10.47, -73.24)));
        verify(transports, never()).saveCoordinates(anyList(), anyInt(), anyDouble(), anyDouble());
        server.verify();
    }

    @Test
    void withoutKeyNothingIsCalledAndTheScreenKeepsTheStopLine() {
        MapData data = service("").prepare(transfer(stop(1, "Sede", null, null), stop(2, "Cementerio", null, null)));
        assertTrue(data.stops().isEmpty());
        assertNull(data.route());
        assertEquals(List.of("Sede", "Cementerio"), data.notFound());
        server.verify();
    }

    @Test
    void failedRouteLeavesStraightLineInsteadOfAnError() {
        server.expect(requestTo(containsString("/v2/directions"))).andRespond(withServerError());
        MapData data = service("clave").prepare(transfer(stop(1, "Sede", 10.46, -73.25), stop(2, "Cementerio", 10.47, -73.24)));
        assertEquals(2, data.stops().size());
        assertNull(data.route());
    }

    @Test
    void malformedGeocodingResponseKeepsTheStopAsNotFound() {
        server.expect(requestTo(containsString("/geocode/search")))
                .andRespond(withSuccess("{incomplete", MediaType.APPLICATION_JSON));

        MapData data = service("clave").prepare(transfer(
                stop(1, "Sede", 10.46, -73.25), stop(2, "Cementerio", null, null)));

        assertEquals(List.of("Cementerio"), data.notFound());
        assertNull(data.route());
        verifyNoInteractions(transports);
        server.verify();
    }

    @Test
    void malformedRouteResponseKeepsTheLocatedStops() {
        server.expect(requestTo(containsString("/v2/directions")))
                .andRespond(withSuccess("{incomplete", MediaType.APPLICATION_JSON));

        MapData data = service("clave").prepare(transfer(
                stop(1, "Sede", 10.46, -73.25), stop(2, "Cementerio", 10.47, -73.24)));

        assertEquals(2, data.stops().size());
        assertNull(data.route());
        server.verify();
    }

    @Test
    void routeWithIncompleteCoordinatesKeepsTheLocatedStops() {
        server.expect(requestTo(containsString("/v2/directions")))
                .andRespond(withSuccess("{\"features\":[{\"geometry\":{\"coordinates\":[[-73.25],[-73.24,10.47]]}}]}",
                        MediaType.APPLICATION_JSON));

        MapData data = service("clave").prepare(transfer(
                stop(1, "Sede", 10.46, -73.25), stop(2, "Cementerio", 10.47, -73.24)));

        assertEquals(2, data.stops().size());
        assertNull(data.route());
        server.verify();
    }

    @Test
    void addressNotFoundIsListed() {
        server.expect(requestTo(containsString("/geocode/search")))
                .andRespond(withSuccess("{\"features\":[]}", MediaType.APPLICATION_JSON));
        MapData data = service("clave").prepare(transfer(stop(1, "Sede", 10.46, -73.25), stop(2, "Finca La Esperanza", null, null)));
        assertEquals(List.of("Finca La Esperanza"), data.notFound());
        assertNull(data.route(), "con una sola parada ubicada no hay ruta");
    }

    private static Stop stop(int order, String name, Double lat, Double lon) {
        return new Stop((long) order, order, order == 1 ? StopType.ORIGIN : StopType.DESTINATION, name, null,
                NINE.plusMinutes(30L * (order - 1)), null, lat, lon);
    }

    private static Transfer transfer(Stop... stops) {
        return new Transfer(40L, "CT-2026-0014", "José Ríos", NINE, TransportStatus.SCHEDULED, null, null,
                List.of(new Unit(100L, 1L, "KLM-519", VehicleType.HEARSE, 10L, "Carlos Pérez", true, TransportStatus.SCHEDULED),
                        new Unit(101L, 3L, "TRX-204", VehicleType.BUS, 11L, "Luis Martínez", true, TransportStatus.SCHEDULED)),
                List.of(stops), null);
    }
}
