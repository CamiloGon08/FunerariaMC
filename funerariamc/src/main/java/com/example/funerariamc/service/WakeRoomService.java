package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.ResourceNotFoundException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.Client;
import com.example.funerariamc.model.Deceased;
import com.example.funerariamc.model.Employee;
import com.example.funerariamc.model.Sale;
import com.example.funerariamc.model.SaleDetail;
import com.example.funerariamc.model.ServiceContract;
import com.example.funerariamc.model.WakeRoom;
import com.example.funerariamc.model.enums.ContractStatus;
import com.example.funerariamc.model.enums.DeathType;
import com.example.funerariamc.model.enums.DocumentType;
import com.example.funerariamc.model.enums.SaleStatus;
import com.example.funerariamc.model.enums.StopType;
import com.example.funerariamc.model.enums.TransactionStatus;
import com.example.funerariamc.model.enums.TransactionType;
import com.example.funerariamc.persistence.DeathCertificateRepository;
import com.example.funerariamc.persistence.FinancialTransactionRepository;
import com.example.funerariamc.persistence.SaleDetailRepository;
import com.example.funerariamc.persistence.SaleRepository;
import com.example.funerariamc.persistence.ServiceContractRepository;
import com.example.funerariamc.persistence.ServiceContractRepository.WakeRow;
import com.example.funerariamc.persistence.ServiceTransportRepository;
import com.example.funerariamc.persistence.WakeRoomRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Módulo Salas de velación: tablero de ocupación por día, panel de
 * detalle de la sala (19.1), registro de salas y asignación de la sala a un contrato
 * con su recargo.
 * Lo usan la Secretaría y el Administrador Maestro; registrar salas es solo del
 * Administrador Maestro.
 * El tablero se arma con una proyección y una consulta de destinos: dos viajes a la base por día.
 */
@Service
public class WakeRoomService {

    /** Texto de la línea de la venta que genera una sala especial. */
    public static final String SURCHARGE_LINE = "Recargo sala especial";
    /** Largo de wake_room.name después del SQL del módulo (antes era 10). */
    public static final int MAX_NAME = 40;
    /** Resultados de los buscadores de los diálogos. */
    public static final int SHORT_LIST = 20;

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");

    /** Estado de la sala en el día consultado. */
    public enum RoomState {
        IN_WAKE("En velación"),
        RESERVED("Reservada"),
        AVAILABLE("Disponible");

        private final String label;

