package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.ResourceNotFoundException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.Driver;
import com.example.funerariamc.model.ServiceContract;
import com.example.funerariamc.model.ServiceTransport;
import com.example.funerariamc.model.TransportStop;
import com.example.funerariamc.model.Vehicle;
import com.example.funerariamc.model.enums.ContractStatus;
import com.example.funerariamc.model.enums.EmployeeStatus;
import com.example.funerariamc.model.enums.SaleStatus;
import com.example.funerariamc.model.enums.StopType;
import com.example.funerariamc.model.enums.TransportStatus;
import com.example.funerariamc.model.enums.VehicleStatus;
import com.example.funerariamc.model.enums.VehicleType;
import com.example.funerariamc.persistence.DriverRepository;
import com.example.funerariamc.persistence.DriverRepository.DriverRow;
import com.example.funerariamc.persistence.ServiceContractRepository;
import com.example.funerariamc.persistence.ServiceTransportRepository;
import com.example.funerariamc.persistence.ServiceTransportRepository.BusyRow;
import com.example.funerariamc.persistence.ServiceTransportRepository.TransferRow;
import com.example.funerariamc.persistence.TransportStopRepository;
import com.example.funerariamc.persistence.VehicleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Módulo Transporte: monitor de la flota, registro de vehículos y sus
 * documentos y asignación del transporte del servicio con
 * las reglas de licencia vigente, SOAT y técnico-mecánica vigentes sin mantenimiento, coche fúnebre
 * y bus incluidos, y sin cruces de horario.
 * Lo usan la Secretaría y el Administrador Maestro; registrar y editar vehículos es
 * solo del Administrador Maestro.
 * La pantalla completa se arma en una transacción de solo lectura con cuatro consultas.
 */
@Service
public class TransportService {

    /** Días que muestra la pestaña Traslados desde hoy (hoy, mañana y el resto de la semana). */
    public static final int DAYS_AHEAD = 7;
    /** Resultados de los buscadores del diálogo. */
    public static final int SHORT_LIST = 20;
    /** Carpeta donde se guardan el SOAT y la técnico-mecánica. */
    static final String FILES_FOLDER = "vehiculos";

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DAY_HOUR = DateTimeFormatter.ofPattern("dd/MM HH:mm");
    private static final List<TransportStatus> BUSY_STATUSES = List.of(TransportStatus.SCHEDULED,
            TransportStatus.IN_PROGRESS);

    /** Estado del vehículo que se pinta en el monitor y en su tarjeta. */
    public enum VehicleState {
        IN_ROUTE("En ruta"),
        AVAILABLE("Disponible"),
        MAINTENANCE("Mantenimiento"),
        EXPIRED_DOCS("Documentos vencidos");

        private final String label;

