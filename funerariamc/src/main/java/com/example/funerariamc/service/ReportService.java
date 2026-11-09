package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.FinancialTransaction;
import com.example.funerariamc.model.Sale;
import com.example.funerariamc.model.enums.TransactionStatus;
import com.example.funerariamc.model.enums.TransactionType;
import com.example.funerariamc.persistence.FinancialTransactionRepository;
import com.example.funerariamc.persistence.SaleRepository;
import com.example.funerariamc.service.FinancialTransactionService.MovementRow;
import com.example.funerariamc.service.FinancialTransactionService.Origin;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Color;
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
                    writePdf(summary, out);
                } else {
                    writeExcel(summary, out);
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
        Document document = new Document(PageSize.A4, 36, 36, 40, 40);
        PdfWriter.getInstance(document, out);
        document.open();
        Color forest = new Color(0x1E, 0x3A, 0x2E);
        Color muted = new Color(0x6A, 0x73, 0x66);
        Font title = new Font(Font.TIMES_ROMAN, 22, Font.BOLD, forest);
        Font h2 = new Font(Font.TIMES_ROMAN, 14, Font.BOLD, forest);
        Font small = new Font(Font.HELVETICA, 9, Font.NORMAL, muted);
        Font body = new Font(Font.HELVETICA, 9, Font.NORMAL, Color.BLACK);
        Font bold = new Font(Font.HELVETICA, 9, Font.BOLD, Color.BLACK);

        document.add(new Paragraph("Funeraria El Edén · Resumen financiero", title));
        document.add(new Paragraph("Período: " + s.periodLabel() + " (" + s.period().from().format(DATE) + " a "
                + s.period().to().format(DATE) + "). Generado el " + s.generatedAt().format(DATE_HOUR)
                + ". Los movimientos anulados no cuentan en los totales.", small));

        section(document, "Balance del período", h2);
        PdfPTable balance = table(new float[]{3, 2, 2}, bold, "Concepto", "Período", "Período anterior");
        cells(balance, body, "Ingresos", pesos(s.totals().income()), pesos(s.totals().previousIncome()));
        cells(balance, body, "Egresos", pesos(s.totals().expense()), pesos(s.totals().previousExpense()));
        cells(balance, bold, "Saldo", pesos(s.totals().balance()), pesos(s.totals().previousBalance()));
        cells(balance, body, "Por cobrar hoy (" + s.onCredit() + " ventas a crédito)", pesos(s.receivable()), "");
        document.add(balance);

        section(document, "Ingresos por origen", h2);
        PdfPTable origins = table(new float[]{4, 2, 1}, bold, "Origen", "Valor", "%");
        for (Slice slice : s.incomeByOrigin()) {
            cells(origins, body, slice.label(), pesos(slice.amount()), percent(slice.amount(), s.totals().income()));
        }
        if (s.incomeByOrigin().isEmpty()) {
            cells(origins, body, "Sin ingresos en el período", "", "");
        }
        document.add(origins);

        section(document, "Egresos por cuenta", h2);
        PdfPTable accounts = table(new float[]{4, 2, 1}, bold, "Cuenta", "Valor", "%");
        for (Slice slice : s.expenseByAccount()) {
            cells(accounts, body, slice.label(), pesos(slice.amount()), percent(slice.amount(), s.totals().expense()));
        }
        if (s.expenseByAccount().isEmpty()) {
            cells(accounts, body, "Sin egresos en el período", "", "");
        }
        document.add(accounts);

        section(document, "Balance por mes · " + s.year(), h2);
        PdfPTable months = table(new float[]{2, 2, 2, 2}, bold, "Mes", "Ingresos", "Egresos", "Saldo");
        for (MonthBalance m : s.months()) {
            cells(months, body, m.month(), pesos(m.income()), pesos(m.expense()), pesos(m.balance()));
        }
        document.add(months);

        section(document, "Comisiones registradas en el período", h2);
        PdfPTable commissions = table(new float[]{3, 5, 2}, bold, "Persona", "Detalle", "Valor");
        for (CommissionLine c : s.commissions()) {
            cells(commissions, body, nullToEmpty(c.name()), nullToEmpty(c.description()), pesos(c.amount()));
        }
        if (s.commissions().isEmpty()) {
            cells(commissions, body, "Sin comisiones en el período", "", "");
        }
        document.add(commissions);

        section(document, "Reporte de ventas", h2);
        List<SaleLine> active = s.sales().stream().filter(l -> !l.voided()).toList();
        if (active.isEmpty()) {
            document.add(new Paragraph("No hay ventas en el período seleccionado.", body));
        } else {
            Map<String, BigDecimal[]> byKind = salesByKind(active);
            PdfPTable kinds = table(new float[]{3, 1, 2}, bold, "Tipo de venta", "Ventas", "Total");
            byKind.forEach((kind, v) -> cells(kinds, body, kind, v[0].toPlainString(), pesos(v[1])));
            cells(kinds, bold, "Total", String.valueOf(active.size()),
                    pesos(active.stream().map(SaleLine::total).reduce(BigDecimal.ZERO, BigDecimal::add)));
            document.add(kinds);
            document.add(new Paragraph(" ", small));
            PdfPTable sales = table(new float[]{1.1f, 1.6f, 1.8f, 3.4f, 1.7f, 1.7f, 1.7f}, bold,
                    "N.°", "Fecha", "Tipo", "Difunto / cliente", "Total", "Abonado", "Saldo");
            for (SaleLine l : active) {
                cells(sales, body, l.number(), l.date() == null ? "" : l.date().format(DATE), l.kind(),
                        l.title() + " · " + l.subtitle(), pesos(l.total()), pesos(l.paid()), pesos(l.balance()));
            }
            document.add(sales);
        }

        section(document, "Movimientos del período", h2);
        PdfPTable moves = table(new float[]{1.3f, 1.8f, 1.2f, 3.4f, 2f, 1.8f, 1.2f}, bold,
                "Código", "Fecha", "Tipo", "Descripción", "Cuenta", "Valor", "Estado");
        for (MovementRow m : s.movements()) {
            cells(moves, body, m.code(), m.date() == null ? "" : m.date().format(DATE_HOUR),
                    m.type() == TransactionType.INCOME ? "Ingreso" : "Egreso", nullToEmpty(m.description()),
                    accountText(m), pesos(m.amount()), m.voided() ? "Anulado" : "Vigente");
        }
        if (s.movements().isEmpty()) {
            cells(moves, body, "", "", "", "Sin movimientos en el período", "", "", "");
        }
        document.add(moves);
        document.close();
    }

    /** Cuenta de un movimiento: vacía en ingresos y "Sin cuenta" en egresos que no la tienen. */
    static String accountText(MovementRow m) {
        if (m.type() != TransactionType.EXPENSE) {
            return "";
        }
        return m.account() == null ? FinancialTransactionService.NO_ACCOUNT : m.account();
    }

    static void section(Document document, String text, Font font) {
        Paragraph p = new Paragraph(text, font);
        p.setSpacingBefore(14);
        p.setSpacingAfter(6);
        document.add(p);
    }

    static PdfPTable table(float[] widths, Font font, String... headers) {
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        for (String header : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(header, font));
            cell.setBackgroundColor(new Color(0xEE, 0xF0, 0xE6));
            cell.setPadding(5);
            table.addCell(cell);
        }
        table.setHeaderRows(1);
        return table;
    }

    static void cells(PdfPTable table, Font font, String... values) {
        for (int i = 0; i < values.length; i++) {
            PdfPCell cell = new PdfPCell(new Phrase(values[i], font));
            cell.setPadding(4);
            if (i > 0 && values[i].startsWith("$")) {
                cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            }
            table.addCell(cell);
        }
    }

    static void writeExcel(Summary s, OutputStream out) throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            DataFormat format = book.createDataFormat();
            CellStyle header = book.createCellStyle();
            org.apache.poi.ss.usermodel.Font bold = book.createFont();
            bold.setBold(true);
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);
            CellStyle money = book.createCellStyle();
            money.setDataFormat(format.getFormat("\"$\" #,##0"));
            CellStyle date = book.createCellStyle();
            date.setDataFormat(format.getFormat("dd/mm/yyyy hh:mm"));

            Sheet balance = book.createSheet("Balance");
            text(balance, 0, "Funeraria El Edén · Resumen financiero");
            text(balance, 1, "Período: " + s.periodLabel() + " (" + s.period().from().format(DATE) + " a "
                    + s.period().to().format(DATE) + "). Los movimientos anulados no cuentan.");
            head(balance, 3, header, "Concepto", "Período", "Período anterior");
            moneyRow(balance, 4, money, "Ingresos", s.totals().income(), s.totals().previousIncome());
            moneyRow(balance, 5, money, "Egresos", s.totals().expense(), s.totals().previousExpense());
            moneyRow(balance, 6, money, "Saldo", s.totals().balance(), s.totals().previousBalance());
            moneyRow(balance, 7, money, "Por cobrar hoy (" + s.onCredit() + " ventas a crédito)", s.receivable());
            head(balance, 9, header, "Ingresos por origen", "Valor");
            int r = 10;
            for (Slice slice : s.incomeByOrigin()) {
                moneyRow(balance, r++, money, slice.label(), slice.amount());
            }
            head(balance, ++r, header, "Egresos por cuenta", "Valor");
            r++;
            for (Slice slice : s.expenseByAccount()) {
                moneyRow(balance, r++, money, slice.label(), slice.amount());
            }
            autosize(balance, 3);

            Sheet months = book.createSheet("Balance por mes");
            head(months, 0, header, "Mes " + s.year(), "Ingresos", "Egresos", "Saldo");
            r = 1;
            for (MonthBalance m : s.months()) {
                moneyRow(months, r++, money, m.month(), m.income(), m.expense(), m.balance());
            }
            autosize(months, 4);

            Sheet commissions = book.createSheet("Comisiones");
            head(commissions, 0, header, "Persona", "Detalle", "Valor");
            r = 1;
            for (CommissionLine c : s.commissions()) {
                Row row = commissions.createRow(r++);
                row.createCell(0).setCellValue(nullToEmpty(c.name()));
                row.createCell(1).setCellValue(nullToEmpty(c.description()));
                moneyCell(row, 2, money, c.amount());
            }
            autosize(commissions, 3);

            Sheet sales = book.createSheet("Ventas");
            head(sales, 0, header, "N.°", "Fecha", "Tipo", "Difunto / cliente", "Total", "Abonado", "Saldo", "Estado");
            r = 1;
            for (SaleLine l : s.sales()) {
                Row row = sales.createRow(r++);
                row.createCell(0).setCellValue(l.number());
                dateCell(row, 1, date, l.date());
                row.createCell(2).setCellValue(l.kind());
                row.createCell(3).setCellValue(l.title() + " · " + l.subtitle());
                moneyCell(row, 4, money, l.total());
                moneyCell(row, 5, money, l.paid());
                moneyCell(row, 6, money, l.balance());
                row.createCell(7).setCellValue(l.voided() ? "Anulada" : "Vigente");
            }
            if (s.sales().isEmpty()) {
                text(sales, 1, "No hay ventas en el período seleccionado.");
            }
            autosize(sales, 8);

            Sheet moves = book.createSheet("Movimientos");
            head(moves, 0, header, "Código", "Fecha", "Tipo", "Origen", "Descripción", "Cuenta", "Registró", "Valor",
                    "Estado");
            r = 1;
            for (MovementRow m : s.movements()) {
                Row row = moves.createRow(r++);
                row.createCell(0).setCellValue(m.code());
                dateCell(row, 1, date, m.date());
                row.createCell(2).setCellValue(m.type() == TransactionType.INCOME ? "Ingreso" : "Egreso");
                row.createCell(3).setCellValue(m.origin().getLabel());
                row.createCell(4).setCellValue(nullToEmpty(m.description()));
                row.createCell(5).setCellValue(accountText(m));
                row.createCell(6).setCellValue(nullToEmpty(m.registeredBy()));
                moneyCell(row, 7, money, m.amount());
                row.createCell(8).setCellValue(m.voided() ? "Anulado" : "Vigente");
            }
            autosize(moves, 9);
            book.write(out);
        }
    }

    private static void text(Sheet sheet, int rowIndex, String value) {
        Row row = sheet.getRow(rowIndex) == null ? sheet.createRow(rowIndex) : sheet.getRow(rowIndex);
        row.createCell(0).setCellValue(value);
    }

    private static void head(Sheet sheet, int rowIndex, CellStyle style, String... values) {
        Row row = sheet.createRow(rowIndex);
        for (int i = 0; i < values.length; i++) {
            var cell = row.createCell(i);
            cell.setCellValue(values[i]);
            cell.setCellStyle(style);
        }
    }

    private static void moneyRow(Sheet sheet, int rowIndex, CellStyle money, String label, BigDecimal... values) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(label);
        for (int i = 0; i < values.length; i++) {
            moneyCell(row, i + 1, money, values[i]);
        }
    }

    private static void moneyCell(Row row, int column, CellStyle money, BigDecimal value) {
        var cell = row.createCell(column);
        cell.setCellValue(value == null ? 0 : value.doubleValue());
        cell.setCellStyle(money);
    }

    private static void dateCell(Row row, int column, CellStyle style, LocalDateTime value) {
        var cell = row.createCell(column);
        if (value != null) {
            cell.setCellValue(value);
            cell.setCellStyle(style);
        }
    }

    private static void autosize(Sheet sheet, int columns) {
        for (int i = 0; i < columns; i++) {
            sheet.setColumnWidth(i, Math.max(sheet.getColumnWidth(i), 14 * 256));
        }
        sheet.setColumnWidth(0, Math.max(sheet.getColumnWidth(0), 34 * 256));
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
        return "$ " + NumberFormat.getIntegerInstance(ES).format(orZero(value).setScale(0, RoundingMode.HALF_UP));
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