        RoomState(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** Opción de un buscador del diálogo (contrato o sala libre). */
    public record Option(Long id, String label, String detail) {
    }

    /**
     * Velación que se muestra en la tarjeta. end es la salida registrada o, si falta, el fin del día
     * de ingreso; endKnown dice si la salida está registrada.
     */
    public record Wake(Long contractId, String contractNumber, String deceasedName, String familyName,
                       String planName, LocalDateTime start, LocalDateTime end, boolean endKnown,
                       LocalDateTime preparationCompletedAt, String destination) {
    }

    /**
     * Tarjeta de una sala en la fecha consultada. wake es la velación que se pinta (en curso o la
     * próxima); freeFrom es la hora en que quedó libre si hubo una velación ese día que ya terminó.
     */
    public record RoomCard(Long id, int number, String name, Integer capacity, BigDecimal basePrice,
                           boolean special, RoomState state, Wake wake, LocalDateTime freeFrom) {
    }

    /** Indicadores de la banda verde para la fecha consultada. */
    public record Kpis(int total, int inWake, int reserved, String nextReservation, int available,
                       int departures, List<LocalTime> departureTimes) {

        public int occupancyPercent() {
            return total == 0 ? 0 : Math.round(inWake * 100f / total);
        }
    }

    /** Todo el tablero de un día. today dice si la fecha es hoy ("Ocupadas ahora"). */
    public record Board(LocalDate date, boolean today, List<RoomCard> rooms, Kpis kpis) {
    }

    /** Panel de detalle de una velación (19.1). Los textos que faltan van en null. */
    public record WakeDetail(Long roomId, int roomNumber, String roomName, boolean special, Integer capacity,
                             RoomState state, Long contractId, String contractNumber,
                             LocalDateTime start, LocalDateTime end, boolean endKnown,
                             String deceasedName, String deceasedDocument, Integer age, LocalDate deathDate,
                             DeathType deathType, String certificate, LocalDateTime preparationCompletedAt,
                             String thanatopractor, String clientName, String clientDocument, String clientPhone,
                             String planLabel, String salesperson, BigDecimal roomSurcharge,
                             String destination) {
    }

    private final WakeRoomRepository roomRepository;
    private final ServiceContractRepository contractRepository;
    private final ServiceTransportRepository transportRepository;
    private final DeathCertificateRepository certificateRepository;
    private final SaleRepository saleRepository;
    private final SaleDetailRepository detailRepository;
    private final FinancialTransactionRepository transactionRepository;
    private final SaleParameters parameters;
    private final AccessLogService accessLogService;
    private final SessionContext sessionContext;
    private final Clock clock;

    @Autowired
    public WakeRoomService(WakeRoomRepository roomRepository, ServiceContractRepository contractRepository,
                           ServiceTransportRepository transportRepository,
                           DeathCertificateRepository certificateRepository, SaleRepository saleRepository,
                           SaleDetailRepository detailRepository,
                           FinancialTransactionRepository transactionRepository, SaleParameters parameters,
                           AccessLogService accessLogService, SessionContext sessionContext) {
        this(roomRepository, contractRepository, transportRepository, certificateRepository, saleRepository,
                detailRepository, transactionRepository, parameters, accessLogService, sessionContext,
                Clock.system(AccessLogService.APP_ZONE));
    }

    /** Las pruebas fijan la fecha y la hora con un Clock. */
    WakeRoomService(WakeRoomRepository roomRepository, ServiceContractRepository contractRepository,
                    ServiceTransportRepository transportRepository, DeathCertificateRepository certificateRepository,
                    SaleRepository saleRepository, SaleDetailRepository detailRepository,
                    FinancialTransactionRepository transactionRepository, SaleParameters parameters,
                    AccessLogService accessLogService, SessionContext sessionContext, Clock clock) {
        this.roomRepository = roomRepository;
        this.contractRepository = contractRepository;
        this.transportRepository = transportRepository;
        this.certificateRepository = certificateRepository;
        this.saleRepository = saleRepository;
        this.detailRepository = detailRepository;
        this.transactionRepository = transactionRepository;
        this.parameters = parameters;
        this.accessLogService = accessLogService;
        this.sessionContext = sessionContext;
        this.clock = clock;
    }

    // ---------- tablero ----------

    /**
     * Tablero de un día: una tarjeta por sala con su estado. Ejemplo: una velación de 08:00 a 18:00 del 24/10 deja la sala «En velación» el 24/10 y «Disponible» el 25/10.
     */
    @Transactional(readOnly = true)
    public Board board(LocalDate date) {
        requireAccess();
        LocalDate day = date == null ? today() : date;
        LocalDateTime now = now();
        LocalDateTime from = day.atStartOfDay();
        LocalDateTime to = day.plusDays(1).atStartOfDay();
        List<WakeRoom> rooms = roomRepository.findAllByOrderByIdAsc();
        List<WakeRow> rows = contractRepository.findWakes(from, to, ContractStatus.CANCELLED);
        Map<Long, String> destinations = destinations(rows.stream().map(row -> row.getContractId()).toList());

        Map<Long, List<Wake>> byRoom = new HashMap<>();
        for (WakeRow row : rows) {
            byRoom.computeIfAbsent(row.getRoomId(), id -> new ArrayList<>())
                    .add(wake(row, destinations.get(row.getContractId())));
        }

        List<RoomCard> cards = new ArrayList<>();
        int number = 0;
        for (WakeRoom room : rooms) {
            number++;
            List<Wake> wakes = byRoom.getOrDefault(room.getId(), List.of());
            cards.add(card(room, number, wakes, day, now));
        }
        return new Board(day, day.equals(now.toLocalDate()), cards, kpis(cards, rows, rooms, from, to));
    }

    /** Estado de la sala en el día: hoy se compara con la hora actual; otro día, con la fecha. */
    static RoomCard card(WakeRoom room, int number, List<Wake> wakes, LocalDate day, LocalDateTime now) {
        RoomState state = RoomState.AVAILABLE;
        Wake shown = null;
        LocalDateTime freeFrom = null;
        LocalDate today = now.toLocalDate();
        for (Wake wake : wakes) {
            if (day.isBefore(today)) {
                // Día pasado: la sala estuvo en velación.
                state = RoomState.IN_WAKE;
                shown = wake;
                break;
            }
            if (day.isAfter(today)) {
                state = RoomState.RESERVED;
                shown = wake;
                break;
            }
            if (!wake.start().isAfter(now) && wake.end().isAfter(now)) {
                state = RoomState.IN_WAKE;
                shown = wake;
                break;
            }
            if (wake.start().isAfter(now) && shown == null) {
                state = RoomState.RESERVED;
                shown = wake;
            } else if (!wake.end().isAfter(now)) {
                freeFrom = freeFrom == null || wake.end().isAfter(freeFrom) ? wake.end() : freeFrom;
            }
        }
        return new RoomCard(room.getId(), number, room.getName(), room.getCapacity(), room.getBasePrice(),
                Boolean.TRUE.equals(room.getSurchargeApplies()), state, shown,
                state == RoomState.AVAILABLE ? freeFrom : null);
    }

    private static Kpis kpis(List<RoomCard> cards, List<WakeRow> rows, List<WakeRoom> rooms,
                             LocalDateTime from, LocalDateTime to) {
        int inWake = 0;
        int reserved = 0;
        int available = 0;
        RoomCard next = null;
        for (RoomCard card : cards) {
            switch (card.state()) {
                case IN_WAKE -> inWake++;
                case RESERVED -> {
                    reserved++;
                    if (next == null || card.wake().start().isBefore(next.wake().start())) {
                        next = card;
                    }
                }
                case AVAILABLE -> available++;
            }
        }
        Map<Long, String> roomNames = new HashMap<>();
        rooms.forEach(r -> roomNames.put(r.getId(), r.getName()));
        List<LocalTime> departures = rows.stream()
                .map(row -> row.getWakeEndAt())
                .filter(end -> end != null && !end.isBefore(from) && end.isBefore(to))
                .sorted()
                .map(end -> end.toLocalTime())
                .toList();
        String nextText = next == null ? null : next.name() + " · " + HOUR.format(next.wake().start());
        return new Kpis(cards.size(), inWake, reserved, nextText, available, departures.size(), departures);
    }

    private Wake wake(WakeRow row, String destination) {
        LocalDateTime start = row.getWakeStartAt();
        boolean endKnown = row.getWakeEndAt() != null;
        LocalDateTime end = endKnown ? row.getWakeEndAt() : start.toLocalDate().plusDays(1).atStartOfDay();
        String family = row.getClientLastname() == null || row.getClientLastname().isBlank()
                ? row.getClientName() : "Familia " + firstWord(row.getClientLastname());
        return new Wake(row.getContractId(), row.getContractNumber(),
                fullName(row.getDeceasedName(), row.getDeceasedLastname()), family, row.getPlanName(),
                start, end, endKnown, row.getPreparationCompletedAt(), destination);
    }

    /** Primer destino del traslado de cada contrato ("Jardines del Ecce Homo"). */
    private Map<Long, String> destinations(List<Long> contractIds) {
        Map<Long, String> result = new HashMap<>();
        if (contractIds.isEmpty()) {
            return result;
        }
        for (ServiceTransportRepository.DestinationRow row
                : transportRepository.findDestinations(contractIds, StopType.DESTINATION)) {
            result.putIfAbsent(row.getContractId(), row.getDestination());
        }
        return result;
    }

    // ---------- panel de detalle (19.1) ----------

    /** Difunto, familia, servicio y salida de la velación de un contrato, en una transacción. */
    @Transactional(readOnly = true)
    public WakeDetail detail(Long contractId) {
        requireAccess();
        ServiceContract contract = contractRepository.findDetailById(contractId)
                .orElseThrow(() -> new ResourceNotFoundException("Contrato", contractId));
        WakeRoom room = contract.getWakeRoom();
        if (room == null || contract.getWakeStartAt() == null) {
            throw new InvalidDataException("El contrato " + contract.getContractNumber() + " no tiene sala asignada.");
        }
        Deceased deceased = contract.getDeceased();
        Client client = contract.getClient();
        LocalDateTime now = now();
        LocalDateTime start = contract.getWakeStartAt();
        boolean endKnown = contract.getWakeEndAt() != null;
        LocalDateTime end = endKnown ? contract.getWakeEndAt() : start.toLocalDate().plusDays(1).atStartOfDay();
        RoomState state = !start.isAfter(now) && end.isAfter(now) ? RoomState.IN_WAKE
                : start.isAfter(now) ? RoomState.RESERVED : RoomState.AVAILABLE;

        String certificate = certificateRepository.findByDeceased(deceased.getId()).stream()
                .filter(doc -> doc.getDocumentType() == DocumentType.MEDICAL_CERTIFICATE)
                .map(doc -> doc.getDocumentNumber())
                .filter(numberText -> numberText != null && !numberText.isBlank())
                .findFirst().orElse(null);
        Integer age = deceased.getBirthDate() == null || deceased.getDeathDate() == null ? null
                : Period.between(deceased.getBirthDate(), deceased.getDeathDate()).getYears();
        String destination = destinations(List.of(contractId)).get(contractId);
        String planLabel = contract.getFuneralPlan() == null ? null
                : contract.getFuneralPlan().getPlanName() + " · " + FuneralPlanService.code(contract.getFuneralPlan().getId());

        return new WakeDetail(room.getId(), roomNumber(room.getId()), room.getName(),
                Boolean.TRUE.equals(room.getSurchargeApplies()), room.getCapacity(), state,
                contract.getId(), contract.getContractNumber(), start, end, endKnown,
                fullName(deceased.getName(), deceased.getLastname()), deceased.getDocumentNumber(), age,
                deceased.getDeathDate(), deceased.getDeathType(), certificate,
                contract.getPreparationCompletedAt(), employeeName(contract.getThanatopractor()),
                ClientService.displayName(client), document(client.getDocumentType(), client.getDocumentNumber()),
                client.getPhone(), planLabel, employeeName(contract.getFuneralSalesperson()),
                roomSurcharge(contract.getId()), destination);
    }

    /** Número de la sala en el orden de registro, el mismo de la tarjeta ("Sala 01"). */
    private int roomNumber(Long roomId) {
        List<WakeRoom> rooms = roomRepository.findAllByOrderByIdAsc();
        for (int i = 0; i < rooms.size(); i++) {
            if (rooms.get(i).getId().equals(roomId)) {
                return i + 1;
            }
        }
        return 0;
    }

    /** Recargo de sala especial que ya está en la venta del contrato, o null si no hay. */
    private BigDecimal roomSurcharge(Long contractId) {
        List<Sale> sales = saleRepository.findByContract(contractId, SaleStatus.ACTIVE);
        if (sales.isEmpty()) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        boolean found = false;
        for (SaleDetail line : detailRepository.findBySale(sales.get(0).getId())) {
            if (isSurchargeLine(line)) {
                total = total.add(line.getSubtotal());
                found = true;
            }
        }
        return found ? total : null;
    }

    // ---------- buscadores de los diálogos ----------

    /** Contratos activos sin sala para "Asignar a contrato". */
    @Transactional(readOnly = true)
    public List<Option> searchContractsWithoutRoom(String text) {
        requireAccess();
        return contractRepository.searchWithoutRoom(ContractStatus.ACTIVE, pattern(text),
                        PageRequest.of(0, SHORT_LIST)).stream()
                .map(c -> new Option(c.getId(), c.getContractNumber(),
                        fullName(c.getDeceased().getName(), c.getDeceased().getLastname())
                                + " · " + ClientService.displayName(c.getClient())))
                .toList();
    }

    /** Salas sin cruce con [start, end) para "Cambiar sala"; deja fuera la sala actual del contrato. */
    @Transactional(readOnly = true)
    public List<Option> freeRooms(Long contractId, LocalDateTime start, LocalDateTime end) {
        requireAccess();
        ServiceContract contract = contractRepository.findDetailById(contractId)
                .orElseThrow(() -> new ResourceNotFoundException("Contrato", contractId));
        Long current = contract.getWakeRoom() == null ? null : contract.getWakeRoom().getId();
        List<Option> result = new ArrayList<>();
        for (WakeRoom room : roomRepository.findAllByOrderByIdAsc()) {
            if (room.getId().equals(current)) {
                continue;
            }
            if (!contractRepository.existsWakeOverlap(room.getId(), contractId, start, end, ContractStatus.CANCELLED)) {
                result.add(new Option(room.getId(), room.getName(), Boolean.TRUE.equals(room.getSurchargeApplies())
                        ? "Especial · con recargo" : "Sin recargo"));
            }
        }
        return result;
    }

    // ---------- operaciones ----------

    /** Registra una sala. Capacidad mayor que cero; el precio base no puede ser negativo. */
    @Transactional
    public WakeRoom register(WakeRoomData data) {
        Long actorId = requireMasterAdmin();
        if (data == null) {
            throw new InvalidDataException("Ingrese los datos de la sala.");
        }
        String name = data.name() == null ? "" : data.name().trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw new InvalidDataException("Ingrese el nombre de la sala.");
        }
        if (name.length() > MAX_NAME) {
            throw new InvalidDataException("El nombre de la sala admite hasta " + MAX_NAME + " caracteres.");
        }
        if (data.capacity() == null || data.capacity() <= 0) {
            throw new InvalidDataException("La capacidad de la sala debe ser mayor que cero.");
        }
        if (data.basePrice() == null || data.basePrice().signum() < 0) {
            throw new InvalidDataException("Ingrese un precio base igual o mayor que cero.");
        }
        if (data.basePrice().compareTo(new BigDecimal("99999999.99")) > 0) {
            throw new InvalidDataException("El precio base es demasiado alto.");
        }
        if (roomRepository.existsByNameIgnoreCase(name)) {
            throw new InvalidDataException("Ya existe una sala llamada " + name + ".");
        }
        WakeRoom room = new WakeRoom();
        room.setName(name);
        room.setCapacity(data.capacity());
        room.setBasePrice(data.basePrice());
        room.setSurchargeApplies(data.special());
        WakeRoom saved = roomRepository.save(room);
        accessLogService.record(actorId, AccessLogService.WAKE_ROOM_REGISTERED + name);
        return saved;
    }

