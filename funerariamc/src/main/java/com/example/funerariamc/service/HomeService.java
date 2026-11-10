package com.example.funerariamc.service;

import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.FinancialTransaction;
import com.example.funerariamc.model.InventoryItem;
import com.example.funerariamc.model.Vault;
import com.example.funerariamc.model.enums.CremationBatchStatus;
import com.example.funerariamc.model.enums.TransactionStatus;
import com.example.funerariamc.model.enums.TransactionType;
import com.example.funerariamc.model.enums.TransportStatus;
import com.example.funerariamc.model.enums.VaultStatus;
import com.example.funerariamc.persistence.CremationBatchRepository;
import com.example.funerariamc.persistence.FinancialTransactionRepository;
import com.example.funerariamc.persistence.InventoryItemRepository;
import com.example.funerariamc.persistence.VaultRepository;
import com.example.funerariamc.service.TransportService.Transfer;
import com.example.funerariamc.service.TransportService.Unit;
import com.example.funerariamc.service.TransportService.VehicleCard;
import com.example.funerariamc.service.WakeRoomService.Board;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Inicio: cómo va la funeraria en este momento. Reúne, solo para leer, las salas de hoy, los traslados y
 * cremaciones del día, las alertas (documentos de vehículos vencidos, bóvedas en mora, inventario en el mínimo)
 * y, solo para el Administrador Maestro, los abonos recibidos hoy. Lo ven el Administrador Maestro y la Secretaría.
 */
@Service
public class HomeService {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");

    /** Gravedad de una alerta: ALERT en rojo, WARN en caqui. */
    public enum Level { ALERT, WARN }

    /** Una alerta del panel de la derecha. */
    public record Alert(Level level, String title, String text) {
    }

    /** Una fila de "Servicios y salidas de hoy". */
    public record AgendaItem(LocalDateTime at, String deceased, String kind, String destination, String vehicles,
                             String state, boolean active, boolean done) {
    }

    /** Abonos (ingresos vigentes) del día; null para la Secretaría. */
    public record Payments(BigDecimal total, long count) {
    }

    public record Today(LocalDateTime now, Board rooms, int servicesNow, int wakesNow, int transfersInRoute,
                        int transfersToday, int transfersDone, int cremationsToday, String nextCremation,
                        Payments payments, List<Alert> alerts, List<AgendaItem> agenda) {
    }

    private final WakeRoomService wakeRoomService;
    private final TransportService transportService;
    private final CremationBatchRepository batchRepository;
    private final VaultRepository vaultRepository;
    private final InventoryItemRepository inventoryRepository;
    private final FinancialTransactionRepository transactionRepository;
    private final SessionContext sessionContext;
    private final Clock clock;

    @Autowired
    public HomeService(WakeRoomService wakeRoomService, TransportService transportService,
                       CremationBatchRepository batchRepository, VaultRepository vaultRepository,
                       InventoryItemRepository inventoryRepository, FinancialTransactionRepository transactionRepository,
                       SessionContext sessionContext) {
        this(wakeRoomService, transportService, batchRepository, vaultRepository, inventoryRepository,
                transactionRepository, sessionContext, Clock.system(AccessLogService.APP_ZONE));
    }

    /** Las pruebas fijan la fecha y la hora con un Clock. */
    HomeService(WakeRoomService wakeRoomService, TransportService transportService,
                CremationBatchRepository batchRepository, VaultRepository vaultRepository,
                InventoryItemRepository inventoryRepository, FinancialTransactionRepository transactionRepository,
                SessionContext sessionContext, Clock clock) {
        this.wakeRoomService = wakeRoomService;
        this.transportService = transportService;
        this.batchRepository = batchRepository;
        this.vaultRepository = vaultRepository;
        this.inventoryRepository = inventoryRepository;
        this.transactionRepository = transactionRepository;
        this.sessionContext = sessionContext;
        this.clock = clock;
    }