        VehicleState(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** Estado del conductor en la pestaña Conductores. */
    public enum DriverState {
        IN_ROUTE("En ruta"),
        AVAILABLE("Disponible"),
        BLOCKED("Bloqueado");

        private final String label;

        DriverState(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** Vigencia de un documento: vencido, por vencer (DOC_EXPIRY_WARNING_DAYS) o vigente. */
    public record DocStatus(LocalDate expiry, boolean expired, boolean expiringSoon) {

        static DocStatus of(LocalDate expiry, LocalDate today, int warningDays) {
            boolean expired = expiry == null || expiry.isBefore(today);
            boolean soon = expiry != null && !expired && !expiry.isAfter(today.plusDays(warningDays));
            return new DocStatus(expiry, expired, soon);
        }
    }

    /** Tarjeta de un vehículo (23.1). where dice dónde está y conductor el de su traslado actual o próximo. */
    public record VehicleCard(Long id, String plate, VehicleType type, Integer capacity, VehicleStatus status,
                              VehicleState state, DocStatus soat, DocStatus technicalReview, String where,
                              String driverName, String soatFileUrl, String technicalReviewFileUrl) {
    }

    /** Fila de un conductor (23.2). */
    public record DriverCard(Long id, String name, String category, DocStatus license, int transfersToday,
                             String vehiclePlate, String currentTransfer, DriverState state) {
    }

    /**
     * Parada del recorrido con su hora prevista y la real. latitude/longitude se buscan una sola vez
     * para el mapa y quedan guardadas en la parada (null mientras no se hayan buscado).
     */
    public record Stop(Long id, int order, StopType type, String placeName, String address,
                       LocalDateTime plannedAt, LocalDateTime arrivedAt, Double latitude, Double longitude) {

        public Stop(Long id, int order, StopType type, String placeName, String address,
                    LocalDateTime plannedAt, LocalDateTime arrivedAt) {
            this(id, order, type, placeName, address, plannedAt, arrivedAt, null, null);
        }

        public boolean located() {
            return latitude != null && longitude != null;
        }
    }

    /** Un vehículo del traslado con su conductor. */
    public record Unit(Long transportId, Long vehicleId, String plate, VehicleType type, Long driverId,
                       String driverName, boolean included, TransportStatus status) {
    }

    /**
     * Traslado: los vehículos de un contrato que salen a la misma hora (coche fúnebre, bus incluido y
     * buses adicionales) y sus paradas. status resume el de sus vehículos.
     */
    public record Transfer(Long contractId, String contractNumber, String deceasedName, LocalDateTime scheduledAt,
                           TransportStatus status, LocalDateTime startedAt, LocalDateTime completedAt,
                           List<Unit> units, List<Stop> stops, String notes) {

        public List<Long> transportIds() {
            return units.stream().map(unit -> unit.transportId()).toList();
        }

        public String origin() {
            return stops.isEmpty() ? null : stops.get(0).placeName();
        }

        public String destination() {
            for (int i = stops.size() - 1; i >= 0; i--) {
                if (stops.get(i).type() == StopType.DESTINATION) {
                    return stops.get(i).placeName();
                }
            }
            return stops.isEmpty() ? null : stops.get(stops.size() - 1).placeName();
        }

        /** Siguiente parada sin llegada registrada; null si ya llegó a todas. */
        public Stop nextStop() {
            return stops.stream().filter(s -> s.arrivedAt() == null).findFirst().orElse(null);
        }

        /** Hora prevista de la última parada; null si no tiene horas previstas. */
        public LocalDateTime plannedEnd() {
            return stops.stream().map(stop -> stop.plannedAt()).filter(Objects::nonNull)
                    .max((left, right) -> left.compareTo(right)).orElse(null);
        }
    }

    /** Indicadores de la banda verde. */
    public record Kpis(int total, int inRoute, int inRouteHearses, int inRouteBuses, int available,
                       int maintenance, String maintenanceDetail, int expiredDocs, String expiredDetail) {
    }

    /** Toda la pantalla. canManageVehicles: el usuario es Administrador Maestro. */
    public record Screen(LocalDate today, LocalDateTime loadedAt, Kpis kpis, List<VehicleCard> vehicles,
                         List<DriverCard> drivers, List<Transfer> transfers, boolean canManageVehicles) {
    }

    /** Opción de un buscador del diálogo. */
    public record Option(Long id, String label, String detail) {
    }

    /** Lo que ya tiene el contrato elegido. */
    public record ContractTransport(Long contractId, String contractNumber, String deceasedName,
                                    LocalDateTime suggestedDeparture, boolean hasIncludedHearse,
                                    boolean hasIncludedBus, long additionalBuses, long chargedBuses) {
    }

    /** Vehículo o conductor para el diálogo. blockedReason != null: no se puede elegir y por qué. */
    public record Candidate(Long id, String label, String detail, String blockedReason) {
    }

    /** Vehículos y conductores para una hora de salida, con su disponibilidad. */
    public record Candidates(List<Candidate> hearses, List<Candidate> buses, List<Candidate> drivers) {
    }

    /** Resultado de guardar: warning es un aviso que no impide guardar (null si no hay). */
    public record Saved(Long id, String warning) {
    }

    private final VehicleRepository vehicleRepository;
    private final DriverRepository driverRepository;
    private final ServiceTransportRepository transportRepository;
    private final TransportStopRepository stopRepository;
    private final ServiceContractRepository contractRepository;
    private final SystemParameterService parameters;
    private final FileStorageService fileStorageService;
    private final AccessLogService accessLogService;
    private final SessionContext sessionContext;
    private final Clock clock;

    @Autowired
    public TransportService(VehicleRepository vehicleRepository, DriverRepository driverRepository,
                            ServiceTransportRepository transportRepository, TransportStopRepository stopRepository,
                            ServiceContractRepository contractRepository, SystemParameterService parameters,
                            FileStorageService fileStorageService, AccessLogService accessLogService,
                            SessionContext sessionContext) {
        this(vehicleRepository, driverRepository, transportRepository, stopRepository, contractRepository,
                parameters, fileStorageService, accessLogService, sessionContext,
                Clock.system(AccessLogService.APP_ZONE));
    }

    /** Las pruebas fijan la fecha y la hora con un Clock. */
    TransportService(VehicleRepository vehicleRepository, DriverRepository driverRepository,
                     ServiceTransportRepository transportRepository, TransportStopRepository stopRepository,
                     ServiceContractRepository contractRepository, SystemParameterService parameters,
                     FileStorageService fileStorageService, AccessLogService accessLogService,
                     SessionContext sessionContext, Clock clock) {
        this.vehicleRepository = vehicleRepository;
        this.driverRepository = driverRepository;
        this.transportRepository = transportRepository;
        this.stopRepository = stopRepository;
        this.contractRepository = contractRepository;
        this.parameters = parameters;
        this.fileStorageService = fileStorageService;
        this.accessLogService = accessLogService;
        this.sessionContext = sessionContext;
        this.clock = clock;
    }

    // ---------- pantalla ----------

    /**
     * Monitor, Vehículos, Conductores y Traslados en una transacción: flota, conductores activos,
     * traslados de hoy a 7 días (más los que siguen en ruta) y sus paradas.
     */
    @Transactional(readOnly = true)
    public Screen screen() {
        requireAccess();
        LocalDateTime now = now();
        LocalDate today = now.toLocalDate();
        int warningDays = warningDays();

        List<Vehicle> vehicles = vehicleRepository.findAllByOrderByPlateAsc();
        List<DriverRow> drivers = driverRepository.findRowsByStatus(EmployeeStatus.ACTIVE);
        List<TransferRow> rows = transportRepository.findTransfers(today.atStartOfDay(),
                today.plusDays(DAYS_AHEAD).atStartOfDay(), TransportStatus.CANCELLED, TransportStatus.IN_PROGRESS);
        Map<Long, List<Stop>> stops = stopsOf(rows.stream().map(row -> row.getId()).toList());
        List<Transfer> transfers = group(rows, stops);

        List<VehicleCard> cards = new ArrayList<>();
        for (Vehicle vehicle : vehicles) {
            cards.add(vehicleCard(vehicle, transfers, now, warningDays));
        }
        List<DriverCard> driverCards = new ArrayList<>();
        for (DriverRow driver : drivers) {
            driverCards.add(driverCard(driver, transfers, now, warningDays));
        }
        return new Screen(today, now, kpis(cards), cards, driverCards, transfers, isMasterAdmin());
    }

    /** Agrupa las filas por contrato y hora de salida: un traslado con todos sus vehículos. */
    static List<Transfer> group(List<TransferRow> rows, Map<Long, List<Stop>> stops) {
        Map<String, List<TransferRow>> groups = new LinkedHashMap<>();
        for (TransferRow row : rows) {
            groups.computeIfAbsent(row.getContractId() + "|" + row.getScheduledAt(), k -> new ArrayList<>()).add(row);
        }
        List<Transfer> result = new ArrayList<>();
        for (List<TransferRow> group : groups.values()) {
            TransferRow first = group.get(0);
            List<Unit> units = new ArrayList<>();
            List<Stop> route = List.of();
            LocalDateTime started = null;
            LocalDateTime completed = null;
            boolean anyInProgress = false;
            boolean allCompleted = true;
            String notes = null;
            for (TransferRow row : group) {
                units.add(new Unit(row.getId(), row.getVehicleId(), row.getPlate(), row.getVehicleType(),
                        row.getDriverId(), fullName(row.getDriverName(), row.getDriverLastname()),
                        Boolean.TRUE.equals(row.getIncluded()), row.getStatus()));
                List<Stop> own = stops.getOrDefault(row.getId(), List.of());
                // El recorrido que se muestra es el del coche fúnebre o, si no hay, el del primero con paradas.
                if (route.isEmpty() || (row.getVehicleType() == VehicleType.HEARSE && !own.isEmpty())) {
                    route = own.isEmpty() ? route : own;
                }
                anyInProgress |= row.getStatus() == TransportStatus.IN_PROGRESS;
                allCompleted &= row.getStatus() == TransportStatus.COMPLETED;
                started = min(started, row.getStartedAt());
                completed = max(completed, row.getCompletedAt());
                notes = notes == null ? blankToNull(row.getNotes()) : notes;
            }
            TransportStatus status = anyInProgress ? TransportStatus.IN_PROGRESS
                    : allCompleted ? TransportStatus.COMPLETED : TransportStatus.SCHEDULED;
            result.add(new Transfer(first.getContractId(), first.getContractNumber(),
                    fullName(first.getDeceasedName(), first.getDeceasedLastname()), first.getScheduledAt(), status,
                    started, allCompleted ? completed : null, units, route, notes));
        }
        return result;
    }

    private Map<Long, List<Stop>> stopsOf(Collection<Long> transportIds) {
        Map<Long, List<Stop>> result = new HashMap<>();
        if (transportIds.isEmpty()) {
            return result;
        }
        for (TransportStop s : stopRepository.findByTransportIds(transportIds)) {
            result.computeIfAbsent(s.getServiceTransport().getId(), k -> new ArrayList<>())
                    .add(new Stop(s.getId(), s.getStopOrder(), s.getStopType(), s.getPlaceName(), s.getAddress(),
                            s.getPlannedAt(), s.getArrivedAt(),
                            s.getLatitude() == null ? null : s.getLatitude().doubleValue(),
                            s.getLongitude() == null ? null : s.getLongitude().doubleValue()));
        }
        return result;
    }

    /** Estado, documentos y "dónde está" de un vehículo. */
    static VehicleCard vehicleCard(Vehicle v, List<Transfer> transfers, LocalDateTime now, int warningDays) {
        LocalDate today = now.toLocalDate();
        DocStatus soat = DocStatus.of(v.getSoatExpiryDate(), today, warningDays);
        DocStatus review = DocStatus.of(v.getTechnicalReviewExpiryDate(), today, warningDays);
        Transfer current = null;
        Transfer next = null;
        Unit unit = null;
        for (Transfer t : transfers) {
            for (Unit u : t.units()) {
                if (!u.vehicleId().equals(v.getId())) {
                    continue;
                }
                if (u.status() == TransportStatus.IN_PROGRESS && current == null) {
                    current = t;
                    unit = u;
                } else if (u.status() == TransportStatus.SCHEDULED && next == null
                        && !t.scheduledAt().isBefore(now)) {
                    next = t;
                    if (unit == null) {
                        unit = u;
                    }
                }
            }
        }
        VehicleState state;
        String where;
        if (current != null) {
            state = VehicleState.IN_ROUTE;
            Stop stop = current.nextStop();
            where = "En ruta a " + orText(stop == null ? current.destination() : stop.placeName(), "destino")
                    + (stop != null && stop.plannedAt() != null ? " · llega " + HOUR.format(stop.plannedAt()) : "");
        } else if (v.getStatus() == VehicleStatus.MAINTENANCE) {
            state = VehicleState.MAINTENANCE;
            where = "En mantenimiento";
        } else if (soat.expired() || review.expired()) {
            state = VehicleState.EXPIRED_DOCS;
            where = "Bloqueado: " + expiredText(soat, review);
        } else {
            state = VehicleState.AVAILABLE;
            where = "En la sede" + (next == null ? "" : " · próximo traslado " + when(next.scheduledAt(), today));
        }
        return new VehicleCard(v.getId(), v.getPlate(), v.getVehicleType(), v.getCapacity(), v.getStatus(), state,
                soat, review, where, unit == null ? null : unit.driverName(), v.getSoatFileUrl(),
                v.getTechnicalReviewFileUrl());
    }

    /** "SOAT vencido el 30/09/2026" o "sin fecha de técnico-mecánica". */
    static String expiredText(DocStatus soat, DocStatus review) {
        List<String> parts = new ArrayList<>();
        if (soat.expired()) {
            parts.add(soat.expiry() == null ? "SOAT sin fecha" : "SOAT vencido el " + DATE.format(soat.expiry()));
        }
        if (review.expired()) {
            parts.add(review.expiry() == null ? "técnico-mecánica sin fecha"
                    : "técnico-mecánica vencida el " + DATE.format(review.expiry()));
        }
        return String.join(" y ", parts);
    }

    static DriverCard driverCard(DriverRow d, List<Transfer> transfers, LocalDateTime now, int warningDays) {
        LocalDate today = now.toLocalDate();
        DocStatus license = DocStatus.of(d.getLicenseExpiryDate(), today, warningDays);
        int todayCount = 0;
        String plate = null;
        String current = null;
        boolean inRoute = false;
        for (Transfer t : transfers) {
            for (Unit u : t.units()) {
                if (!u.driverId().equals(d.getId())) {
                    continue;
                }
                if (t.scheduledAt().toLocalDate().equals(today)) {
                    todayCount++;
                }
                if (u.status() == TransportStatus.IN_PROGRESS) {
                    inRoute = true;
                    plate = u.plate();
                    current = "Contrato " + t.contractNumber() + " · en ruta";
                } else if (!inRoute && current == null && u.status() == TransportStatus.SCHEDULED
                        && !t.scheduledAt().isBefore(now)) {
                    plate = u.plate();
                    current = "Próximo: " + t.contractNumber() + " " + when(t.scheduledAt(), today);
                }
            }
        }
        DriverState state = inRoute ? DriverState.IN_ROUTE
                : license.expired() ? DriverState.BLOCKED : DriverState.AVAILABLE;
        if (state == DriverState.BLOCKED) {
            current = d.getLicenseExpiryDate() == null ? "Licencia sin fecha · no asignable"
                    : "Licencia vencida · no asignable";
        } else if (current == null) {
            current = "Sin traslados próximos";
        }
        return new DriverCard(d.getId(), fullName(d.getName(), d.getLastname()), d.getLicenseCategory(), license,
                todayCount, plate, current, state);
    }

    static Kpis kpis(List<VehicleCard> cards) {
        int inRoute = 0;
        int hearses = 0;
        int buses = 0;
        int available = 0;
        List<String> maintenance = new ArrayList<>();
        List<String> expired = new ArrayList<>();
        for (VehicleCard c : cards) {
            switch (c.state()) {
                case IN_ROUTE -> {
                    inRoute++;
                    if (c.type() == VehicleType.HEARSE) {
                        hearses++;
                    } else {
                        buses++;
                    }
                }
                case AVAILABLE -> available++;
                case MAINTENANCE -> maintenance.add(c.plate());
                case EXPIRED_DOCS -> expired.add(c.plate() + " · " + expiredText(c.soat(), c.technicalReview()));
            }
        }
        return new Kpis(cards.size(), inRoute, hearses, buses, available, maintenance.size(),
                maintenance.isEmpty() ? null : String.join(", ", maintenance), expired.size(),
                expired.isEmpty() ? null : expired.get(0) + (expired.size() > 1 ? " y " + (expired.size() - 1) + " más" : ""));
    }

    // ---------- diálogo "Asignar traslado" ----------

    /** Contratos vigentes por número o por nombre del difunto. */
    @Transactional(readOnly = true)
    public List<Option> searchContracts(String text) {
        requireAccess();
        return transportRepository.searchContracts(normalize(text), ContractStatus.ACTIVE, PageRequest.of(0, SHORT_LIST))
                .stream()
                .map(c -> new Option(c.getId(), c.getContractNumber(),
                        fullName(c.getDeceasedName(), c.getDeceasedLastname())))
                .toList();
    }

    /** Transporte que ya tiene el contrato y salida sugerida (fin de la velación, si está registrado). */
    @Transactional(readOnly = true)
    public ContractTransport contractTransport(Long contractId) {
        requireAccess();
        ServiceContract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new ResourceNotFoundException("Contrato", contractId));
        boolean hearse = false;
        boolean bus = false;
        for (ServiceTransportRepository.IncludedCount count
                : transportRepository.countIncluded(contractId, TransportStatus.CANCELLED)) {
            hearse |= count.getVehicleType() == VehicleType.HEARSE && count.getTotal() > 0;
            bus |= count.getVehicleType() == VehicleType.BUS && count.getTotal() > 0;
        }
        long additional = transportRepository.countAdditional(contractId, TransportStatus.CANCELLED);
        long charged = transportRepository.countChargedBuses(contractId, SaleStatus.ACTIVE, ContractService.BUS_LINE);
        String deceased = contract.getDeceased() == null ? null
                : fullName(contract.getDeceased().getName(), contract.getDeceased().getLastname());
        return new ContractTransport(contract.getId(), contract.getContractNumber(), deceased,
                contract.getWakeEndAt(), hearse, bus, additional, charged);
    }

    /**
     * Vehículos y conductores para salir a "from" y terminar a "until" (hora prevista de la última
     * parada; null = misma hora). Los que no se pueden asignar llevan el motivo.
     */
    @Transactional(readOnly = true)
    public Candidates candidates(LocalDateTime from, LocalDateTime until) {
        requireAccess();
        if (from == null) {
            throw new InvalidDataException("Elija la fecha y la hora de salida.");
        }
        LocalDateTime end = until == null || until.isBefore(from) ? from : until;
        List<Vehicle> vehicles = vehicleRepository.findAllByOrderByPlateAsc();
        List<DriverRow> drivers = driverRepository.findRowsByStatus(EmployeeStatus.ACTIVE);
        List<Long> vehicleIds = vehicles.stream().map(vehicle -> vehicle.getId()).toList();
        List<Long> driverIds = drivers.stream().map(driver -> driver.getId()).toList();
        List<BusyRow> busy = vehicleIds.isEmpty() && driverIds.isEmpty() ? List.of()
                : transportRepository.findBusy(orNone(vehicleIds), orNone(driverIds), BUSY_STATUSES, end);
        LocalDate day = from.toLocalDate();

        List<Candidate> hearses = new ArrayList<>();
        List<Candidate> buses = new ArrayList<>();
        for (Vehicle v : vehicles) {
            String reason = vehicleBlock(v, day);
            if (reason == null) {
                reason = conflict(busy, row -> row.getVehicleId(), v.getId(), from, end, null);
            }
            Candidate candidate = new Candidate(v.getId(), v.getPlate(),
                    v.getCapacity() == null ? null : v.getCapacity() + (v.getVehicleType() == VehicleType.BUS
                            ? " pasajeros" : (v.getCapacity() == 1 ? " ataúd" : " ataúdes")), reason);
            (v.getVehicleType() == VehicleType.HEARSE ? hearses : buses).add(candidate);
        }
        List<Candidate> driverOptions = new ArrayList<>();
        for (DriverRow d : drivers) {
            String reason = licenseBlock(d.getLicenseExpiryDate(), day);
            if (reason == null) {
                reason = conflict(busy, row -> row.getDriverId(), d.getId(), from, end, null);
            }
            driverOptions.add(new Candidate(d.getId(), fullName(d.getName(), d.getLastname()),
                    d.getLicenseCategory() == null ? null : "Licencia " + d.getLicenseCategory(), reason));
        }
        return new Candidates(hearses, buses, driverOptions);
    }

    /**
     * Guarda un traslado por vehículo, cada uno con las mismas paradas. Rechaza todo si un
     * vehículo o conductor no cumple licencia, documentos, vehículos incluidos u horario, y dice cuál y por qué.
     */
    @Transactional
    public Saved assign(TransferData data) {
        requireAccess();
        Long actorId = currentUserId();
        if (data == null || data.contractId() == null) {
            throw new InvalidDataException("Seleccione el contrato.");
        }
        LocalDateTime start = data.scheduledAt();
        if (start == null) {
            throw new InvalidDataException("Elija la fecha y la hora de salida.");
        }
        if (start.isBefore(now().minusMinutes(1))) {
            throw new InvalidDataException("La salida no puede quedar en el pasado.");
        }
        List<TransferData.Assignment> assignments = data.vehicles() == null ? List.of() : data.vehicles();
        if (assignments.isEmpty()) {
            throw new InvalidDataException("Agregue al menos un vehículo con su conductor.");
        }
        List<TransferData.StopData> stops = validStops(data.stops(), start);
        LocalDateTime end = stops.stream().map(stop -> stop.plannedAt()).filter(Objects::nonNull)
                .max((left, right) -> left.compareTo(right)).orElse(start);

        ServiceContract contract = contractRepository.findById(data.contractId())
                .orElseThrow(() -> new ResourceNotFoundException("Contrato", data.contractId()));
        if (contract.getStatus() != ContractStatus.ACTIVE) {
            throw new InvalidDataException("El contrato " + contract.getContractNumber() + " no está vigente.");
        }

        // Vehículos y conductores del formulario, sin repetir.
        Set<Long> vehicleIds = new HashSet<>();
        Set<Long> driverIds = new HashSet<>();
        for (TransferData.Assignment a : assignments) {
            if (a.vehicleId() == null || a.driverId() == null) {
                throw new InvalidDataException("Cada vehículo necesita su conductor.");
            }
            if (!vehicleIds.add(a.vehicleId())) {
                throw new InvalidDataException("Un mismo vehículo está dos veces en el traslado.");
            }
            if (!driverIds.add(a.driverId())) {
                throw new InvalidDataException("Un mismo conductor no puede manejar dos vehículos a la vez.");
            }
        }
        Map<Long, Vehicle> vehicles = new HashMap<>();
        vehicleRepository.findAllById(vehicleIds).forEach(v -> vehicles.put(v.getId(), v));
        Map<Long, Driver> drivers = new HashMap<>();
        driverRepository.findAllById(driverIds).forEach(d -> drivers.put(d.getId(), d));

        // Un coche fúnebre y un bus incluidos por contrato; los adicionales solo pueden ser buses.
        ContractTransport current = contractTransport(contract.getId());
        boolean hearseIncluded = current.hasIncludedHearse();
        boolean busIncluded = current.hasIncludedBus();
        int newAdditional = 0;
        LocalDate day = start.toLocalDate();
        for (TransferData.Assignment a : assignments) {
            Vehicle v = vehicles.get(a.vehicleId());
            Driver d = drivers.get(a.driverId());
            if (v == null) {
                throw new ResourceNotFoundException("Vehículo", a.vehicleId());
            }
            if (d == null || !d.isActive()) {
                throw new InvalidDataException("El conductor elegido no está activo.");
            }
            if (a.included()) {
                if (v.getVehicleType() == VehicleType.HEARSE) {
                    if (hearseIncluded) {
                        throw new InvalidDataException("El contrato " + contract.getContractNumber()
                                + " ya tiene su coche fúnebre incluido.");
                    }
                    hearseIncluded = true;
                } else {
                    if (busIncluded) {
                        throw new InvalidDataException("El contrato " + contract.getContractNumber()
                                + " ya tiene su bus incluido; los demás van como buses adicionales.");
                    }
                    busIncluded = true;
                }
            } else {
                if (v.getVehicleType() != VehicleType.BUS) {
                    throw new InvalidDataException("Solo los buses pueden ir como adicionales.");
                }
                newAdditional++;
            }
            String block = vehicleBlock(v, day);
            if (block != null) {
                throw new InvalidDataException("No se puede asignar " + v.getPlate() + ": " + block + ".");
            }
            String license = licenseBlock(d.getLicenseExpiryDate(), day);
            if (license != null) {
                throw new InvalidDataException("No se puede asignar al conductor seleccionado: " + license + ".");
            }
        }

        // Ni el vehículo ni el conductor pueden estar en otro traslado en ese horario.
        List<BusyRow> busy = transportRepository.findBusy(vehicleIds, driverIds, BUSY_STATUSES, end);
        for (TransferData.Assignment a : assignments) {
            String vehicleConflict = conflict(busy, row -> row.getVehicleId(), a.vehicleId(), start, end, null);
            if (vehicleConflict != null) {
                throw new InvalidDataException("No se puede asignar " + vehicles.get(a.vehicleId()).getPlate()
                        + ": " + vehicleConflict + ".");
            }
            String driverConflict = conflict(busy, row -> row.getDriverId(), a.driverId(), start, end, null);
            if (driverConflict != null) {
                throw new InvalidDataException("No se puede asignar al conductor seleccionado: "
                        + driverConflict + ".");
            }
        }

        String notes = blankToNull(data.notes());
        Long firstId = null;
        List<String> plates = new ArrayList<>();
        for (TransferData.Assignment a : assignments) {
            ServiceTransport transport = new ServiceTransport();
            transport.setServiceContract(contract);
            transport.setVehicle(vehicles.get(a.vehicleId()));
            transport.setDriver(drivers.get(a.driverId()));
            transport.setScheduledAt(start);
            transport.setIncluded(a.included());
            transport.setNotes(notes);
            transport.setStatus(TransportStatus.SCHEDULED);
            ServiceTransport saved = transportRepository.save(transport);
            firstId = firstId == null ? saved.getId() : firstId;
            plates.add(vehicles.get(a.vehicleId()).getPlate());
            int order = 1;
            for (TransferData.StopData s : stops) {
                TransportStop stop = new TransportStop();
                stop.setServiceTransport(saved);
                stop.setStopOrder(order++);
                stop.setStopType(s.type());
                stop.setPlaceName(s.placeName().trim());
                stop.setAddress(blankToNull(s.address()));
                stop.setPlannedAt(s.plannedAt());
                stopRepository.save(stop);
            }
        }
        accessLogService.record(actorId, AccessLogService.TRANSPORT_ASSIGNED + contract.getContractNumber()
                + " " + String.join(", ", plates));

        // Los buses adicionales se cobran en la venta del contrato (Contratos y ventas).
        long additionalTotal = current.additionalBuses() + newAdditional;
        String warning = newAdditional > 0 && additionalTotal > current.chargedBuses()
                ? "La venta del contrato cobra " + current.chargedBuses() + " bus(es) adicional(es) y ahora hay "
                + additionalTotal + " asignado(s). Revise el cobro en Contratos y ventas."
                : null;
        return new Saved(firstId, warning);
    }

    /** Paradas con nombre, tipo y horas en orden, sin quedar antes de la salida. */
    static List<TransferData.StopData> validStops(List<TransferData.StopData> stops, LocalDateTime start) {
        List<TransferData.StopData> result = new ArrayList<>();
        if (stops == null) {
            return result;
        }
        LocalDateTime previous = start;
        int number = 0;
        for (TransferData.StopData s : stops) {
            number++;
            if (s == null || s.placeName() == null || s.placeName().isBlank()) {
                throw new InvalidDataException("Escriba el lugar de la parada " + number + ".");
            }
            if (s.type() == null) {
                throw new InvalidDataException("Elija el tipo de la parada " + number + ".");
            }
            if (s.placeName().trim().length() > 120) {
                throw new InvalidDataException("El lugar de la parada " + number + " admite máximo 120 caracteres.");
            }
            if (s.address() != null && s.address().trim().length() > 200) {
                throw new InvalidDataException("La dirección de la parada " + number + " admite máximo 200 caracteres.");
            }
            if (s.plannedAt() != null) {
                if (s.plannedAt().isBefore(previous)) {
                    throw new InvalidDataException("La hora de la parada " + number
                            + " no puede ser anterior a la salida ni a la parada anterior.");
                }
                previous = s.plannedAt();
            }
            result.add(s);
        }
        return result;
    }

    /** Mantenimiento, SOAT o técnico-mecánica vencidos para ese día. null = se puede asignar. */
    static String vehicleBlock(Vehicle v, LocalDate day) {
        if (v.getStatus() == VehicleStatus.MAINTENANCE) {
            return "está en mantenimiento";
        }
        if (v.getSoatExpiryDate() == null) {
            return "no tiene registrada la fecha del SOAT";
        }
        if (v.getSoatExpiryDate().isBefore(day)) {
            return "el SOAT venció el " + DATE.format(v.getSoatExpiryDate());
        }
        if (v.getTechnicalReviewExpiryDate() == null) {
            return "no tiene registrada la fecha de la revisión técnico-mecánica";
        }
        if (v.getTechnicalReviewExpiryDate().isBefore(day)) {
            return "la revisión técnico-mecánica venció el " + DATE.format(v.getTechnicalReviewExpiryDate());
        }
        return null;
    }

    /** Licencia vencida para ese día. null = vigente. */
    static String licenseBlock(LocalDate expiry, LocalDate day) {
        if (expiry == null) {
            return "no tiene registrada la fecha de vencimiento de la licencia";
        }
        return expiry.isBefore(day) ? "la licencia venció el " + DATE.format(expiry) : null;
    }

    /**
     * Un traslado ocupa desde su salida hasta la hora prevista de su última parada (si no tiene,
     * solo la hora de salida). Devuelve qué servicio ocupa el horario o null si está libre.
     */
    static String conflict(List<BusyRow> busy, Function<BusyRow, Long> idOf, Long id, LocalDateTime start,
                           LocalDateTime end, Collection<Long> ignoreIds) {
        for (BusyRow row : busy) {
            if (!id.equals(idOf.apply(row)) || (ignoreIds != null && ignoreIds.contains(row.getId()))) {
                continue;
            }
            LocalDateTime otherEnd = row.getLastPlannedAt() == null || row.getLastPlannedAt().isBefore(row.getScheduledAt())
                    ? row.getScheduledAt() : row.getLastPlannedAt();
            if (overlaps(start, end, row.getScheduledAt(), otherEnd)) {
                return "ya está asignado al contrato " + row.getContractNumber() + " ("
                        + DAY_HOUR.format(row.getScheduledAt())
                        + (otherEnd.equals(row.getScheduledAt()) ? "" : " a " + HOUR.format(otherEnd)) + ")";
            }
        }
        return null;
    }

    /** Dos horarios se cruzan si comparten al menos un minuto (los extremos cuentan). */
    static boolean overlaps(LocalDateTime aStart, LocalDateTime aEnd, LocalDateTime bStart, LocalDateTime bEnd) {
        return !aStart.isAfter(bEnd) && !bStart.isAfter(aEnd);
    }

    // ---------- salida, llegadas y cierre ----------

    /**
     * Salida: el traslado pasa a "En ruta", sus vehículos a IN_SERVICE y la primera parada (la
     * salida) toma la hora real. Se revisan otra vez la licencia y los documentos con la fecha de hoy.
     */
    @Transactional
    public void start(List<Long> transportIds) {
        requireAccess();
        Long actorId = currentUserId();
        List<ServiceTransport> transports = load(transportIds);
        LocalDateTime now = now();
        for (ServiceTransport t : transports) {
            if (t.getStatus() != TransportStatus.SCHEDULED) {
                throw new InvalidDataException("El traslado de " + t.getVehicle().getPlate() + " ya salió o está cerrado.");
            }
            String block = vehicleBlock(t.getVehicle(), now.toLocalDate());
            if (block != null) {
                throw new InvalidDataException("No puede salir " + t.getVehicle().getPlate() + ": " + block + ".");
            }
            String license = licenseBlock(t.getDriver().getLicenseExpiryDate(), now.toLocalDate());
            if (license != null) {
                throw new InvalidDataException("No puede salir " + t.getVehicle().getPlate() + ": " + license + ".");
            }
        }
        Map<Long, List<TransportStop>> stops = stopsByTransport(transportIds);
        for (ServiceTransport t : transports) {
            t.setStatus(TransportStatus.IN_PROGRESS);
            t.setStartedAt(now);
            t.getVehicle().setStatus(VehicleStatus.IN_SERVICE);
            List<TransportStop> own = stops.getOrDefault(t.getId(), List.of());
            if (!own.isEmpty() && own.get(0).getArrivedAt() == null) {
                own.get(0).setArrivedAt(now);
            }
        }
        accessLogService.record(actorId, AccessLogService.TRANSPORT_STARTED + contractNumber(transports));
    }

    /**
     * Llegada a la parada número stopOrder: guarda la hora real. Si es la última, el traslado queda
     * completado y los vehículos vuelven a disponibles.
     */
    @Transactional
    public void arrive(List<Long> transportIds, int stopOrder) {
        requireAccess();
        Long actorId = currentUserId();
        List<ServiceTransport> transports = load(transportIds);
        for (ServiceTransport t : transports) {
            if (t.getStatus() != TransportStatus.IN_PROGRESS) {
                throw new InvalidDataException("Primero registre la salida del traslado.");
            }
        }
        Map<Long, List<TransportStop>> stops = stopsByTransport(transportIds);
        LocalDateTime now = now();
        String place = null;
        boolean last = false;
        for (ServiceTransport t : transports) {
            List<TransportStop> own = stops.getOrDefault(t.getId(), List.of());
            for (TransportStop s : own) {
                if (s.getStopOrder() == stopOrder) {
                    if (s.getArrivedAt() == null) {
                        s.setArrivedAt(now);
                    }
                    place = s.getPlaceName();
                    last |= s.getStopOrder().equals(own.get(own.size() - 1).getStopOrder());
                }
            }
        }
        if (place == null) {
            throw new InvalidDataException("La parada no existe en este traslado.");
        }
        accessLogService.record(actorId, AccessLogService.TRANSPORT_ARRIVED + contractNumber(transports) + " " + place);
        if (last) {
            finish(transports, now, transportIds, actorId);
        }
    }

    /** Cierra el traslado aunque falten llegadas (por ejemplo, si se registró todo al final del día). */
    @Transactional
    public void complete(List<Long> transportIds) {
        requireAccess();
        Long actorId = currentUserId();
        List<ServiceTransport> transports = load(transportIds);
        for (ServiceTransport t : transports) {
            if (t.getStatus() != TransportStatus.IN_PROGRESS) {
                throw new InvalidDataException("Solo se puede cerrar un traslado en ruta.");
            }
        }
        finish(transports, now(), transportIds, actorId);
    }

    private void finish(List<ServiceTransport> transports, LocalDateTime now, List<Long> transportIds, Long actorId) {
        for (ServiceTransport t : transports) {
            t.setStatus(TransportStatus.COMPLETED);
            t.setCompletedAt(now);
            Vehicle v = t.getVehicle();
            if (v.getStatus() == VehicleStatus.IN_SERVICE
                    && !transportRepository.existsByVehicleIdAndStatusAndIdNotIn(v.getId(),
                    TransportStatus.IN_PROGRESS, transportIds)) {
                v.setStatus(VehicleStatus.AVAILABLE);
            }
        }
        accessLogService.record(actorId, AccessLogService.TRANSPORT_COMPLETED + contractNumber(transports));
    }

    /** Cancela un traslado que todavía no ha salido. Queda en la BD como CANCELLED (no se borra). */
    @Transactional
    public void cancel(List<Long> transportIds) {
        requireAccess();
        Long actorId = currentUserId();
        List<ServiceTransport> transports = load(transportIds);
        for (ServiceTransport t : transports) {
            if (t.getStatus() != TransportStatus.SCHEDULED) {
                throw new InvalidDataException("Solo se puede cancelar un traslado que no ha salido.");
            }
        }
        transports.forEach(t -> t.setStatus(TransportStatus.CANCELLED));
        accessLogService.record(actorId, AccessLogService.TRANSPORT_CANCELLED + contractNumber(transports));
    }

    private List<ServiceTransport> load(List<Long> transportIds) {
        if (transportIds == null || transportIds.isEmpty()) {
            throw new InvalidDataException("Seleccione el traslado.");
        }
        List<ServiceTransport> transports = transportRepository.findWithVehicle(transportIds);
        if (transports.size() != new HashSet<>(transportIds).size()) {
            throw new ResourceNotFoundException("Traslado", transportIds.get(0));
        }
        return transports;
    }

    private Map<Long, List<TransportStop>> stopsByTransport(Collection<Long> transportIds) {
        Map<Long, List<TransportStop>> result = new HashMap<>();
        for (TransportStop s : stopRepository.findByTransportIds(transportIds)) {
            result.computeIfAbsent(s.getServiceTransport().getId(), k -> new ArrayList<>()).add(s);
        }
        return result;
    }

    private static String contractNumber(List<ServiceTransport> transports) {
        return transports.get(0).getServiceContract().getContractNumber();
    }

    /**
     * Guarda las coordenadas de la parada número stopOrder en todos los vehículos del traslado
     * (cada vehículo tiene su copia de las paradas). Así cada dirección se busca una sola vez.
     */
    @Transactional
    public void saveCoordinates(List<Long> transportIds, int stopOrder, double latitude, double longitude) {
        requireAccess();
        if (transportIds == null || transportIds.isEmpty()) {
            return;
        }
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new InvalidDataException("Las coordenadas de la parada no son válidas.");
        }
        for (TransportStop s : stopRepository.findByTransportIds(transportIds)) {
            if (s.getStopOrder() == stopOrder) {
                s.setLatitude(BigDecimal.valueOf(latitude).setScale(6, RoundingMode.HALF_UP));
                s.setLongitude(BigDecimal.valueOf(longitude).setScale(6, RoundingMode.HALF_UP));
            }
        }
    }

    // ---------- vehículos ----------

    /**
     * Guarda el vehículo DISPONIBLE con sus documentos (solo la URL). Si el SOAT o
     * la técnico-mecánica ya vencieron, lo guarda y avisa que no podrá asignarse (2a).
     */
    @Transactional
    public Saved registerVehicle(VehicleData data) {
        Long actorId = requireMasterAdmin();
        String plate = validVehicle(data);
        if (vehicleRepository.existsByPlateIgnoreCase(plate)) {
            throw new InvalidDataException("Ya existe un vehículo con la placa " + plate + ".");
        }
        if (data.soatFile() == null || data.technicalReviewFile() == null) {
            throw new InvalidDataException("Adjunte el SOAT y la revisión técnico-mecánica.");
        }
        Vehicle vehicle = new Vehicle();
        vehicle.setPlate(plate);
        vehicle.setStatus(VehicleStatus.AVAILABLE);
        fill(vehicle, data);
        Vehicle saved = vehicleRepository.save(vehicle);
        accessLogService.record(actorId, AccessLogService.VEHICLE_REGISTERED + plate);
        return new Saved(saved.getId(), expiredWarning(saved));
    }

    /** Editar placa, capacidad y documentos. Los archivos que no se cambian se conservan. */
    @Transactional
    public Saved updateVehicle(Long vehicleId, VehicleData data) {
        Long actorId = requireMasterAdmin();
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehículo", vehicleId));
        String plate = validVehicle(data);
        if (vehicleRepository.existsByPlateIgnoreCaseAndIdNot(plate, vehicleId)) {
            throw new InvalidDataException("Ya existe un vehículo con la placa " + plate + ".");
        }
        vehicle.setPlate(plate);
        fill(vehicle, data);
        accessLogService.record(actorId, AccessLogService.VEHICLE_UPDATED + plate);
        return new Saved(vehicle.getId(), expiredWarning(vehicle));
    }

    /** Pone el vehículo en mantenimiento o lo devuelve a disponible. */
    @Transactional
    public void setMaintenance(Long vehicleId, boolean maintenance) {
        Long actorId = requireMasterAdmin();
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehículo", vehicleId));
        if (vehicle.getStatus() == VehicleStatus.IN_SERVICE) {
            throw new InvalidDataException("El vehículo " + vehicle.getPlate() + " está en ruta. Cierre primero su traslado.");
        }
        vehicle.setStatus(maintenance ? VehicleStatus.MAINTENANCE : VehicleStatus.AVAILABLE);
        accessLogService.record(actorId, (maintenance ? AccessLogService.VEHICLE_MAINTENANCE
                : AccessLogService.VEHICLE_AVAILABLE) + vehicle.getPlate());
    }

    private String validVehicle(VehicleData data) {
        if (data == null || data.plate() == null || data.plate().isBlank()) {
            throw new InvalidDataException("La placa es obligatoria.");
        }
        String plate = normalizePlate(data.plate());
        if (!plate.matches("[A-Z0-9-]{5,10}")) {
            throw new InvalidDataException("La placa solo admite letras, números y guion (ejemplo: KLM-519).");
        }
        if (data.type() == null) {
            throw new InvalidDataException("Elija el tipo de vehículo.");
        }
        if (data.capacity() == null || data.capacity() <= 0) {
            throw new InvalidDataException("La capacidad debe ser un número mayor que cero.");
        }
        if (data.soatExpiry() == null) {
            throw new InvalidDataException("La fecha de vencimiento del SOAT es obligatoria.");
        }
        if (data.technicalReviewExpiry() == null) {
            throw new InvalidDataException("La fecha de vencimiento de la revisión técnico-mecánica es obligatoria.");
        }
        return plate;
    }

    /** "klm 519" pasa a "KLM-519"; lo demás se deja en mayúsculas sin espacios. */
    static String normalizePlate(String text) {
        String clean = text.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (clean.matches("[A-Z]{3} ?-?[0-9]{2,3}[A-Z]?")) {
            return clean.substring(0, 3) + "-" + clean.substring(3).replace(" ", "").replace("-", "");
        }
        return clean.replace(" ", "");
    }

    private void fill(Vehicle vehicle, VehicleData data) {
        vehicle.setVehicleType(data.type());
        vehicle.setCapacity(data.capacity());
        vehicle.setSoatExpiryDate(data.soatExpiry());
        vehicle.setTechnicalReviewExpiryDate(data.technicalReviewExpiry());
        if (data.soatFile() != null) {
            String url = fileStorageService.store(data.soatFile(), FILES_FOLDER);
            deleteFileOnRollback(url);
            vehicle.setSoatFileUrl(url);
        }
        if (data.technicalReviewFile() != null) {
            String url = fileStorageService.store(data.technicalReviewFile(), FILES_FOLDER);
            deleteFileOnRollback(url);
            vehicle.setTechnicalReviewFileUrl(url);
        }
    }

    private String expiredWarning(Vehicle v) {
        DocStatus soat = DocStatus.of(v.getSoatExpiryDate(), today(), 0);
        DocStatus review = DocStatus.of(v.getTechnicalReviewExpiryDate(), today(), 0);
        if (!soat.expired() && !review.expired()) {
            return null;
        }
        return "Se guardó el vehículo " + v.getPlate() + ", pero no podrá asignarse a servicios hasta renovar: "
                + expiredText(soat, review) + ".";
    }

    /** Si la BD no guarda el vehículo, el archivo copiado no queda huérfano en la carpeta. */
    private void deleteFileOnRollback(String url) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    fileStorageService.delete(url);
                }
            }
        });
    }

    // ---------- utilidades ----------

    private int warningDays() {
        long days = parameters.getLong(EmployeeService.DOC_EXPIRY_WARNING_DAYS);
        if (days < 0 || days > Integer.MAX_VALUE) {
            throw new InvalidDataException(
                    "Los días de anticipación de documentos deben ser un entero no negativo dentro del rango permitido.");
        }
        return (int) days;
    }

    /** "hoy 18:00", "mañana 10:00" o "12/11 09:30". */
    static String when(LocalDateTime at, LocalDate today) {
        LocalDate day = at.toLocalDate();
        if (day.equals(today)) {
            return HOUR.format(at);
        }
        if (day.equals(today.plusDays(1))) {
            return "mañana " + HOUR.format(at);
        }
        return DAY_HOUR.format(at);
    }

    private static Collection<Long> orNone(List<Long> ids) {
        return ids.isEmpty() ? List.of(-1L) : ids;
    }

    private static LocalDateTime min(LocalDateTime a, LocalDateTime b) {
        return a == null ? b : b == null || a.isBefore(b) ? a : b;
    }

    private static LocalDateTime max(LocalDateTime a, LocalDateTime b) {
        return a == null ? b : b == null || a.isAfter(b) ? a : b;
    }

    static String fullName(String name, String lastname) {
        String text = ((name == null ? "" : name.trim()) + " " + (lastname == null ? "" : lastname.trim())).trim();
        return text.isEmpty() ? "Sin nombre" : text;
    }

    private static String orText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    /** Minúsculas y sin espacios sobrantes para el buscador. */
    private static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).withSecond(0).withNano(0);
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private boolean isMasterAdmin() {
        return sessionContext.getRoleName().map(RoleNames.MASTER_ADMIN::equals).orElse(false);
    }

    private void requireAccess() {
        boolean allowed = sessionContext.getRoleName().map(AppModule.TRANSPORTE::isAllowedFor).orElse(false);
        if (!allowed) {
            throw new UnauthorizedActionException();
        }
    }

    private Long requireMasterAdmin() {
        if (!isMasterAdmin()) {
            throw new UnauthorizedActionException();
        }
        return currentUserId();
    }

    private Long currentUserId() {
        return sessionContext.getCurrentUser().map(user -> user.getId()).orElseThrow(UnauthorizedActionException::new);
    }
}