    /**
     * Asocia la sala al contrato con su hora de ingreso y salida. Rechaza el cruce con
     * otra velación de la misma sala. Si la sala es especial, agrega el recargo
     * parametrizado como una línea aparte de la venta del contrato.
     */
    @Transactional
    public void assign(AssignWakeData data) {
        requireAccess();
        Long actorId = currentUserId();
        if (data == null || data.roomId() == null) {
            throw new InvalidDataException("Seleccione la sala.");
        }
        if (data.contractId() == null) {
            throw new InvalidDataException("Seleccione el contrato.");
        }
        validateRange(data.start(), data.end());
        ServiceContract contract = contractRepository.findDetailById(data.contractId())
                .orElseThrow(() -> new ResourceNotFoundException("Contrato", data.contractId()));
        if (contract.getStatus() != ContractStatus.ACTIVE) {
            throw new InvalidDataException("Solo un contrato activo puede recibir sala.");
        }
        if (contract.getWakeRoom() != null) {
            throw new InvalidDataException("El contrato " + contract.getContractNumber()
                    + " ya tiene la " + contract.getWakeRoom().getName() + ". Use «Cambiar sala».");
        }
        WakeRoom room = roomRepository.findById(data.roomId())
                .orElseThrow(() -> new ResourceNotFoundException("Sala", data.roomId()));
        checkNoOverlap(room, contract.getId(), data.start(), data.end());
        contract.setWakeRoom(room);
        contract.setWakeStartAt(data.start());
        contract.setWakeEndAt(data.end());
        applyRoomSurcharge(contract, room);
        contractRepository.save(contract);
        accessLogService.record(actorId, AccessLogService.WAKE_ROOM_ASSIGNED + contract.getContractNumber()
                + " " + room.getName());
    }

