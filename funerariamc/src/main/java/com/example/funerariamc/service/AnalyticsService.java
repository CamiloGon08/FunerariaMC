package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Employee;
import com.example.funerariamc.model.FinancialTransaction;
import com.example.funerariamc.model.FuneralSalesperson;
import com.example.funerariamc.model.Thanatopractor;
import com.example.funerariamc.model.WakeRoom;
import com.example.funerariamc.utils.enums.ContractStatus;
import com.example.funerariamc.utils.enums.SaleStatus;
import com.example.funerariamc.utils.enums.TransactionStatus;
import com.example.funerariamc.utils.enums.TransactionType;
import com.example.funerariamc.utils.enums.VaultStatus;
import com.example.funerariamc.persistence.FinancialTransactionRepository;
import com.example.funerariamc.persistence.SaleRepository;
import com.example.funerariamc.persistence.ServiceContractRepository;
import com.example.funerariamc.persistence.StaffOptionRepository;
import com.example.funerariamc.persistence.VaultRepository;
import com.example.funerariamc.persistence.WakeRoomRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Panel de análisis (solo el Administrador Maestro, solo lectura): servicios vendidos por tipo, valor promedio,
 * ocupación de salas por día de la semana, ocupación de bóvedas por cementerio y el desempeño del equipo
 * (ventas y comisiones de cada vendedor, preparaciones de cada tanatólogo). No cambia nada en la BD; solo
 * deja en la bitácora la exportación del PDF.
 */