    /** Inicio con datos: Administrador Maestro y Secretaría. Los demás roles ven el saludo. */
    public boolean canSeeHome() {
        return sessionContext.getRoleName()
                .map(role -> RoleNames.MASTER_ADMIN.equals(role) || RoleNames.SECRETARY.equals(role)).orElse(false);
    }

    public boolean canSeeMoney() {
        return sessionContext.getRoleName().map(RoleNames.MASTER_ADMIN::equals).orElse(false);
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /**
     * Resumen del día. Las lecturas no dependen entre sí, así que van en paralelo (cada una con su
     * propia transacción de solo lectura) y la pantalla espera solo la más lenta.
     */
    public Today today() {
        if (!canSeeHome()) {
            throw new UnauthorizedActionException();
        }
        LocalDateTime now = now();
        LocalDate today = now.toLocalDate();
        boolean money = canSeeMoney();
        var boardRead = ParallelReads.start(() -> wakeRoomService.board(today));
        var transportRead = ParallelReads.start(transportService::screen);
        var batchesRead = ParallelReads.start(() -> batchRepository.findBatchesBetween(today.atStartOfDay(),
                today.plusDays(1).atStartOfDay()));
        var paymentsRead = money ? ParallelReads.start(() -> payments(today))
                : java.util.concurrent.CompletableFuture.<Payments>completedFuture(null);
        var vaultsRead = ParallelReads.start(this::overdueVaults);
        var lowRead = ParallelReads.start(() -> inventoryRepository.findAtOrBelowMinimum(PageRequest.of(0, 3)));

        Board board = ParallelReads.get(boardRead);
        int wakesNow = board.kpis().inWake();
        TransportService.Screen transport = ParallelReads.get(transportRead);
        List<Transfer> todays = transport.transfers().stream()
                .filter(t -> t.scheduledAt() != null && t.scheduledAt().toLocalDate().equals(today)
                        || t.status() == TransportStatus.IN_PROGRESS)
                .toList();
        int inRoute = (int) todays.stream().filter(t -> t.status() == TransportStatus.IN_PROGRESS).count();
        int done = (int) todays.stream().filter(t -> t.status() == TransportStatus.COMPLETED).count();

        List<CremationBatchRepository.DayBatch> batches = ParallelReads.get(batchesRead);
        int cremations = (int) batches.stream().mapToLong(CremationBatchRepository.DayBatch::getCremations).sum();
        String next = batches.stream()
                .filter(b -> b.getStatus() == CremationBatchStatus.SCHEDULED && b.getScheduledDate().isAfter(now))
                .findFirst()
                .map(b -> (b.getFurnaceCode() == null ? "" : b.getFurnaceCode() + " · ") + "próxima "
                        + b.getScheduledDate().format(HOUR))
                .orElse(null);
        Payments payments = ParallelReads.get(paymentsRead);
        List<Alert> alerts = alerts(transport.vehicles(), todays, ParallelReads.get(vaultsRead), ParallelReads.get(lowRead));

        List<AgendaItem> agenda = new ArrayList<>();
        for (Transfer t : todays) {
            agenda.add(new AgendaItem(t.startedAt() != null && t.scheduledAt() == null ? t.startedAt() : t.scheduledAt(),
                    t.deceasedName(), "Traslado", t.destination() == null ? "—" : t.destination(), plates(t),
                    t.status().getDisplayName(), t.status() == TransportStatus.IN_PROGRESS,
                    t.status() == TransportStatus.COMPLETED));
        }
        for (CremationBatchRepository.DayBatch b : batches) {
            String state = switch (b.getStatus()) {
                case SCHEDULED -> "Programada";
                case IN_PROGRESS -> "En horno";
                case FINISHED -> "Terminada";
            };
            agenda.add(new AgendaItem(b.getScheduledDate(), b.getCremations() == 1 ? "1 difunto"
                    : b.getCremations() + " difuntos", "Cremación · lote L-%04d".formatted(b.getBatchId()),
                    b.getFurnaceCode() == null ? "Horno" : b.getFurnaceCode(), "—", state,
                    b.getStatus() == CremationBatchStatus.IN_PROGRESS, b.getStatus() == CremationBatchStatus.FINISHED));
        }
        agenda.sort(Comparator.comparing(AgendaItem::at, Comparator.nullsLast(Comparator.naturalOrder())));

        return new Today(now, board, wakesNow + inRoute, wakesNow, inRoute, todays.size(), done, cremations, next,
                payments, alerts, agenda);
    }

    /** Abonos de hoy (ingresos vigentes); solo los ve el Administrador Maestro. */
    private Payments payments(LocalDate today) {
        BigDecimal total = BigDecimal.ZERO;
        long count = 0;
        FinancePeriod day = new FinancePeriod(today, today);
        for (FinancialTransaction t : transactionRepository.findInRange(day.dbFrom(), day.dbTo())) {
            if (t.getTransactionType() == TransactionType.INCOME && t.getStatus() != TransactionStatus.VOIDED) {
                total = total.add(t.getAmount());
                count++;
            }
        }
        return new Payments(total, count);
    }

    /** Alerta de bóvedas en mora con las tres primeras, o null si no hay. */
    private Alert overdueVaults() {
        long overdue = vaultRepository.countByStatus(VaultStatus.OVERDUE);
        if (overdue == 0) {
            return null;
        }
        List<Vault> first = vaultRepository.findByStatusWithCemetery(VaultStatus.OVERDUE, PageRequest.of(0, 3));
        String codes = first.stream().map(v -> v.getCode() + " (" + v.getCemetery().getName() + ")")
                .collect(Collectors.joining(", "));
        return new Alert(Level.ALERT, overdue == 1 ? "1 bóveda en mora" : overdue + " bóvedas en mora",
                "Arriendo vencido sin renovar: " + codes + (overdue > first.size() ? " y otras." : "."));
    }

    private List<Alert> alerts(List<VehicleCard> vehicles, List<Transfer> todays, Alert vaults,
                               List<InventoryItem> low) {
        List<Alert> alerts = new ArrayList<>();
        for (VehicleCard v : vehicles) {
            if (v.soat() != null && v.soat().expired()) {
                alerts.add(new Alert(Level.ALERT, "SOAT vencido · " + v.plate(),
                        "El vehículo no puede asignarse a traslados hasta renovar el SOAT."));
            }
            if (v.technicalReview() != null && v.technicalReview().expired()) {
                alerts.add(new Alert(Level.ALERT, "Revisión técnico-mecánica vencida · " + v.plate(),
                        "El vehículo no puede asignarse a traslados hasta renovarla."));
            }
        }
        for (Transfer t : todays) {
            boolean missingDriver = t.units().stream().anyMatch(u -> u.driverId() == null);
            if (missingDriver && t.status() == TransportStatus.SCHEDULED) {
                alerts.add(new Alert(Level.WARN, "Traslado " + t.contractNumber() + " sin conductor",
                        "Sale hoy a las " + t.scheduledAt().format(HOUR) + (t.destination() == null ? "" : " hacia "
                                + t.destination()) + "."));
            }
        }
        if (vaults != null) {
            alerts.add(vaults);
        }
        for (InventoryItem item : low) {
            int stock = item.getStockQuantity() == null ? 0 : item.getStockQuantity();
            alerts.add(new Alert(Level.WARN, "Inventario en el mínimo · " + item.getName(),
                    "Quedan " + stock + (stock == 1 ? " unidad" : " unidades") + "; el mínimo es "
                            + (item.getMinimumStock() == null ? 0 : item.getMinimumStock()) + "."));
        }
        return alerts;
    }

    private static String plates(Transfer t) {
        String plates = t.units().stream().map(Unit::plate).filter(p -> p != null && !p.isBlank())
                .collect(Collectors.joining(" + "));
        return plates.isEmpty() ? "Sin vehículo" : plates;
    }
}