    /** "Cambiar sala" del panel: mismo horario en otra sala libre; el recargo se ajusta a la sala nueva. */
    @Transactional
    public void changeRoom(Long contractId, Long newRoomId) {
        requireAccess();
        Long actorId = currentUserId();
        if (newRoomId == null) {
            throw new InvalidDataException("Seleccione la sala nueva.");
        }
        ServiceContract contract = contractRepository.findDetailById(contractId)
                .orElseThrow(() -> new ResourceNotFoundException("Contrato", contractId));
        if (contract.getStatus() != ContractStatus.ACTIVE) {
            throw new InvalidDataException("Solo se cambia la sala de un contrato activo.");
        }
        if (contract.getWakeRoom() == null || contract.getWakeStartAt() == null) {
            throw new InvalidDataException("El contrato no tiene sala asignada.");
        }
        if (contract.getWakeRoom().getId().equals(newRoomId)) {
            throw new InvalidDataException("Elija una sala distinta a la actual.");
        }
        WakeRoom room = roomRepository.findById(newRoomId)
                .orElseThrow(() -> new ResourceNotFoundException("Sala", newRoomId));
        LocalDateTime end = contract.getWakeEndAt() != null ? contract.getWakeEndAt()
                : contract.getWakeStartAt().toLocalDate().plusDays(1).atStartOfDay();
        checkNoOverlap(room, contract.getId(), contract.getWakeStartAt(), end);
        String previous = contract.getWakeRoom().getName();
        contract.setWakeRoom(room);
        applyRoomSurcharge(contract, room);
        contractRepository.save(contract);
        accessLogService.record(actorId, AccessLogService.WAKE_ROOM_CHANGED + contract.getContractNumber()
                + " " + previous + " > " + room.getName());
    }

