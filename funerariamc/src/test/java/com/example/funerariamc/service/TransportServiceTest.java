package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Driver;
import com.example.funerariamc.model.Manager;
import com.example.funerariamc.model.Role;
import com.example.funerariamc.model.ServiceContract;
import com.example.funerariamc.model.ServiceTransport;
import com.example.funerariamc.model.TransportStop;
import com.example.funerariamc.model.Vehicle;
import com.example.funerariamc.model.enums.ContractStatus;
import com.example.funerariamc.model.enums.StopType;
import com.example.funerariamc.model.enums.TransportStatus;
import com.example.funerariamc.model.enums.VehicleStatus;
import com.example.funerariamc.model.enums.VehicleType;
import com.example.funerariamc.persistence.DriverRepository;
import com.example.funerariamc.persistence.ServiceContractRepository;
import com.example.funerariamc.persistence.ServiceTransportRepository;
import com.example.funerariamc.persistence.ServiceTransportRepository.BusyRow;
import com.example.funerariamc.persistence.TransportStopRepository;
import com.example.funerariamc.persistence.VehicleRepository;
import com.example.funerariamc.service.TransportService.Kpis;
import com.example.funerariamc.service.TransportService.Saved;
import com.example.funerariamc.service.TransportService.Stop;
import com.example.funerariamc.service.TransportService.Transfer;
import com.example.funerariamc.service.TransportService.Unit;
import com.example.funerariamc.service.TransportService.VehicleCard;
import com.example.funerariamc.service.TransportService.VehicleState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Criterios del módulo Transporte: registrar vehículos, asignar traslados
 * con las reglas de licencia, documentos, vehículos incluidos y horario, y mover el traslado de programado a en ruta y a completado.
 */
class TransportServiceTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    /** "Ahora" de las pruebas: 06/11/2026 a las 10:00. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 10, 0);
    private static final LocalDate TODAY = NOW.toLocalDate();

    private final VehicleRepository vehicles = mock(VehicleRepository.class);
    private final DriverRepository drivers = mock(DriverRepository.class);
    private final ServiceTransportRepository transports = mock(ServiceTransportRepository.class);
    private final TransportStopRepository stops = mock(TransportStopRepository.class);
    private final ServiceContractRepository contracts = mock(ServiceContractRepository.class);
    private final SystemParameterService parameters = mock(SystemParameterService.class);
    private final FileStorageService files = mock(FileStorageService.class);
    private final AccessLogService accessLog = mock(AccessLogService.class);
    private final SessionContext session = new SessionContext();
    private final TransportService service = new TransportService(vehicles, drivers, transports, stops, contracts,
            parameters, files, accessLog, session, Clock.fixed(NOW.atZone(BOGOTA).toInstant(), BOGOTA));

    private final ServiceContract contract = contract();
    private final Vehicle hearse = vehicle(1L, "KLM-519", VehicleType.HEARSE);
    private final Vehicle bus = vehicle(2L, "TRX-204", VehicleType.BUS);
    private final Vehicle bus2 = vehicle(3L, "TRX-310", VehicleType.BUS);
    private final Driver carlos = driver(10L, "Carlos", TODAY.plusYears(1));
    private final Driver luis = driver(11L, "Luis", TODAY.plusYears(1));
    private final Driver ana = driver(12L, "Ana", TODAY.plusYears(1));

    @BeforeEach
    void setUp() {
        loginAs(RoleNames.SECRETARY);
        when(contracts.findById(30L)).thenReturn(Optional.of(contract));
        when(vehicles.findAllById(any())).thenReturn(List.of(hearse, bus, bus2));
        when(drivers.findAllById(any())).thenReturn(List.of(carlos, luis, ana));
        when(transports.countIncluded(anyLong(), any())).thenReturn(List.of());
        when(transports.findBusy(any(), any(), any(), any())).thenReturn(List.of());
        when(transports.save(any())).thenAnswer(invocation -> {
            ServiceTransport t = invocation.getArgument(0);
            t.setId(100L + t.getVehicle().getId());
            return t;
        });
        when(vehicles.save(any())).thenAnswer(invocation -> {
            Vehicle v = invocation.getArgument(0);
            v.setId(50L);
            return v;
        });
    }

    // ---------- utilidades puras ----------

    @Test
    void missingWarningParameterIsReportedInsteadOfDisablingWarnings() {
        InvalidDataException missing = new InvalidDataException("Falta el parámetro de anticipación.");
        when(parameters.getLong(EmployeeService.DOC_EXPIRY_WARNING_DAYS)).thenThrow(missing);

        assertSame(missing, assertThrows(InvalidDataException.class, service::screen));
    }

    @Test
    void invalidWarningDaysAreRejected() {
        when(parameters.getLong(EmployeeService.DOC_EXPIRY_WARNING_DAYS)).thenReturn(-1L);
        assertThrows(InvalidDataException.class, service::screen);

        when(parameters.getLong(EmployeeService.DOC_EXPIRY_WARNING_DAYS)).thenReturn(Long.MAX_VALUE);
        assertThrows(InvalidDataException.class, service::screen);
    }

    @Test
    void plateIsNormalizedToTheColombianFormat() {
        assertEquals("KLM-519", TransportService.normalizePlate(" klm 519 "));
        assertEquals("KLM-519", TransportService.normalizePlate("KLM519"));
        assertEquals("ABC-12D", TransportService.normalizePlate("abc 12d"));
    }

    @Test
    void schedulesThatShareAMinuteOverlap() {
        LocalDateTime nine = NOW.withHour(9);
        assertTrue(TransportService.overlaps(nine, nine.plusHours(2), nine.plusHours(2), nine.plusHours(3)));
        assertFalse(TransportService.overlaps(nine, nine.plusHours(2), nine.plusHours(2).plusMinutes(1),
                nine.plusHours(3)));
    }

    @Test
    void vehicleInMaintenanceOrWithExpiredDocumentsIsBlocked() {
        // Documentos del vehículo
        Vehicle v = vehicle(9L, "AAA-111", VehicleType.BUS);
        assertNull(TransportService.vehicleBlock(v, TODAY));
        v.setSoatExpiryDate(TODAY.minusDays(1));
        assertTrue(TransportService.vehicleBlock(v, TODAY).contains("SOAT venció"));
        v.setSoatExpiryDate(TODAY);
        assertNull(TransportService.vehicleBlock(v, TODAY), "el SOAT vence hoy: todavía sirve hoy");
        v.setStatus(VehicleStatus.MAINTENANCE);
        assertEquals("está en mantenimiento", TransportService.vehicleBlock(v, TODAY));
    }

    @Test
    void expiredLicenseBlocksTheDriver() {
        // Documentos del vehículo
        assertNotNull(TransportService.licenseBlock(TODAY.minusDays(1), TODAY));
        assertNotNull(TransportService.licenseBlock(null, TODAY));
        assertNull(TransportService.licenseBlock(TODAY, TODAY));
    }

    @Test
    void stopBeforeTheDepartureIsRejected() {
        LocalDateTime start = NOW.plusHours(2);
        List<TransferData.StopData> list = List.of(
                new TransferData.StopData(StopType.ORIGIN, "Sede El Edén", null, start),
                new TransferData.StopData(StopType.DESTINATION, "Cementerio Central", null, start.minusMinutes(30)));
        assertThrows(InvalidDataException.class, () -> TransportService.validStops(list, start));
    }

    @Test
    void busyRowConflictNamesTheOtherContract() {
        BusyRow row = busy(2L, 11L, NOW.plusHours(1), NOW.plusHours(3));
        String text = TransportService.conflict(List.of(row), busy -> busy.getVehicleId(), 2L,
                NOW.plusHours(2), NOW.plusHours(4), null);
        assertNotNull(text);
        assertTrue(text.contains("CT-2026-0099"));
        assertNull(TransportService.conflict(List.of(row), busy -> busy.getVehicleId(), 2L,
                NOW.plusHours(4), NOW.plusHours(5), null));
    }

    // ---------- tarjetas e indicadores ----------

    @Test
    void vehicleOnARouteShowsTheNextStopAndCountsAsInRoute() {
        Stop origin = new Stop(1L, 1, StopType.ORIGIN, "Sede El Edén", null, NOW.minusMinutes(30), NOW.minusMinutes(30));
        Stop church = new Stop(2L, 2, StopType.CEREMONY, "Parroquia San José", null, NOW.plusMinutes(20), null);
        Transfer transfer = new Transfer(30L, "CT-2026-0001", "José Ríos", NOW.minusMinutes(30),
                TransportStatus.IN_PROGRESS, NOW.minusMinutes(30), null,
                List.of(new Unit(101L, 1L, "KLM-519", VehicleType.HEARSE, 10L, "Carlos Pérez", true,
                        TransportStatus.IN_PROGRESS)),
                List.of(origin, church), null);

        VehicleCard card = TransportService.vehicleCard(hearse, List.of(transfer), NOW, 30);
        VehicleCard free = TransportService.vehicleCard(bus, List.of(transfer), NOW, 30);
        Vehicle expired = vehicle(4L, "XYZ-001", VehicleType.BUS);
        expired.setTechnicalReviewExpiryDate(TODAY.minusDays(3));
        VehicleCard blocked = TransportService.vehicleCard(expired, List.of(), NOW, 30);

        assertEquals(VehicleState.IN_ROUTE, card.state());
        assertTrue(card.where().contains("Parroquia San José"));
        assertEquals("Carlos Pérez", card.driverName());
        assertEquals(VehicleState.AVAILABLE, free.state());
        assertEquals(VehicleState.EXPIRED_DOCS, blocked.state());

        Kpis kpis = TransportService.kpis(List.of(card, free, blocked));
        assertEquals(1, kpis.inRoute());
        assertEquals(1, kpis.available());
    }

    // ---------- asignar traslado ----------

    @Test
    void assignSavesOneTransferPerVehicleWithTheSameStops() {
        Saved saved = service.assign(data(
                new TransferData.Assignment(1L, 10L, true),
                new TransferData.Assignment(2L, 11L, true)));

        ArgumentCaptor<ServiceTransport> captor = ArgumentCaptor.forClass(ServiceTransport.class);
        verify(transports, times(2)).save(captor.capture());
        assertTrue(captor.getAllValues().stream().allMatch(t -> t.getStatus() == TransportStatus.SCHEDULED));
        verify(stops, times(4)).save(any(TransportStop.class));
        assertEquals(101L, saved.id());
        assertNull(saved.warning());
        verify(accessLog).record(5L, AccessLogService.TRANSPORT_ASSIGNED + "CT-2026-0001 KLM-519, TRX-204");
    }

    @Test
    void secondIncludedHearseIsRejected() {
        // El contrato ya tiene su coche fúnebre incluido.
        ServiceTransportRepository.IncludedCount count = mock(ServiceTransportRepository.IncludedCount.class);
        when(count.getVehicleType()).thenReturn(VehicleType.HEARSE);
        when(count.getTotal()).thenReturn(1L);
        when(transports.countIncluded(anyLong(), any())).thenReturn(List.of(count));

        assertThrows(InvalidDataException.class,
                () -> service.assign(data(new TransferData.Assignment(1L, 10L, true))));
        verify(transports, never()).save(any());
    }

    @Test
    void onlyBusesCanGoAsAdditional() {
        assertThrows(InvalidDataException.class,
                () -> service.assign(data(new TransferData.Assignment(1L, 10L, false))));
    }

    @Test
    void additionalBusNotChargedInTheSaleSavesWithAWarning() {
        // El cobro se hace en Contratos y ventas; aquí solo se avisa.
        Saved saved = service.assign(data(
                new TransferData.Assignment(2L, 11L, true),
                new TransferData.Assignment(3L, 12L, false)));
        assertNotNull(saved.warning());
        assertTrue(saved.warning().contains("Contratos y ventas"));
    }

    @Test
    void driverWithExpiredLicenseCannotBeAssigned() {
        // conductor con licencia vencida; PASA si se rechaza.
        carlos.setLicenseExpiryDate(TODAY.minusDays(1));
        InvalidDataException error = assertThrows(InvalidDataException.class,
                () -> service.assign(data(new TransferData.Assignment(1L, 10L, true))));
        assertTrue(error.getMessage().contains("licencia"));
    }

    @Test
    void vehicleWithExpiredSoatCannotBeAssigned() {
        // vehículo con el SOAT vencido; PASA si se rechaza.
        hearse.setSoatExpiryDate(TODAY.minusDays(2));
        assertThrows(InvalidDataException.class,
                () -> service.assign(data(new TransferData.Assignment(1L, 10L, true))));
    }

    @Test
    void vehicleBusyInAnotherTransferAtThatTimeCannotBeAssigned() {
        // mismo vehículo en dos traslados que se cruzan; PASA si se rechaza.
        BusyRow other = busy(1L, 99L, NOW.plusHours(1), NOW.plusHours(4));
        when(transports.findBusy(any(), any(), any(), any())).thenReturn(List.of(other));
        InvalidDataException error = assertThrows(InvalidDataException.class,
                () -> service.assign(data(new TransferData.Assignment(1L, 10L, true))));
        assertTrue(error.getMessage().contains("CT-2026-0099"));
    }

    @Test
    void sameDriverForTwoVehiclesIsRejected() {
        assertThrows(InvalidDataException.class, () -> service.assign(data(
                new TransferData.Assignment(1L, 10L, true),
                new TransferData.Assignment(2L, 10L, true))));
    }

    @Test
    void contractThatIsNotActiveCannotGetTransport() {
        contract.setStatus(ContractStatus.CANCELLED);
        assertThrows(InvalidDataException.class,
                () -> service.assign(data(new TransferData.Assignment(1L, 10L, true))));
    }

    // ---------- salida, llegadas y cierre ----------

    @Test
    void startPutsTheTransferAndTheVehicleOnTheRoute() {
        ServiceTransport t = transport(101L, hearse, TransportStatus.SCHEDULED);
        TransportStop origin = stop(t, 1);
        when(transports.findWithVehicle(List.of(101L))).thenReturn(List.of(t));
        when(stops.findByTransportIds(List.of(101L))).thenReturn(List.of(origin, stop(t, 2)));

        service.start(List.of(101L));

        assertEquals(TransportStatus.IN_PROGRESS, t.getStatus());
        assertEquals(NOW, t.getStartedAt());
        assertEquals(VehicleStatus.IN_SERVICE, hearse.getStatus());
        assertEquals(NOW, origin.getArrivedAt());
    }

    @Test
    void arrivingAtTheLastStopCompletesTheTransferAndFreesTheVehicle() {
        hearse.setStatus(VehicleStatus.IN_SERVICE);
        ServiceTransport t = transport(101L, hearse, TransportStatus.IN_PROGRESS);
        TransportStop last = stop(t, 2);
        when(transports.findWithVehicle(List.of(101L))).thenReturn(List.of(t));
        when(stops.findByTransportIds(List.of(101L))).thenReturn(List.of(stop(t, 1), last));

        service.arrive(List.of(101L), 2);

        assertEquals(NOW, last.getArrivedAt());
        assertEquals(TransportStatus.COMPLETED, t.getStatus());
        assertEquals(VehicleStatus.AVAILABLE, hearse.getStatus());
    }

    @Test
    void transferOnTheRouteCannotBeCancelled() {
        ServiceTransport t = transport(101L, hearse, TransportStatus.IN_PROGRESS);
        when(transports.findWithVehicle(List.of(101L))).thenReturn(List.of(t));
        assertThrows(InvalidDataException.class, () -> service.cancel(List.of(101L)));
        assertEquals(TransportStatus.IN_PROGRESS, t.getStatus());
    }

    @Test
    void coordinatesAreSavedOnEveryVehicleCopyOfTheStop() {
        ServiceTransport hearseTransfer = transport(101L, hearse, TransportStatus.SCHEDULED);
        ServiceTransport busTransfer = transport(102L, bus, TransportStatus.SCHEDULED);
        TransportStop hearseStop = stop(hearseTransfer, 2);
        TransportStop busStop = stop(busTransfer, 2);
        TransportStop origin = stop(hearseTransfer, 1);
        when(stops.findByTransportIds(List.of(101L, 102L))).thenReturn(List.of(origin, hearseStop, busStop));

        service.saveCoordinates(List.of(101L, 102L), 2, 10.4479012, -73.2628);

        assertEquals(new java.math.BigDecimal("10.447901"), hearseStop.getLatitude());
        assertEquals(new java.math.BigDecimal("-73.262800"), busStop.getLongitude());
        assertNull(origin.getLatitude());
    }

    // ---------- vehículos ----------

    @Test
    void onlyTheMasterAdminRegistersVehicles() {
        assertThrows(UnauthorizedActionException.class, () -> service.registerVehicle(vehicleData("KLM-777")));
    }

    @Test
    void duplicatePlateIsRejected() {
        // registrar una placa que ya existe; PASA si se rechaza.
        loginAs(RoleNames.MASTER_ADMIN);
        when(vehicles.existsByPlateIgnoreCase("KLM-519")).thenReturn(true);
        assertThrows(InvalidDataException.class, () -> service.registerVehicle(vehicleData("klm 519")));
        verify(vehicles, never()).save(any());
    }

    @Test
    void registerStoresTheDocumentsAndWarnsWhenTheyAreExpired() {
        loginAs(RoleNames.MASTER_ADMIN);
        when(files.store(any(), anyString())).thenReturn("vehiculos/soat.pdf", "vehiculos/rtm.pdf");
        VehicleData data = new VehicleData("klm 777", VehicleType.HEARSE, 1, TODAY.minusDays(1),
                Path.of("soat.pdf"), TODAY.plusMonths(6), Path.of("rtm.pdf"));

        Saved saved = service.registerVehicle(data);

        ArgumentCaptor<Vehicle> captor = ArgumentCaptor.forClass(Vehicle.class);
        verify(vehicles).save(captor.capture());
        assertEquals("KLM-777", captor.getValue().getPlate());
        assertEquals("vehiculos/soat.pdf", captor.getValue().getSoatFileUrl());
        assertNotNull(saved.warning(), "SOAT vencido: se guarda pero avisa que no podrá asignarse");
        verify(accessLog).record(5L, AccessLogService.VEHICLE_REGISTERED + "KLM-777");
    }

    @Test
    void vehicleOnTheRouteCannotGoToMaintenance() {
        loginAs(RoleNames.MASTER_ADMIN);
        hearse.setStatus(VehicleStatus.IN_SERVICE);
        when(vehicles.findById(1L)).thenReturn(Optional.of(hearse));
        assertThrows(InvalidDataException.class, () -> service.setMaintenance(1L, true));
    }

    @Test
    void cemeteryAdminHasNoAccessToTransport() {
        loginAs(RoleNames.CEMETERY_ADMIN);
        assertThrows(UnauthorizedActionException.class, service::screen);
    }

    // ---------- datos de prueba ----------

    private TransferData data(TransferData.Assignment... assignments) {
        LocalDateTime start = NOW.plusHours(2);
        return new TransferData(30L, start, List.of(assignments), List.of(
                new TransferData.StopData(StopType.ORIGIN, "Sede El Edén", null, start),
                new TransferData.StopData(StopType.DESTINATION, "Cementerio Central", "Calle 16 #12-30",
                        start.plusHours(1))), null);
    }

    private static VehicleData vehicleData(String plate) {
        return new VehicleData(plate, VehicleType.BUS, 30, TODAY.plusMonths(6), Path.of("soat.pdf"),
                TODAY.plusMonths(6), Path.of("rtm.pdf"));
    }

    private static ServiceContract contract() {
        ServiceContract c = new ServiceContract();
        c.setId(30L);
        c.setContractNumber("CT-2026-0001");
        c.setStatus(ContractStatus.ACTIVE);
        return c;
    }

    private static Vehicle vehicle(Long id, String plate, VehicleType type) {
        Vehicle v = new Vehicle();
        v.setId(id);
        v.setPlate(plate);
        v.setVehicleType(type);
        v.setCapacity(type == VehicleType.BUS ? 30 : 1);
        v.setStatus(VehicleStatus.AVAILABLE);
        v.setSoatExpiryDate(TODAY.plusMonths(6));
        v.setTechnicalReviewExpiryDate(TODAY.plusMonths(6));
        return v;
    }

    private static Driver driver(Long id, String name, LocalDate licenseExpiry) {
        Driver d = new Driver();
        d.setId(id);
        d.setName(name);
        d.setLastname("Pérez");
        d.setLicenseExpiryDate(licenseExpiry);
        return d;
    }

    private ServiceTransport transport(Long id, Vehicle vehicle, TransportStatus status) {
        ServiceTransport t = new ServiceTransport();
        t.setId(id);
        t.setServiceContract(contract);
        t.setVehicle(vehicle);
        t.setDriver(carlos);
        t.setScheduledAt(NOW.minusMinutes(5));
        t.setStatus(status);
        return t;
    }

    private static TransportStop stop(ServiceTransport t, int order) {
        TransportStop s = new TransportStop();
        s.setId(t.getId() * 10 + order);
        s.setServiceTransport(t);
        s.setStopOrder(order);
        s.setStopType(order == 1 ? StopType.ORIGIN : StopType.DESTINATION);
        s.setPlaceName(order == 1 ? "Sede El Edén" : "Cementerio Central");
        return s;
    }

    private static BusyRow busy(Long vehicleId, Long driverId, LocalDateTime start, LocalDateTime end) {
        BusyRow row = mock(BusyRow.class);
        when(row.getId()).thenReturn(900L);
        when(row.getVehicleId()).thenReturn(vehicleId);
        when(row.getDriverId()).thenReturn(driverId);
        when(row.getContractNumber()).thenReturn("CT-2026-0099");
        when(row.getScheduledAt()).thenReturn(start);
        when(row.getLastPlannedAt()).thenReturn(end);
        return row;
    }

    private void loginAs(String roleName) {
        Manager employee = new Manager();
        employee.setId(99L);
        employee.setRole(new Role(1L, roleName, null));
        AppUser user = new AppUser();
        user.setId(5L);
        user.setEmployee(employee);
        session.start(user);
    }
}