@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_HOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    /** Hasta esta cantidad de días la gráfica "Servicios por tipo" muestra una barra por día. */
    static final int KIND_DAYS = 7;
    /** Hasta esta cantidad de días muestra una barra por semana; con más, una por mes. */
    static final int KIND_WEEKS_DAYS = 62;
    private static final DateTimeFormatter DAY_NAME = DateTimeFormatter.ofPattern("EEE d", Locale.forLanguageTag("es-CO"));
    public static final String[] KINDS = {"Contrato · plan", "Contrato · pensión", "Particular", "Venta de productos"};
    public static final String[] WEEKDAYS = {"Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom"};

    /** Indicadores de arriba. servicesPrevious es el período anterior de la misma duración. */
    public record Kpis(long services, long servicesPrevious, BigDecimal averageValue, int roomOccupancy,
                       int vaultOccupancy, long vaults, BigDecimal commissions, long commissionPeople) {
    }

    /**
     * Cantidad de ventas de cada tipo (en el orden de KINDS) en un tramo del período: un día, una semana o
     * un mes, según lo largo del período. from y to son el primer y el último día del tramo.
     */
    public record KindBucket(LocalDate from, LocalDate to, String label, long[] counts) {

        public long total() {
            long total = 0;
            for (long c : counts) {
                total += c;
            }
            return total;
        }
    }

    /** Bóvedas de un cementerio: ocupadas (incluye en mora y vencidas), en mora y disponibles. */
    public record CemeteryOccupancy(String name, long total, long occupied, long overdue, long available) {

        public int percent() {
            return total == 0 ? 0 : (int) Math.round(occupied * 100.0 / total);
        }
    }

    /** Ocupación de una sala por día de la semana (lunes a domingo), en porcentaje de horas. */
    public record RoomHeat(String room, int[] percents) {
    }

    public record SellerLine(String name, boolean active, long sales, BigDecimal sold, BigDecimal commission) {

        public BigDecimal average() {
            return sales == 0 ? BigDecimal.ZERO : sold.divide(BigDecimal.valueOf(sales), 0, RoundingMode.HALF_UP);
        }
    }

    public record ThanatoLine(String name, boolean active, long cases, BigDecimal commission) {
    }

    public record Overview(FinancePeriod period, String periodLabel, String previousLabel, Kpis kpis,
                           List<KindBucket> kindBuckets, List<CemeteryOccupancy> cemeteries, List<RoomHeat> rooms,
                           List<SellerLine> sellers, List<ThanatoLine> thanatologists, LocalDateTime generatedAt) {
    }

    private final SaleRepository saleRepository;
    private final ServiceContractRepository contractRepository;
    private final WakeRoomRepository roomRepository;
    private final VaultRepository vaultRepository;
    private final StaffOptionRepository staffRepository;
    private final FinancialTransactionRepository transactionRepository;
    private final AccessLogService accessLogService;
    private final SessionContext sessionContext;
    private final Clock clock;
    private final BrandingService brandingService;

    @Autowired
    public AnalyticsService(SaleRepository saleRepository, ServiceContractRepository contractRepository,
                            WakeRoomRepository roomRepository, VaultRepository vaultRepository,
                            StaffOptionRepository staffRepository, FinancialTransactionRepository transactionRepository,
                            AccessLogService accessLogService, SessionContext sessionContext, BrandingService brandingService) {
        this(saleRepository, contractRepository, roomRepository, vaultRepository, staffRepository,
                transactionRepository, accessLogService, sessionContext, Clock.system(AccessLogService.APP_ZONE), brandingService);
    }

    public AnalyticsService(SaleRepository saleRepository, ServiceContractRepository contractRepository,
                            WakeRoomRepository roomRepository, VaultRepository vaultRepository,
                            StaffOptionRepository staffRepository, FinancialTransactionRepository transactionRepository,
                            AccessLogService accessLogService, SessionContext sessionContext) {
        this(saleRepository, contractRepository, roomRepository, vaultRepository, staffRepository,
                transactionRepository, accessLogService, sessionContext, Clock.system(AccessLogService.APP_ZONE), null);
    }

    AnalyticsService(SaleRepository saleRepository, ServiceContractRepository contractRepository,
                     WakeRoomRepository roomRepository, VaultRepository vaultRepository,
                     StaffOptionRepository staffRepository, FinancialTransactionRepository transactionRepository,
                     AccessLogService accessLogService, SessionContext sessionContext, Clock clock) {
        this(saleRepository, contractRepository, roomRepository, vaultRepository, staffRepository,
                transactionRepository, accessLogService, sessionContext, clock, null);
    }

    AnalyticsService(SaleRepository saleRepository, ServiceContractRepository contractRepository,
                     WakeRoomRepository roomRepository, VaultRepository vaultRepository,
                     StaffOptionRepository staffRepository, FinancialTransactionRepository transactionRepository,
                     AccessLogService accessLogService, SessionContext sessionContext, Clock clock,
                     BrandingService brandingService) {
        this.saleRepository = saleRepository;
        this.contractRepository = contractRepository;
        this.roomRepository = roomRepository;
        this.vaultRepository = vaultRepository;
        this.staffRepository = staffRepository;
        this.transactionRepository = transactionRepository;
        this.accessLogService = accessLogService;
        this.sessionContext = sessionContext;
        this.clock = clock;
        this.brandingService = brandingService;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /**
     * Panel del período. Las lecturas no dependen entre sí, así que van en paralelo (cada una con su
     * propia transacción de solo lectura) y la pantalla espera solo la más lenta.
     */
    public Overview overview(FinancePeriod period) {
        requireMaster();
        LocalDateTime now = LocalDateTime.now(clock);

        var salesRead = ParallelReads.start(() -> activeSales(period));
        var previousRead = ParallelReads.start(() -> activeSales(period.previous()));
        var kindsRead = ParallelReads.start(() -> kindBuckets(period));
        var cemeteriesRead = ParallelReads.start(this::cemeteries);
        int[] roomTotal = new int[1];
        var roomsRead = ParallelReads.start(() -> rooms(period, now, roomTotal));
        var commissionsRead = ParallelReads.start(() -> commissions(period));
        var sellersRead = commissionsRead.thenCompose(c -> ParallelReads.start(() -> sellers(period, c)));
        var thanatologistsRead = commissionsRead.thenCompose(c -> ParallelReads.start(() -> thanatologists(period, c)));

        List<SaleRepository.SaleRow> services = ParallelReads.get(salesRead).stream()
                .filter(AnalyticsService::isService).toList();
        long servicesPrevious = ParallelReads.get(previousRead).stream().filter(AnalyticsService::isService).count();
        BigDecimal servicesTotal = services.stream().map(r -> orZero(r.getTotal())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = services.isEmpty() ? BigDecimal.ZERO
                : servicesTotal.divide(BigDecimal.valueOf(services.size()), 0, RoundingMode.HALF_UP);

        List<CemeteryOccupancy> cemeteries = ParallelReads.get(cemeteriesRead);
        long vaults = cemeteries.stream().mapToLong(CemeteryOccupancy::total).sum();
        long occupied = cemeteries.stream().mapToLong(CemeteryOccupancy::occupied).sum();
        int vaultPercent = vaults == 0 ? 0 : (int) Math.round(occupied * 100.0 / vaults);

        List<RoomHeat> rooms = ParallelReads.get(roomsRead);
        Map<Long, BigDecimal> commissionByPerson = ParallelReads.get(commissionsRead);
        BigDecimal commissions = commissionByPerson.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        Kpis kpis = new Kpis(services.size(), servicesPrevious, average, roomTotal[0], vaultPercent, vaults,
                commissions, commissionByPerson.size());
        return new Overview(period, period.label(), period.previous().label(), kpis, ParallelReads.get(kindsRead),
                cemeteries, rooms, ParallelReads.get(sellersRead), ParallelReads.get(thanatologistsRead), now);
    }

    private List<SaleRepository.SaleRow> activeSales(FinancePeriod period) {
        return saleRepository.findRowsBetween(period.dbFrom(), period.dbTo(), TransactionType.INCOME,
                        TransactionStatus.ACTIVE).stream()
                .filter(r -> r.getStatus() != SaleStatus.VOIDED).toList();
    }

    /** Un servicio funerario es toda venta que no es solo de productos (contrato o particular). */
    static boolean isService(SaleRepository.SaleRow r) {
        return ContractService.kindOf(r.getSaleType(), r.getPlanId()) != ContractService.SaleKind.PRODUCT;
    }

    static int kindIndex(SaleRepository.SaleRow r) {
        return switch (ContractService.kindOf(r.getSaleType(), r.getPlanId())) {
            case PLAN -> 0;
            case PENSION -> 1;
            case PRIVATE -> 2;
            case PRODUCT -> 3;
        };
    }

    /** Cómo se agrupan las barras de "Servicios por tipo" según lo largo del período. */
    public enum KindGrouping { DAY, WEEK, MONTH }

    public static KindGrouping groupingOf(FinancePeriod period) {
        if (period.days() <= KIND_DAYS) {
            return KindGrouping.DAY;
        }
        return period.days() <= KIND_WEEKS_DAYS ? KindGrouping.WEEK : KindGrouping.MONTH;
    }

    /**
     * Ventas de cada tipo dentro del período elegido, sin pasar de hoy: por día en 7 días, por semana
     * (desde el primer día del período, de 7 en 7) en un mes y por mes en un año. Así la gráfica cambia
     * con el filtro igual que los indicadores.
     */
    private List<KindBucket> kindBuckets(FinancePeriod period) {
        LocalDate today = today();
        LocalDate last = period.to().isAfter(today) ? today : period.to();
        List<KindBucket> buckets = new ArrayList<>();
        if (last.isBefore(period.from())) {
            return buckets;
        }
        KindGrouping grouping = groupingOf(period);
        for (LocalDate start = period.from(); !start.isAfter(last); ) {
            LocalDate end = switch (grouping) {
                case DAY -> start;
                case WEEK -> start.plusDays(6);
                case MONTH -> YearMonth.from(start).atEndOfMonth();
            };
            if (end.isAfter(last)) {
                end = last;
            }
            buckets.add(new KindBucket(start, end, bucketLabel(grouping, start, end), new long[KINDS.length]));
            start = end.plusDays(1);
        }
        for (SaleRepository.SaleRow r : activeSales(new FinancePeriod(period.from(), last))) {
            LocalDate day = AccessLogService.toAppTime(r.getSaleDate()).toLocalDate();
            for (KindBucket b : buckets) {
                if (!day.isBefore(b.from()) && !day.isAfter(b.to())) {
                    b.counts()[kindIndex(r)]++;
                    break;
                }
            }
        }
        return buckets;
    }

    /** "Jue 12" para un día, "1–7 nov" para una semana y "Nov" para un mes. */
    static String bucketLabel(KindGrouping grouping, LocalDate from, LocalDate to) {
        return switch (grouping) {
            case DAY -> {
                String text = from.format(DAY_NAME).replace(".", "");
                yield Character.toUpperCase(text.charAt(0)) + text.substring(1);
            }
            case WEEK -> {
                String month = shortMonth(YearMonth.from(to)).toLowerCase(Locale.ROOT);
                yield from.equals(to) ? to.getDayOfMonth() + " " + month
                        : from.getDayOfMonth() + "–" + to.getDayOfMonth() + " " + month;
            }
            case MONTH -> shortMonth(YearMonth.from(from));
        };
    }

    private List<CemeteryOccupancy> cemeteries() {
        Map<Long, long[]> byCemetery = new LinkedHashMap<>();
        Map<Long, String> names = new HashMap<>();
        for (VaultRepository.CemeteryCount c : vaultRepository.countByCemeteryAndStatus()) {
            names.put(c.getCemeteryId(), c.getCemeteryName());
            long[] v = byCemetery.computeIfAbsent(c.getCemeteryId(), k -> new long[3]);
            if (c.getStatus() == VaultStatus.AVAILABLE) {
                v[2] += c.getVaults();
            } else {
                v[0] += c.getVaults();
                if (c.getStatus() == VaultStatus.OVERDUE) {
                    v[1] += c.getVaults();
                }
            }
        }
        List<CemeteryOccupancy> list = new ArrayList<>();
        byCemetery.forEach((id, v) -> list.add(new CemeteryOccupancy(names.get(id), v[0] + v[2], v[0], v[1], v[2])));
        return list;
    }

    /**
     * Horas ocupadas de cada sala por día de la semana, sobre las horas transcurridas del período (hasta ahora).
     * Una velación sin salida registrada ocupa hasta el fin de su día de ingreso, como en Salas.
     */
    private List<RoomHeat> rooms(FinancePeriod period, LocalDateTime now, int[] overall) {
        LocalDateTime from = period.appFrom();
        LocalDateTime until = period.appTo().isAfter(now) ? now : period.appTo();
        List<WakeRoom> rooms = roomRepository.findAllByOrderByIdAsc();
        if (!until.isAfter(from) || rooms.isEmpty()) {
            overall[0] = 0;
            return rooms.stream().map(r -> new RoomHeat(r.getName(), new int[7])).toList();
        }
        double[] available = new double[7];
        for (LocalDateTime t = from; t.isBefore(until); t = t.toLocalDate().plusDays(1).atStartOfDay()) {
            LocalDateTime end = t.toLocalDate().plusDays(1).atStartOfDay();
            available[t.getDayOfWeek().getValue() - 1] += hours(t, end.isAfter(until) ? until : end);
        }
        Map<Long, double[]> used = new HashMap<>();
        for (ServiceContractRepository.WakeRow w : contractRepository.findWakes(from, until, ContractStatus.CANCELLED)) {
            LocalDateTime start = w.getWakeStartAt().isBefore(from) ? from : w.getWakeStartAt();
            LocalDateTime end = w.getWakeEndAt() != null ? w.getWakeEndAt()
                    : w.getWakeStartAt().toLocalDate().plusDays(1).atStartOfDay();
            if (end.isAfter(until)) {
                end = until;
            }
            double[] byDay = used.computeIfAbsent(w.getRoomId(), k -> new double[7]);
            for (LocalDateTime t = start; t.isBefore(end); t = t.toLocalDate().plusDays(1).atStartOfDay()) {
                LocalDateTime dayEnd = t.toLocalDate().plusDays(1).atStartOfDay();
                byDay[t.getDayOfWeek().getValue() - 1] += hours(t, dayEnd.isAfter(end) ? end : dayEnd);
            }
        }
        List<RoomHeat> heat = new ArrayList<>();
        double usedTotal = 0;
        double availableTotal = 0;
        for (WakeRoom room : rooms) {
            double[] byDay = used.getOrDefault(room.getId(), new double[7]);
            int[] percents = new int[7];
            for (int d = 0; d < 7; d++) {
                percents[d] = available[d] == 0 ? 0 : (int) Math.min(100, Math.round(byDay[d] * 100 / available[d]));
                usedTotal += Math.min(byDay[d], available[d]);
                availableTotal += available[d];
            }
            heat.add(new RoomHeat(room.getName(), percents));
        }
        overall[0] = availableTotal == 0 ? 0 : (int) Math.round(usedTotal * 100 / availableTotal);
        return heat;
    }

    private static double hours(LocalDateTime from, LocalDateTime to) {
        return Math.max(0, Duration.between(from, to).toMinutes() / 60.0);
    }

    /** Comisiones registradas (egresos vigentes "Comisión ...") en el período, por persona. */
    private Map<Long, BigDecimal> commissions(FinancePeriod period) {
        Map<Long, BigDecimal> byPerson = new HashMap<>();
        for (FinancialTransaction t : transactionRepository.findInRange(period.dbFrom(), period.dbTo())) {
            if (t.getTransactionType() == TransactionType.EXPENSE && t.getStatus() != TransactionStatus.VOIDED
                    && t.getEmployee() != null && t.getDescription() != null
                    && t.getDescription().startsWith(FinancialTransactionService.COMMISSION_PREFIX)) {
                byPerson.merge(t.getEmployee().getId(), t.getAmount(), BigDecimal::add);
            }
        }
        return byPerson;
    }

    private List<SellerLine> sellers(FinancePeriod period, Map<Long, BigDecimal> commissions) {
        Map<Long, SaleRepository.SellerTotal> totals = new HashMap<>();
        for (SaleRepository.SellerTotal t : saleRepository.sumBySeller(period.dbFrom(), period.dbTo(), SaleStatus.ACTIVE)) {
            totals.put(t.getEmployeeId(), t);
        }
        List<SellerLine> lines = new ArrayList<>();
        for (FuneralSalesperson s : staffRepository.findAllSalespeople()) {
            SaleRepository.SellerTotal t = totals.get(s.getId());
            boolean active = s.getStatus() == com.example.funerariamc.utils.enums.EmployeeStatus.ACTIVE;
            if (t == null && !active && !commissions.containsKey(s.getId())) {
                continue;
            }
            lines.add(new SellerLine(name(s), active, t == null ? 0 : t.getSales(), t == null ? BigDecimal.ZERO
                    : orZero(t.getTotal()), commissions.getOrDefault(s.getId(), BigDecimal.ZERO)));
        }
        lines.sort(Comparator.comparing(SellerLine::sold).reversed().thenComparing(SellerLine::name));
        return lines;
    }

    private List<ThanatoLine> thanatologists(FinancePeriod period, Map<Long, BigDecimal> commissions) {
        Map<Long, Long> cases = new HashMap<>();
        for (ServiceContractRepository.PreparationCount c : contractRepository.countPreparations(period.appFrom(),
                period.appTo(), ContractStatus.CANCELLED)) {
            cases.put(c.getThanatopractorId(), c.getCases());
        }
        List<ThanatoLine> lines = new ArrayList<>();
        for (Thanatopractor t : staffRepository.findAllThanatopractors()) {
            long n = cases.getOrDefault(t.getId(), 0L);
            boolean active = t.getStatus() == com.example.funerariamc.utils.enums.EmployeeStatus.ACTIVE;
            if (n == 0 && !active && !commissions.containsKey(t.getId())) {
                continue;
            }
            lines.add(new ThanatoLine(name(t), active, n, commissions.getOrDefault(t.getId(), BigDecimal.ZERO)));
        }
        lines.sort(Comparator.comparingLong(ThanatoLine::cases).reversed().thenComparing(ThanatoLine::name));
        return lines;
    }

    // ---------- exportación ----------

    /** Guarda el panel del período en PDF (primero en un temporal de la misma carpeta) y lo deja en la bitácora. */
    public void exportPdf(FinancePeriod period, Path target) {
        AppUser actor = requireMaster();
        if (target == null) {
            throw new InvalidDataException("Elija dónde guardar el archivo.");
        }
        Overview overview = overview(period);
        Path temp = null;
        try {
            temp = Files.createTempFile(target.toAbsolutePath().getParent(), "funeraria-", ".pdf");
            try (OutputStream out = Files.newOutputStream(temp)) {
                writePdf(overview, out, ExportDesign.brandOf(brandingService), BrandingService.fullNameOf(brandingService));
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failure) {
            log.warn("No se pudo generar el panel de análisis en PDF.", failure);
            try {
                if (temp != null) {
                    Files.deleteIfExists(temp);
                }
            } catch (IOException ignored) {
                // El temporal se pierde; lo importante es no entregar un archivo incompleto.
            }
            throw new InvalidDataException("No se pudo generar el archivo. Revise que la carpeta exista y que el archivo"
                    + " no esté abierto en otro programa, e intente de nuevo.");
        }
        accessLogService.record(actor.getId(), AccessLogService.ANALYTICS_REPORT_EXPORTED + period.from() + " " + period.to());
    }

    /** Nombre sugerido: "Panel_de_analisis_2026-10-01_2026-10-31.pdf". */
    public static String suggestedName(FinancePeriod period) {
        return "Panel_de_analisis_" + period.from() + "_" + period.to() + ".pdf";
    }

    static void writePdf(Overview o, OutputStream out) {
        writePdf(o, out, BrandingService.defaults(), BrandingService.defaults().fullName());
    }

    /**
     * PDF del panel con el diseño de la pantalla: banda con los cinco indicadores, tarjetas por sección,
     * mapa de calor de las salas y los colores, el logo y el nombre de la funeraria.
     */
    static void writePdf(Overview o, OutputStream out, BrandingService.BrandView brand, String funeralHome) {
        ExportDesign.PdfReport pdf = ExportDesign.pdf(brand, funeralHome, out, "Panel de análisis");
        Kpis k = o.kpis();
        long diff = k.services() - k.servicesPrevious();
        String change = k.servicesPrevious() == 0
                ? "Período anterior (" + o.previousLabel() + "): 0"
                : (diff >= 0 ? "+" : "-") + Math.abs(Math.round(diff * 100.0 / k.servicesPrevious()))
                + " % vs. " + o.previousLabel() + " (" + k.servicesPrevious() + ")";
        ExportDesign.Tone tone = k.servicesPrevious() == 0 ? ExportDesign.Tone.NEUTRAL
                : (diff >= 0 ? ExportDesign.Tone.UP : ExportDesign.Tone.DOWN);
        pdf.hero("Panel de análisis", "Período: " + o.periodLabel() + " · del " + o.period().from().format(DATE)
                        + " al " + o.period().to().format(DATE) + " · generado el " + o.generatedAt().format(DATE_HOUR),
                List.of(new ExportDesign.Kpi("SERVICIOS DEL PERÍODO", String.valueOf(k.services()), change, tone),
                        new ExportDesign.Kpi("VALOR PROMEDIO", ReportService.pesos(k.averageValue()), "por servicio vendido"),
                        new ExportDesign.Kpi("OCUPACIÓN DE SALAS", k.roomOccupancy() + " %", "de las horas del período"),
                        new ExportDesign.Kpi("OCUPACIÓN DE BÓVEDAS", k.vaultOccupancy() + " %", "de " + k.vaults() + " bóvedas, hoy"),
                        new ExportDesign.Kpi("COMISIONES", ReportService.pesos(k.commissions()),
                                k.commissionPeople() == 1 ? "1 persona" : k.commissionPeople() + " personas")),
                true);
        pdf.note("Solo lectura: las ventas anuladas no cuentan.");

        KindGrouping grouping = groupingOf(o.period());
        String unit = switch (grouping) {
            case DAY -> "Día";
            case WEEK -> "Semana";
            case MONTH -> "Mes";
        };
        ExportDesign.PdfTable kinds = pdf.table("Servicios y ventas por tipo", o.periodLabel(),
                new float[]{1.4f, 1.6f, 1.6f, 1.4f, 1.6f, 1}, unit, KINDS[0], KINDS[1], KINDS[2], KINDS[3], "Total");
        long[] totals = new long[KINDS.length];
        long all = 0;
        for (KindBucket m : o.kindBuckets()) {
            kinds.row(m.label(), String.valueOf(m.counts()[0]), String.valueOf(m.counts()[1]),
                    String.valueOf(m.counts()[2]), String.valueOf(m.counts()[3]), String.valueOf(m.total()));
            for (int i = 0; i < totals.length; i++) {
                totals[i] += m.counts()[i];
            }
            all += m.total();
        }
        kinds.total("Total", String.valueOf(totals[0]), String.valueOf(totals[1]), String.valueOf(totals[2]),
                String.valueOf(totals[3]), String.valueOf(all));
        kinds.end();

        ExportDesign.PdfTable vaults = pdf.table("Ocupación de bóvedas por cementerio", "Estado de hoy",
                new float[]{4, 1.4f, 1.4f, 1.4f, 1.4f, 1.3f}, "Cementerio", "Bóvedas", "Ocupadas", "En mora",
                "Disponibles", "%");
        for (CemeteryOccupancy c : o.cemeteries()) {
            vaults.bar(c.percent() / 100.0, c.name(), String.valueOf(c.total()), String.valueOf(c.occupied()),
                    String.valueOf(c.overdue()), String.valueOf(c.available()), c.percent() + " %");
        }
        if (o.cemeteries().isEmpty()) {
            vaults.empty("Sin bóvedas registradas");
        }
        vaults.end();

        ExportDesign.PdfTable heat = pdf.table("Ocupación de salas por día de la semana", "Porcentaje de horas ocupadas",
                new float[]{3, 1, 1, 1, 1, 1, 1, 1}, "Sala", WEEKDAYS[0], WEEKDAYS[1], WEEKDAYS[2], WEEKDAYS[3],
                WEEKDAYS[4], WEEKDAYS[5], WEEKDAYS[6]);
        for (RoomHeat r : o.rooms()) {
            heat.heat(r.room(), r.percents());
        }
        if (o.rooms().isEmpty()) {
            heat.empty("Sin salas registradas");
        }
        heat.end();

        ExportDesign.PdfTable sellers = pdf.table("Equipo · vendedores", "Ventas y comisión registrada en el período",
                new float[]{3.4f, 1, 2, 2, 2}, "Vendedor", "Ventas", "Total vendido", "Promedio", "Comisión registrada");
        for (SellerLine s : o.sellers()) {
            sellers.row(s.name() + (s.active() ? "" : " (inactivo)"), String.valueOf(s.sales()),
                    ReportService.pesos(s.sold()), ReportService.pesos(s.average()), ReportService.pesos(s.commission()));
        }
        if (o.sellers().isEmpty()) {
            sellers.empty("Sin vendedores");
        }
        sellers.end();

        ExportDesign.PdfTable thanato = pdf.table("Equipo · tanatólogos", "Preparaciones y comisión registrada",
                new float[]{4, 2, 2}, "Tanatólogo", "Preparaciones", "Comisión registrada");
        for (ThanatoLine t : o.thanatologists()) {
            thanato.row(t.name() + (t.active() ? "" : " (inactivo)"), String.valueOf(t.cases()),
                    ReportService.pesos(t.commission()));
        }
        if (o.thanatologists().isEmpty()) {
            thanato.empty("Sin tanatólogos");
        }
        thanato.end();
        pdf.close();
    }

    static String shortMonth(YearMonth month) {
        String name = FinancePeriod.monthName(month);
        String shortName = name.length() <= 4 ? name : name.substring(0, 3);
        return Character.toUpperCase(shortName.charAt(0)) + shortName.substring(1);
    }

    private static String name(Employee e) {
        return e.getName() + " " + e.getLastname();
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** Panel de análisis: solo el Administrador Maestro. */
    AppUser requireMaster() {
        boolean allowed = sessionContext.getRoleName().map(AppModule.ANALISIS::isAllowedFor).orElse(false);
        AppUser actor = sessionContext.getCurrentUser().orElseThrow(UnauthorizedActionException::new);
        if (!allowed || actor.getId() == null) {
            throw new UnauthorizedActionException();
        }
        return actor;
    }
}