    /**
     * Deja en la venta del contrato exactamente el recargo que corresponde a la sala: lo agrega si es
     * especial y no estaba, y lo quita si la sala nueva no es especial. Si el contrato todavía no tiene
     * venta, no hace nada: la venta lo incluye cuando se genere.
     */
    void applyRoomSurcharge(ServiceContract contract, WakeRoom room) {
        List<Sale> sales = saleRepository.findByContract(contract.getId(), SaleStatus.ACTIVE);
        if (sales.isEmpty()) {
            return;
        }
        Sale sale = sales.get(0);
        List<SaleDetail> surcharges = detailRepository.findBySale(sale.getId()).stream()
                .filter(WakeRoomService::isSurchargeLine).toList();
        boolean special = Boolean.TRUE.equals(room.getSurchargeApplies());
        BigDecimal total = sale.getTotalAmount() == null ? BigDecimal.ZERO : sale.getTotalAmount();
        if (special && surcharges.isEmpty()) {
            BigDecimal amount = parameters.wakeRoomSurcharge();
            SaleDetail line = surchargeLine(sale, room, amount);
            detailRepository.save(line);
            total = total.add(amount);
        } else if (!special && !surcharges.isEmpty()) {
            BigDecimal removed = surcharges.stream().map(line -> line.getSubtotal())
                    .reduce(BigDecimal.ZERO, (left, right) -> left.add(right));
            BigDecimal paid = transactionRepository.sumIncomeBySale(sale.getId(), TransactionType.INCOME,
                    TransactionStatus.ACTIVE);
            if (total.subtract(removed).compareTo(paid) < 0) {
                throw new InvalidDataException("No se puede quitar el recargo de la sala: la familia ya abonó más "
                        + "de lo que quedaría en la venta.");
            }
            detailRepository.deleteAll(surcharges);
            total = total.subtract(removed);
        } else if (special) {
            // Sigue siendo sala especial: solo se actualiza el nombre de la sala en la línea.
            surcharges.forEach(line -> line.setDescription(surchargeText(room)));
            detailRepository.saveAll(surcharges);
        }
        sale.setTotalAmount(total);
        saleRepository.save(sale);
        contract.setTotalAmount(total);
    }

