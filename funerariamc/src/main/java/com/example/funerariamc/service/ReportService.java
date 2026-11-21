package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.FinancialTransaction;
import com.example.funerariamc.model.Sale;
import com.example.funerariamc.utils.enums.TransactionStatus;
import com.example.funerariamc.utils.enums.TransactionType;
import com.example.funerariamc.persistence.FinancialTransactionRepository;
import com.example.funerariamc.persistence.SaleRepository;
import com.example.funerariamc.service.FinancialTransactionService.MovementRow;
import com.example.funerariamc.service.FinancialTransactionService.Origin;
import com.example.funerariamc.service.BrandingService.BrandView;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Resumen financiero del Administrador Maestro: balance del período comparado con el anterior,
 * flujo de ingresos y egresos, ingresos por origen, balance por mes del año, comisiones y el
 * reporte de ventas. También lo exporta a PDF o a Excel para el auditor externo.
 * Los movimientos anulados no cuentan en ningún total.
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    private static final Locale ES = Locale.forLanguageTag("es-CO");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_HOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Totales del período y del anterior. change* es el porcentaje frente al anterior o null si no hay base. */
    public record Totals(BigDecimal income, BigDecimal expense, BigDecimal balance,
                         BigDecimal previousIncome, BigDecimal previousExpense, BigDecimal previousBalance) {

        public Double incomeChange() {
            return change(income, previousIncome);
        }

        public Double expenseChange() {
            return change(expense, previousExpense);
        }

        public Double balanceChange() {
            return change(balance, previousBalance);
        }

        private static Double change(BigDecimal now, BigDecimal before) {
            if (before == null || before.signum() == 0) {
                return null;
            }
            return now.subtract(before).multiply(BigDecimal.valueOf(100))
                    .divide(before.abs(), 1, RoundingMode.HALF_UP).doubleValue();
        }
    }

    /** Un punto de la gráfica de flujo: un día o un mes. */
    public record FlowPoint(String label, String longLabel, BigDecimal income, BigDecimal expense) {
    }

    /** Una porción de "Ingresos por origen" o de "Egresos por cuenta". */
    public record Slice(String label, BigDecimal amount) {
    }

    /** Un mes del año para "Balance por mes". */
    public record MonthBalance(String month, BigDecimal income, BigDecimal expense) {

        public BigDecimal balance() {
            return income.subtract(expense);
        }
    }

    /** Comisión registrada en el período. */
    public record CommissionLine(String name, String description, BigDecimal amount) {
    }

    /** Una venta del reporte de ventas. */
    public record SaleLine(String number, LocalDateTime date, String kind, String title, String subtitle,
                           BigDecimal total, BigDecimal paid, BigDecimal balance, boolean voided) {
    }

    public record Summary(FinancePeriod period, String periodLabel, String previousLabel, Totals totals,
                          BigDecimal receivable, long onCredit, boolean byDay, List<FlowPoint> flow,
                          List<Slice> incomeByOrigin, List<Slice> expenseByAccount, int year, List<MonthBalance> months,
                          List<CommissionLine> commissions, List<SaleLine> sales, List<MovementRow> movements,
                          LocalDateTime generatedAt) {

        public long activeMovements() {
            return movements.stream().filter(m -> !m.voided()).count();
        }

        public long voidedMovements() {
            return movements.stream().filter(MovementRow::voided).count();
        }
    }

    public enum Format {
        PDF("pdf"),
        EXCEL("xlsx");

        private final String extension;

        Format(String extension) {
            this.extension = extension;
        }

        public String getExtension() {
            return extension;
        }
    }

    private final FinancialTransactionRepository transactionRepository;
    private final SaleRepository saleRepository;
    private final FinancialTransactionService transactionService;
    private final AccessLogService accessLogService;
    private final SessionContext sessionContext;
    private final Clock clock;
    private BrandingService brandingService;

    /** Nombre de la funeraria para los mensajes; Spring lo pone al arrancar (las pruebas usan El Edén). */
    @Autowired(required = false)
    void setBrandingService(BrandingService brandingService) {
        this.brandingService = brandingService;
    }

    @Autowired
    public ReportService(FinancialTransactionRepository transactionRepository, SaleRepository saleRepository,
                         FinancialTransactionService transactionService, AccessLogService accessLogService,
                         SessionContext sessionContext) {
        this(transactionRepository, saleRepository, transactionService, accessLogService, sessionContext,
                Clock.system(AccessLogService.APP_ZONE));
    }

    /** Las pruebas fijan la fecha de hoy con un Clock. */
    ReportService(FinancialTransactionRepository transactionRepository, SaleRepository saleRepository,
                  FinancialTransactionService transactionService, AccessLogService accessLogService,
                  SessionContext sessionContext, Clock clock) {
        this.transactionRepository = transactionRepository;
        this.saleRepository = saleRepository;
        this.transactionService = transactionService;
        this.accessLogService = accessLogService;
        this.sessionContext = sessionContext;
        this.clock = clock;
    }

    /** Hoy en Colombia (para elegir el período inicial). */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /**
     * Todo el resumen. Las 8 lecturas no dependen entre sí, así que van en paralelo (cada una con su
     * propia transacción de solo lectura) y la pantalla espera solo la más lenta.
     */
    public Summary summary(FinancePeriod period) {
        requireMaster();
        FinancePeriod previous = period.previous();
        int year = period.to().getYear();
        FinancePeriod wholeYear = FinancePeriod.year(year);
        YearMonth now = YearMonth.now(clock);
        var foundRead = ParallelReads.start(() -> transactionRepository.findInRange(period.dbFrom(), period.dbTo()));
        var planIdsRead = ParallelReads.start(() -> transactionRepository.findPlanPaymentIds(period.dbFrom(), period.dbTo()));
        var cremationIdsRead = ParallelReads.start(() -> transactionRepository.findCremationPurchaseIds(period.dbFrom(),
                period.dbTo()));
        var alliedIdsRead = ParallelReads.start(() -> transactionService.alliedPaymentIds(period));
        var beforeRead = ParallelReads.start(() -> transactionRepository.sumByType(previous.dbFrom(), previous.dbTo(),
                TransactionStatus.ACTIVE));
        var monthsRead = ParallelReads.start(() -> transactionRepository.sumByMonth(wholeYear.dbFrom(), wholeYear.dbTo()));
        var kpisRead = ParallelReads.start(() -> saleRepository.findKpis(FinancePeriod.month(now.minusMonths(1)).dbFrom(),
                FinancePeriod.month(now).dbFrom(), FinancePeriod.month(now).dbTo()));
        var salesRead = ParallelReads.start(() -> saleRepository.findRowsBetween(period.dbFrom(), period.dbTo(),
                TransactionType.INCOME, TransactionStatus.ACTIVE));

        List<FinancialTransaction> found = ParallelReads.get(foundRead);
        FinancialTransactionService.Owners owners = new FinancialTransactionService.Owners(
                new HashSet<>(ParallelReads.get(planIdsRead)), new HashSet<>(ParallelReads.get(cremationIdsRead)),
                new HashSet<>(ParallelReads.get(alliedIdsRead)));
        List<MovementRow> movements = found.stream().map(t -> FinancialTransactionService.row(t, owners)).toList();

        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        Map<LocalDate, BigDecimal[]> byBucket = new TreeMap<>();
        Map<String, BigDecimal> origins = new LinkedHashMap<>();
        for (String label : List.of("Contrato · plan", "Contrato · pensión", "Particular", "Venta de productos",
                "Cuotas de planes", "Otros ingresos")) {
            origins.put(label, BigDecimal.ZERO);
        }
        // Egresos por cuenta: los automáticos (comisiones, aliadas, cremaciones) quedan en "Sin cuenta".
        Map<String, BigDecimal> accounts = new TreeMap<>();
        BigDecimal noAccount = BigDecimal.ZERO;
        List<CommissionLine> commissions = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            FinancialTransaction t = found.get(i);
            MovementRow row = movements.get(i);
            if (row.voided()) {
                continue;
            }
            BigDecimal amount = row.amount() == null ? BigDecimal.ZERO : row.amount();
            LocalDate day = row.date() == null ? period.from() : row.date().toLocalDate();
            LocalDate bucket = period.byDay() ? day : day.withDayOfMonth(1);
            BigDecimal[] sums = byBucket.computeIfAbsent(bucket, d -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            if (row.type() == TransactionType.INCOME) {
                income = income.add(amount);
                sums[0] = sums[0].add(amount);
                origins.merge(originLabel(t, row.origin()), amount, BigDecimal::add);
            } else {
                expense = expense.add(amount);
                sums[1] = sums[1].add(amount);
                if (row.account() == null) {
                    noAccount = noAccount.add(amount);
                } else {
                    accounts.merge(row.account(), amount, BigDecimal::add);
                }
                if (row.origin() == Origin.COMMISSION) {
                    commissions.add(new CommissionLine(row.registeredBy(), row.description(), amount));
                }
            }
        }

        Map<TransactionType, BigDecimal> before = new EnumMap<>(TransactionType.class);
        for (FinancialTransactionRepository.TypeTotal total : ParallelReads.get(beforeRead)) {
            before.put(total.getType(), total.getTotal());
        }
        BigDecimal prevIncome = before.getOrDefault(TransactionType.INCOME, BigDecimal.ZERO);
        BigDecimal prevExpense = before.getOrDefault(TransactionType.EXPENSE, BigDecimal.ZERO);
        Totals totals = new Totals(income, expense, income.subtract(expense), prevIncome, prevExpense,
                prevIncome.subtract(prevExpense));

        BigDecimal[][] monthSums = new BigDecimal[12][2];
        for (BigDecimal[] pair : monthSums) {
            pair[0] = BigDecimal.ZERO;
            pair[1] = BigDecimal.ZERO;
        }
        for (FinancialTransactionRepository.MonthTotal total : ParallelReads.get(monthsRead)) {
            int index = total.getMonth() - 1;
            if (index >= 0 && index < 12) {
                monthSums[index]["INCOME".equals(total.getType()) ? 0 : 1] = total.getTotal();
            }
        }
        List<MonthBalance> months = new ArrayList<>();
        for (int m = 0; m < 12; m++) {
            months.add(new MonthBalance(shortMonth(m + 1), monthSums[m][0], monthSums[m][1]));
        }

        SaleRepository.SaleKpis kpis = ParallelReads.get(kpisRead);

        List<SaleLine> sales = ParallelReads.get(salesRead).stream()
                .map(ContractService::row)
                .map(r -> new SaleLine(r.number(), r.saleDate(), r.kind().getLabel(), r.title(), r.subtitle(),
                        r.total(), r.paid(), r.balance(), r.state() == ContractService.PaymentState.VOIDED))
                .toList();

        List<Slice> slices = origins.entrySet().stream()
                .filter(e -> e.getValue().signum() > 0)
                .map(e -> new Slice(e.getKey(), e.getValue()))
                .toList();
        List<Slice> byAccount = new ArrayList<>();
        accounts.forEach((name, amount) -> byAccount.add(new Slice(name, amount)));
        if (noAccount.signum() > 0) {
            byAccount.add(new Slice(FinancialTransactionService.NO_ACCOUNT, noAccount));
        }
        return new Summary(period, period.label(), previous.label(), totals,
                kpis == null ? BigDecimal.ZERO : orZero(kpis.getReceivable()), kpis == null ? 0 : kpis.getOnCredit(),
                period.byDay(), flow(period, byBucket), slices, byAccount, year, months, commissions, sales, movements,
                LocalDateTime.now(clock));
    }

    /** Todos los días (o meses) del período, aunque no tengan movimientos, para que la gráfica no salte. */
    private static List<FlowPoint> flow(FinancePeriod period, Map<LocalDate, BigDecimal[]> byBucket) {
        List<FlowPoint> points = new ArrayList<>();
        if (period.byDay()) {
            // Dentro de un solo mes basta el número del día; si cruza meses, "d/M" para que no se repita.
            DateTimeFormatter shortDay = DateTimeFormatter.ofPattern(
                    YearMonth.from(period.from()).equals(YearMonth.from(period.to())) ? "d" : "d/M", ES);
            DateTimeFormatter longDay = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", ES);
            for (LocalDate d = period.from(); !d.isAfter(period.to()); d = d.plusDays(1)) {
                BigDecimal[] sums = byBucket.getOrDefault(d, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                points.add(new FlowPoint(d.format(shortDay).replace(".", ""), capitalize(d.format(longDay)), sums[0], sums[1]));
            }
        } else {
            for (YearMonth m = YearMonth.from(period.from()); !m.isAfter(YearMonth.from(period.to())); m = m.plusMonths(1)) {
                BigDecimal[] sums = byBucket.getOrDefault(m.atDay(1), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                points.add(new FlowPoint(shortMonth(m.getMonthValue()) + (YearMonth.from(period.from()).getYear()
                        == YearMonth.from(period.to()).getYear() ? "" : " " + (m.getYear() % 100)),
                        capitalize(FinancePeriod.monthName(m)) + " " + m.getYear(), sums[0], sums[1]));
            }
        }
        return points;
    }

    /** Origen de un ingreso para la gráfica de dona. */
    private static String originLabel(FinancialTransaction t, Origin origin) {
        if (origin == Origin.PLAN_INSTALLMENT) {
            return "Cuotas de planes";
        }
        Sale sale = t.getRelatedSale();
        if (sale == null) {
            return "Otros ingresos";
        }
        Long planId = sale.getServiceContract() == null || sale.getServiceContract().getFuneralPlan() == null
                ? null : sale.getServiceContract().getFuneralPlan().getId();
        return switch (ContractService.kindOf(sale.getSaleType(), planId)) {
            case PLAN -> "Contrato · plan";
            case PENSION -> "Contrato · pensión";
            case PRIVATE -> "Particular";
            case PRODUCT -> "Venta de productos";
        };
    }

    // ---------- exportar ----------

    /**
     * Genera el archivo del resumen (balance, ingresos por origen, balance por mes, comisiones,
     * reporte de ventas y movimientos). Se escribe primero en un archivo temporal y solo si sale
     * completo reemplaza el destino: nunca queda un archivo a medias.
     */
    public void export(FinancePeriod period, Format format, Path target) {
        AppUser actor = requireMaster();
        if (target == null || format == null) {
            throw new InvalidDataException("Elija dónde guardar el archivo.");
        }
        Summary summary = summary(period);
        Path temp = null;
        try {
            Path folder = target.toAbsolutePath().getParent();
            temp = Files.createTempFile(folder, "funeraria-", "." + format.getExtension());
            try (OutputStream out = Files.newOutputStream(temp)) {
                if (format == Format.PDF) {
                    writePdf(summary, out, ExportDesign.brandOf(brandingService), BrandingService.fullNameOf(brandingService));
                } else {
                    writeExcel(summary, out, ExportDesign.brandOf(brandingService), BrandingService.fullNameOf(brandingService));
                }
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failure) {
            log.warn("No se pudo generar el resumen financiero en {}.", format, failure);
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
        accessLogService.record(actor.getId(), AccessLogService.FINANCIAL_REPORT_EXPORTED + format.name() + " "
                + period.from() + " " + period.to());
    }

    /** Nombre sugerido: "Finanzas_2026-10-01_2026-10-31.pdf". */
    public static String suggestedName(FinancePeriod period, Format format) {
        return "Finanzas_" + period.from() + "_" + period.to() + "." + format.getExtension();
    }

    static void writePdf(Summary s, OutputStream out) {
        writePdf(s, out, BrandingService.defaults(), BrandingService.defaults().fullName());
    }

    /**
     * PDF del resumen con el diseño de la pantalla de Finanzas: banda con los cuatro indicadores y una
     * tarjeta por sección, con los colores, el logo y el nombre de la funeraria.
     */
    static void writePdf(Summary s, OutputStream out, BrandView brand, String funeralHome) {
        ExportDesign.PdfReport pdf = ExportDesign.pdf(brand, funeralHome, out, "Resumen financiero");
        pdf.hero("Resumen financiero", periodText(s) + " · generado el " + s.generatedAt().format(DATE_HOUR),
                summaryKpis(s), true);
        pdf.note("Los movimientos anulados no cuentan en los totales.");

        ExportDesign.PdfTable balance = pdf.table("Balance del período", "Comparado con " + s.previousLabel(),
                new float[]{3, 2, 2}, "Concepto", "Período", "Período anterior");
        balance.row("Ingresos", pesos(s.totals().income()), pesos(s.totals().previousIncome()));
        balance.row("Egresos", pesos(s.totals().expense()), pesos(s.totals().previousExpense()));
        balance.total("Saldo", pesos(s.totals().balance()), pesos(s.totals().previousBalance()));
        balance.row("Por cobrar hoy (" + credit(s.onCredit()) + ")", pesos(s.receivable()), "");
        balance.end();

        ExportDesign.PdfTable origins = pdf.table("Ingresos por origen", "De dónde entró el dinero del período",
                new float[]{4, 2, 1.4f}, "Origen", "Valor", "%");
        for (Slice slice : s.incomeByOrigin()) {
            origins.bar(fraction(slice.amount(), s.totals().income()), slice.label(), pesos(slice.amount()),
                    percent(slice.amount(), s.totals().income()));
        }
        if (s.incomeByOrigin().isEmpty()) {
            origins.empty("Sin ingresos en el período");
        }
        origins.end();

        ExportDesign.PdfTable accounts = pdf.table("Egresos por cuenta", "En qué se fue el dinero del período",
                new float[]{4, 2, 1.4f}, "Cuenta", "Valor", "%");
        for (Slice slice : s.expenseByAccount()) {
            accounts.bar(fraction(slice.amount(), s.totals().expense()), slice.label(), pesos(slice.amount()),
                    percent(slice.amount(), s.totals().expense()));
        }
        if (s.expenseByAccount().isEmpty()) {
            accounts.empty("Sin egresos en el período");
        }
        accounts.end();

        ExportDesign.PdfTable months = pdf.table("Balance por mes · " + s.year(), "Ingresos, egresos y saldo de cada mes",
                new float[]{2, 2, 2, 2}, "Mes", "Ingresos", "Egresos", "Saldo");
        BigDecimal yearIncome = BigDecimal.ZERO;
        BigDecimal yearExpense = BigDecimal.ZERO;
        for (MonthBalance m : s.months()) {
            months.row(m.month(), pesos(m.income()), pesos(m.expense()), pesos(m.balance()));
            yearIncome = yearIncome.add(orZero(m.income()));
            yearExpense = yearExpense.add(orZero(m.expense()));
        }
        months.total("Total " + s.year(), pesos(yearIncome), pesos(yearExpense), pesos(yearIncome.subtract(yearExpense)));
        months.end();

        ExportDesign.PdfTable commissions = pdf.table("Comisiones registradas", "Registradas como egreso en el período",
                new float[]{3, 5, 2}, "Persona", "Detalle", "Valor");
        for (CommissionLine c : s.commissions()) {
            commissions.row(nullToEmpty(c.name()), nullToEmpty(c.description()), pesos(c.amount()));
        }
        if (s.commissions().isEmpty()) {
            commissions.empty("Sin comisiones en el período");
        }
        commissions.end();

        List<SaleLine> active = s.sales().stream().filter(l -> !l.voided()).toList();
        ExportDesign.PdfTable kinds = pdf.table("Reporte de ventas", "Ventas vigentes del período por tipo",
                new float[]{3, 1, 2}, "Tipo de venta", "Ventas", "Total");
        if (active.isEmpty()) {
            kinds.empty("No hay ventas en el período seleccionado.");
        } else {
            salesByKind(active).forEach((kind, v) -> kinds.row(kind, v[0].toPlainString(), pesos(v[1])));
            kinds.total("Total", String.valueOf(active.size()),
                    pesos(active.stream().map(SaleLine::total).reduce(BigDecimal.ZERO, BigDecimal::add)));
        }
        kinds.end();
        if (!active.isEmpty()) {
            ExportDesign.PdfTable sales = pdf.table("Detalle de ventas", null,
                    new float[]{1.1f, 1.6f, 1.8f, 3.4f, 1.7f, 1.7f, 1.7f},
                    "N.°", "Fecha", "Tipo", "Difunto / cliente", "Total", "Abonado", "Saldo");
            for (SaleLine l : active) {
                sales.row(l.number(), l.date() == null ? "" : l.date().format(DATE), l.kind(),
                        l.title() + " · " + l.subtitle(), pesos(l.total()), pesos(l.paid()), pesos(l.balance()));
            }
            sales.end();
        }

        ExportDesign.PdfTable moves = pdf.table("Movimientos del período",
                s.activeMovements() + " vigentes · " + s.voidedMovements() + " anulados",
                new float[]{1.3f, 1.8f, 1.2f, 3.4f, 2f, 1.8f, 1.2f},
                "Código", "Fecha", "Tipo", "Descripción", "Cuenta", "Valor", "Estado");
        for (MovementRow m : s.movements()) {
            String[] values = {m.code(), m.date() == null ? "" : m.date().format(DATE_HOUR),
                    m.type() == TransactionType.INCOME ? "Ingreso" : "Egreso", nullToEmpty(m.description()),
                    accountText(m), pesos(m.amount()), m.voided() ? "Anulado" : "Vigente"};
            if (m.voided()) {
                moves.voided(values);
            } else {
                moves.row(values);
            }
        }
        if (s.movements().isEmpty()) {
            moves.empty("Sin movimientos en el período");
        }
        moves.end();
        pdf.close();
    }

    /** Los cuatro indicadores de la banda de Finanzas, con el mismo texto y tono que en la pantalla. */
    static List<ExportDesign.Kpi> summaryKpis(Summary s) {
        Totals t = s.totals();
        return List.of(
                new ExportDesign.Kpi("INGRESOS", pesos(t.income()), change(t.incomeChange(), s.previousLabel()),
                        tone(t.incomeChange(), false)),
                new ExportDesign.Kpi("EGRESOS", pesos(t.expense()), change(t.expenseChange(), s.previousLabel()),
                        tone(t.expenseChange(), true)),
                new ExportDesign.Kpi("BALANCE", pesos(t.balance()), change(t.balanceChange(), s.previousLabel()),
                        tone(t.balanceChange(), false)),
                new ExportDesign.Kpi("POR COBRAR HOY", pesos(s.receivable()), credit(s.onCredit())));
    }

    /** "+20 % vs. septiembre 2026" o "sin datos de septiembre 2026". */
    static String change(Double percent, String previous) {
        if (percent == null) {
            return "sin datos de " + previous;
        }
        String sign = percent > 0 ? "+" : "";
        return sign + new java.text.DecimalFormat("0.#", java.text.DecimalFormatSymbols.getInstance(ES)).format(percent)
                + " % vs. " + previous;
    }

    /** Verde si mejora y terracota si empeora; para egresos, subir es empeorar. */
    static ExportDesign.Tone tone(Double percent, boolean expense) {
        if (percent == null || percent == 0) {
            return ExportDesign.Tone.NEUTRAL;
        }
        boolean good = expense ? percent < 0 : percent > 0;
        return good ? ExportDesign.Tone.UP : ExportDesign.Tone.DOWN;
    }

    private static String credit(long count) {
        return count + (count == 1 ? " venta a crédito" : " ventas a crédito");
    }

    private static String periodText(Summary s) {
        return "Período: " + s.periodLabel() + " · del " + s.period().from().format(DATE) + " al "
                + s.period().to().format(DATE);
    }

    private static double fraction(BigDecimal part, BigDecimal whole) {
        if (part == null || whole == null || whole.signum() == 0) {
            return 0;
        }
        return part.doubleValue() / whole.doubleValue();
    }

    /** Cuenta de un movimiento: vacía en ingresos y "Sin cuenta" en egresos que no la tienen. */
    static String accountText(MovementRow m) {
        if (m.type() != TransactionType.EXPENSE) {
            return "";
        }
        return m.account() == null ? FinancialTransactionService.NO_ACCOUNT : m.account();
    }

    static void writeExcel(Summary s, OutputStream out) throws IOException {
        writeExcel(s, out, BrandingService.defaults(), BrandingService.defaults().fullName());
    }

    /**
     * Excel del resumen con el mismo diseño: banda de la marca en cada hoja, tarjetas de indicadores en
     * Balance y tablas con encabezado del color principal, filas alternas y filtro.
     */
    static void writeExcel(Summary s, OutputStream out, BrandView brand, String funeralHome) throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            ExportDesign.XlsxReport x = ExportDesign.xlsx(brand, funeralHome, book, "Resumen financiero");
            String period = periodText(s);

            XSSFSheet balance = book.createSheet("Balance");
            x.widths(balance, 40, 22, 22, 22);
            int r = x.banner(balance, "Resumen financiero", period + " · los movimientos anulados no cuentan", 4);
            r = x.kpis(balance, r, 0, 1, summaryKpis(s), true);
            r = x.section(balance, r, 0, "Balance del período", "Comparado con " + s.previousLabel());
            ExportDesign.XlsxTable t = x.table(balance, r, 0, "Concepto", "Período", "Período anterior");
            t.row().text("Ingresos").money(s.totals().income()).money(s.totals().previousIncome());
            t.row().text("Egresos").money(s.totals().expense()).money(s.totals().previousExpense());
            t.totalRow().text("Saldo").money(s.totals().balance()).money(s.totals().previousBalance());
            t.row().text("Por cobrar hoy (" + credit(s.onCredit()) + ")").money(s.receivable()).text("");
            r = t.end(false);
            r = x.section(balance, r, 0, "Ingresos por origen", null);
            t = x.table(balance, r, 0, "Origen", "Valor", "%");
            for (Slice slice : s.incomeByOrigin()) {
                t.row().text(slice.label()).money(slice.amount()).percent(fraction(slice.amount(), s.totals().income()));
            }
            if (s.incomeByOrigin().isEmpty()) {
                t.empty("Sin ingresos en el período");
            }
            r = t.end(false);
            r = x.section(balance, r, 0, "Egresos por cuenta", null);
            t = x.table(balance, r, 0, "Cuenta", "Valor", "%");
            for (Slice slice : s.expenseByAccount()) {
                t.row().text(slice.label()).money(slice.amount()).percent(fraction(slice.amount(), s.totals().expense()));
            }
            if (s.expenseByAccount().isEmpty()) {
                t.empty("Sin egresos en el período");
            }
            t.end(false);

            XSSFSheet months = book.createSheet("Balance por mes");
            x.widths(months, 22, 22, 22, 22);
            r = x.banner(months, "Balance por mes · " + s.year(), "Ingresos, egresos y saldo de cada mes", 4);
            t = x.table(months, r, 0, "Mes " + s.year(), "Ingresos", "Egresos", "Saldo");
            BigDecimal yearIncome = BigDecimal.ZERO;
            BigDecimal yearExpense = BigDecimal.ZERO;
            for (MonthBalance m : s.months()) {
                t.row().text(m.month()).money(m.income()).money(m.expense()).money(m.balance());
                yearIncome = yearIncome.add(orZero(m.income()));
                yearExpense = yearExpense.add(orZero(m.expense()));
            }
            t.totalRow().text("Total " + s.year()).money(yearIncome).money(yearExpense).money(yearIncome.subtract(yearExpense));
            t.end(false);
            months.createFreezePane(0, r + 1);

            XSSFSheet commissions = book.createSheet("Comisiones");
            x.widths(commissions, 30, 50, 18);
            r = x.banner(commissions, "Comisiones registradas", period, 3);
            t = x.table(commissions, r, 0, "Persona", "Detalle", "Valor");
            for (CommissionLine c : s.commissions()) {
                t.row().text(nullToEmpty(c.name())).text(nullToEmpty(c.description())).money(c.amount());
            }
            if (s.commissions().isEmpty()) {
                t.empty("Sin comisiones en el período");
            }
            t.end(!s.commissions().isEmpty());
            commissions.createFreezePane(0, r + 1);

            XSSFSheet sales = book.createSheet("Ventas");
            x.widths(sales, 10, 18, 20, 46, 16, 16, 16, 12);
            r = x.banner(sales, "Reporte de ventas", period, 8);
            t = x.table(sales, r, 0, "N.°", "Fecha", "Tipo", "Difunto / cliente", "Total", "Abonado", "Saldo", "Estado");
            for (SaleLine l : s.sales()) {
                (l.voided() ? t.voidedRow() : t.row()).text(l.number()).date(l.date()).text(l.kind())
                        .text(l.title() + " · " + l.subtitle()).money(l.total()).money(l.paid()).money(l.balance())
                        .text(l.voided() ? "Anulada" : "Vigente");
            }
            if (s.sales().isEmpty()) {
                t.empty("No hay ventas en el período seleccionado.");
            }
            t.end(!s.sales().isEmpty());
            sales.createFreezePane(0, r + 1);

            XSSFSheet moves = book.createSheet("Movimientos");
            x.widths(moves, 12, 18, 10, 22, 46, 22, 22, 16, 11);
            r = x.banner(moves, "Movimientos del período", period + " · " + s.activeMovements() + " vigentes · "
                    + s.voidedMovements() + " anulados", 9);
            t = x.table(moves, r, 0, "Código", "Fecha", "Tipo", "Origen", "Descripción", "Cuenta", "Registró", "Valor",
                    "Estado");
            for (MovementRow m : s.movements()) {
                (m.voided() ? t.voidedRow() : t.row()).text(m.code()).date(m.date())
                        .text(m.type() == TransactionType.INCOME ? "Ingreso" : "Egreso").text(m.origin().getLabel())
                        .text(nullToEmpty(m.description())).text(accountText(m)).text(nullToEmpty(m.registeredBy()))
                        .money(m.amount()).text(m.voided() ? "Anulado" : "Vigente");
            }
            if (s.movements().isEmpty()) {
                t.empty("Sin movimientos en el período");
            }
            t.end(!s.movements().isEmpty());
            moves.createFreezePane(0, r + 1);
            book.write(out);
        }
    }

    private static Map<String, BigDecimal[]> salesByKind(List<SaleLine> lines) {
        Map<String, BigDecimal[]> byKind = new LinkedHashMap<>();
        for (SaleLine l : lines) {
            BigDecimal[] v = byKind.computeIfAbsent(l.kind(), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            v[0] = v[0].add(BigDecimal.ONE);
            v[1] = v[1].add(orZero(l.total()));
        }
        return byKind;
    }

    // ---------- piezas ----------

    /** Resumen y exportación: solo el Administrador Maestro. */
    private AppUser requireMaster() {
        boolean master = sessionContext.getRoleName().map(RoleNames.MASTER_ADMIN::equals).orElse(false);
        AppUser actor = sessionContext.getCurrentUser().orElseThrow(UnauthorizedActionException::new);
        if (!master || actor.getId() == null) {
            throw new UnauthorizedActionException();
        }
        return actor;
    }

    /** El resumen lo ve solo el Administrador Maestro; la Secretaría ve Movimientos y Comisiones. */
    public boolean canSeeSummary() {
        return sessionContext.getRoleName().map(RoleNames.MASTER_ADMIN::equals).orElse(false);
    }

    static String pesos(BigDecimal value) {
        // El signo va antes del símbolo (-$ 9.997.000), igual que en las tablas del Excel.
        BigDecimal rounded = orZero(value).setScale(0, RoundingMode.HALF_UP);
        String digits = NumberFormat.getIntegerInstance(ES).format(rounded.abs());
        return (rounded.signum() < 0 ? "-$ " : "$ ") + digits;
    }

    private static String percent(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return "";
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 0, RoundingMode.HALF_UP) + " %";
    }

    private static String shortMonth(int month) {
        String name = java.time.Month.of(month).getDisplayName(TextStyle.SHORT, ES).replace(".", "");
        return capitalize(name);
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