    /** Línea de recargo de una sala especial, lista para guardar. */
    static SaleDetail surchargeLine(Sale sale, WakeRoom room, BigDecimal amount) {
        SaleDetail line = new SaleDetail();
        line.setSale(sale);
        line.setDescription(surchargeText(room));
        line.setQuantity(1);
        line.setUnitPrice(amount);
        line.setSubtotal(amount);
        return line;
    }

    static String surchargeText(WakeRoom room) {
        String text = SURCHARGE_LINE + " · " + room.getName();
        return text.length() > 100 ? text.substring(0, 100) : text;
    }

    static boolean isSurchargeLine(SaleDetail line) {
        return line.getInventoryItem() == null && line.getDescription() != null
                && line.getDescription().startsWith(SURCHARGE_LINE);
    }

    private void checkNoOverlap(WakeRoom room, Long contractId, LocalDateTime start, LocalDateTime end) {
        if (contractRepository.existsWakeOverlap(room.getId(), contractId, start, end, ContractStatus.CANCELLED)) {
            String other = contractRepository.findOverlappingNumbers(room.getId(), contractId, start, end,
                    ContractStatus.CANCELLED, PageRequest.of(0, 1)).stream().findFirst().orElse("otro contrato");
            throw new InvalidDataException("La " + room.getName() + " ya está ocupada en ese horario por el contrato "
                    + other + ".");
        }
    }

    /** Ingreso y salida obligatorios, y la salida después del ingreso (restricción de la tabla). */
    static void validateRange(LocalDateTime start, LocalDateTime end) {
        if (start == null) {
            throw new InvalidDataException("Ingrese la fecha y hora de ingreso a la sala.");
        }
        if (end == null) {
            throw new InvalidDataException("Ingrese la fecha y hora de salida de la sala.");
        }
        if (!end.isAfter(start)) {
            throw new InvalidDataException("La salida debe ser posterior al ingreso.");
        }
    }

    // ---------- utilidades ----------

    private static String employeeName(Employee employee) {
        return employee == null ? null : fullName(employee.getName(), employee.getLastname());
    }

    static String fullName(String name, String lastname) {
        return ((name == null ? "" : name) + " " + (lastname == null ? "" : lastname)).trim();
    }

    private static String firstWord(String text) {
        return text == null || text.isBlank() ? "" : text.trim().split("\\s+")[0];
    }

    private static String document(String type, String number) {
        if (number == null) {
            return null;
        }
        return type == null || type.isBlank() ? number : type + " " + number;
    }

    private static String pattern(String text) {
        String clean = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        return "%" + clean + "%";
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** true si la sesión puede registrar salas (Administrador Maestro). La pantalla oculta el botón si no. */
    public boolean canRegisterRooms() {
        return sessionContext.getRoleName().map(RoleNames.MASTER_ADMIN::equals).orElse(false);
    }

    private void requireAccess() {
        boolean allowed = sessionContext.getRoleName().map(AppModule.SALAS::isAllowedFor).orElse(false);
        if (!allowed) {
            throw new UnauthorizedActionException();
        }
    }

    private Long requireMasterAdmin() {
        if (!canRegisterRooms()) {
            throw new UnauthorizedActionException();
        }
        return currentUserId();
    }

    private Long currentUserId() {
        return sessionContext.getCurrentUser().map(user -> user.getId()).orElseThrow(UnauthorizedActionException::new);
    }
}
