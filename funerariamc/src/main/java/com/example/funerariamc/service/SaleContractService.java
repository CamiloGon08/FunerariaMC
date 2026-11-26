package com.example.funerariamc.service;

import com.example.funerariamc.service.BrandingService.BrandView;
import com.example.funerariamc.service.ContractService.ContractInfo;
import com.example.funerariamc.service.ContractService.Line;
import com.example.funerariamc.service.ContractService.Payment;
import com.example.funerariamc.service.ContractService.SaleKind;
import com.example.funerariamc.service.ContractService.SaleRow;
import com.example.funerariamc.service.ContractService.SaleView;
import com.example.funerariamc.utils.enums.ContractStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Contrato de una venta ya guardada (servicio funerario con plan, por pensión, particular, o venta
 * de productos): junta el cliente, el servicio con su difunto, los conceptos y los abonos para verlo
 * en pantalla y exportarlo a PDF con el diseño de la marca. No agrega cláusulas: solo lo que está
 * en el sistema.
 */
@Service
public class SaleContractService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_HOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy h:mm a");

    private final ContractService contractService;
    private final BrandingService brandingService;

    public SaleContractService(ContractService contractService, BrandingService brandingService) {
        this.contractService = contractService;
        this.brandingService = brandingService;
    }

    /**
     * Todo lo que lleva el contrato. code es el número del contrato de servicio o, en una venta de
     * productos, "Venta 0148". service va en null en la venta de productos. issued es la fecha en que
     * se ve o se imprime.
     */
    public record SaleContract(Long saleId, String code, String title, SaleKind kind, LocalDate issued,
                               LocalDateTime saleDate, String state, boolean voided, String clientCaption,
                               String clientName, String clientDocument, String clientPhone, String salesperson,
                               ContractInfo service, List<Line> lines, BigDecimal total, BigDecimal paid,
                               BigDecimal balance, List<Payment> payments) {

        public boolean isService() {
            return service != null;
        }
    }

    /** Arma el contrato de la venta con lo que hay guardado hoy (los abonos anulados no van). */
    public SaleContract contract(Long saleId) {
        return of(contractService.detail(saleId), LocalDate.now(AccessLogService.APP_ZONE));
    }

    static SaleContract of(SaleView v, LocalDate issued) {
        SaleRow r = v.row();
        ContractInfo c = v.contract();
        String code = c != null && c.number() != null ? c.number() : "Venta " + r.number();
        List<Payment> payments = new ArrayList<>();
        for (Payment p : v.payments()) {
            if (!p.voided()) {
                payments.add(p);
            }
        }
        return new SaleContract(r.saleId(), code, title(r.kind(), c != null), r.kind(), issued, r.saleDate(),
                r.state().getLabel(), r.state() == ContractService.PaymentState.VOIDED,
                r.kind() == SaleKind.PENSION ? "Entidad pagadora" : "Cliente", v.clientName(), v.clientDocument(),
                v.clientPhone(), v.salesperson(), c, List.copyOf(v.lines()), r.total(), r.paid(), r.balance(),
                List.copyOf(payments));
    }

    /** La hoja que se ve en pantalla: cliente, servicio, conceptos con el total, y abonos con el saldo. */
    public static ContractDocument document(SaleContract k) {
        List<ContractDocument.Section> sections = new ArrayList<>();
        sections.add(new ContractDocument.Section(k.clientCaption().toUpperCase(java.util.Locale.ROOT), List.of(
                new ContractDocument.Row("Nombre", k.clientName()),
                new ContractDocument.Row("Documento", k.clientDocument()),
                new ContractDocument.Row("Teléfono", k.clientPhone()),
                new ContractDocument.Row("Vendedor", k.salesperson()))));
        ContractInfo c = k.service();
        if (c != null) {
            sections.add(new ContractDocument.Section("SERVICIO", List.of(
                    new ContractDocument.Row("Difunto", c.deceasedName()),
                    new ContractDocument.Row("Plan exequial", c.planLabel() == null ? "Sin plan" : c.planLabel()),
                    new ContractDocument.Row("Lo atiende", c.alliedHome() == null ? "La funeraria" : "Funeraria aliada · " + c.alliedHome()),
                    new ContractDocument.Row("Sala de velación", room(c)),
                    new ContractDocument.Row("Estado del contrato", contractStatus(c.status())))));
        }
        List<ContractDocument.Row> lines = new ArrayList<>();
        for (Line line : k.lines()) {
            lines.add(new ContractDocument.Row(line.description() + (line.quantity() > 1 ? " × " + line.quantity() : ""),
                    ReportService.pesos(line.subtotal())));
        }
        lines.add(new ContractDocument.Row("Total", ReportService.pesos(k.total()), true));
        sections.add(new ContractDocument.Section("CONCEPTOS", lines));
        List<ContractDocument.Row> paid = new ArrayList<>();
        for (Payment p : k.payments()) {
            paid.add(new ContractDocument.Row(p.date() == null ? "Abono" : "Abono del " + DATE.format(p.date()),
                    ReportService.pesos(p.amount())));
        }
        paid.add(new ContractDocument.Row("Abonado", ReportService.pesos(k.paid())));
        paid.add(new ContractDocument.Row(k.voided() ? "Saldo · venta anulada" : "Saldo", ReportService.pesos(k.balance()), true));
        sections.add(new ContractDocument.Section("ABONOS", paid));
        String number = k.code().startsWith("Venta ") ? k.code().substring(6) : k.code();
        return new ContractDocument(number, k.issued(), k.title().toUpperCase(java.util.Locale.ROOT),
                "Contrato " + (k.code().startsWith("Venta ") ? "de la venta " + number : number),
                k.clientName() + " · " + k.kind().getLabel(), sections, null, List.of(), suggestedName(k));
    }

    /** Título del contrato según el tipo de venta. */
    static String title(SaleKind kind, boolean hasService) {
        if (!hasService || kind == SaleKind.PRODUCT) {
            return "Contrato de venta";
        }
        return switch (kind) {
            case PLAN -> "Contrato de servicio funerario con plan exequial";
            case PENSION -> "Contrato de servicio funerario por pensión";
            default -> "Contrato de servicio funerario particular";
        };
    }

    /** Nombre que se sugiere al guardar: Contrato_CT-2026-0148.pdf o Contrato_Venta_0148.pdf. */
    public static String suggestedName(SaleContract contract) {
        return "Contrato_" + contract.code().replaceAll("[^A-Za-z0-9-]+", "_") + ".pdf";
    }

    /** Guarda el PDF en un archivo temporal para abrirlo con el visor del sistema ("Ver PDF"). */
    public Path temporaryPdf(SaleContract contract) {
        try {
            Path file = Files.createTempFile("Contrato_" + contract.saleId() + "_", ".pdf");
            file.toFile().deleteOnExit();
            exportPdf(contract, file);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo preparar el PDF del contrato.", e);
        }
    }

    /** Escribe el PDF del contrato en el archivo elegido. */
    public void exportPdf(SaleContract contract, Path file) {
        try (OutputStream out = Files.newOutputStream(file)) {
            writePdf(contract, out, ExportDesign.brandOf(brandingService), BrandingService.fullNameOf(brandingService));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar el contrato.", e);
        }
    }

    /**
     * El PDF con forma de contrato: membrete, presentación de las partes, datos del contratante y del
     * servicio, conceptos con su valor, abonos, cláusulas de la venta y las firmas.
     */
    static void writePdf(SaleContract k, OutputStream out, BrandView brand, String funeralHome) {
        ExportDesign.PdfReport pdf = ExportDesign.document(brand, funeralHome, out, "Contrato N.° " + k.code());
        pdf.letterhead(k.title(), "Expedido el " + DATE.format(k.issued())
                + (k.saleDate() == null ? "" : " · Venta registrada el " + DATE.format(k.saleDate()))
                + (k.voided() ? " · ANULADA" : ""));
        pdf.paragraph("Entre " + funeralHome + ", en adelante LA FUNERARIA, y " + orDash(k.clientName())
                + (k.clientDocument() == null || k.clientDocument().isBlank() ? "" : ", identificado(a) con " + k.clientDocument())
                + ", en adelante EL CONTRATANTE, se celebra el presente " + k.title().toLowerCase(java.util.Locale.ROOT)
                + ", que se rige por los datos y las cláusulas siguientes.");

        pdf.section(k.kind() == SaleKind.PENSION ? "Datos de la entidad pagadora" : "Datos del contratante");
        pdf.fields("Nombre o razón social", orDash(k.clientName()), "Documento", orDash(k.clientDocument()),
                "Teléfono", phone(k.clientPhone()), "Lo atiende", orDash(k.salesperson()));

        ContractInfo c = k.service();
        if (c != null) {
            pdf.section("Datos del servicio");
            pdf.fields("Difunto", orDash(c.deceasedName()), "Plan exequial", c.planLabel() == null ? "Sin plan" : c.planLabel(),
                    "Sala de velación", room(c), "Lo presta", c.alliedHome() == null ? "La funeraria" : "Funeraria aliada " + c.alliedHome());
        }

        pdf.section("Conceptos");
        if (k.lines().isEmpty()) {
            pdf.paragraph("Sin conceptos registrados.");
        }
        for (Line line : k.lines()) {
            pdf.amountLine(line.quantity() > 1 ? line.description() + " (" + line.quantity() + " × "
                    + ReportService.pesos(line.unitPrice()) + ")" : line.description(), ReportService.pesos(line.subtotal()), false);
        }
        pdf.amountLine("Valor total", ReportService.pesos(k.total()), true);

        pdf.section("Abonos");
        if (k.payments().isEmpty()) {
            pdf.paragraph("Todavía no hay abonos.");
        }
        for (Payment p : k.payments()) {
            pdf.amountLine((p.date() == null ? "" : DATE.format(p.date()) + " · ") + orDash(p.description()),
                    ReportService.pesos(p.amount()), false);
        }
        pdf.amountLine("Saldo pendiente", ReportService.pesos(k.balance()), true);

        clauses(pdf, k, funeralHome);
        pdf.close();
    }

    /** Cláusulas del contrato. Solo dicen lo que el sistema ya maneja; los valores salen de esta venta. */
    private static void clauses(ExportDesign.PdfReport pdf, SaleContract k, String funeralHome) {
        String[] numbers = {"PRIMERA", "SEGUNDA", "TERCERA", "CUARTA", "QUINTA", "SEXTA", "SÉPTIMA"};
        int n = 0;
        pdf.section("Cláusulas");
        pdf.clause(numbers[n++], "Objeto", k.isService()
                ? "LA FUNERARIA se compromete a prestar el servicio funerario y a entregar los conceptos de este contrato."
                : "LA FUNERARIA se compromete a entregar los conceptos de este contrato.");
        String pay = "El valor total es " + ReportService.pesos(k.total()) + ". EL CONTRATANTE ha abonado "
                + ReportService.pesos(k.paid());
        pdf.clause(numbers[n++], "Valor y pago", k.balance() == null || k.balance().signum() <= 0
                ? pay + " y el contrato queda pagado."
                : pay + " y el saldo de " + ReportService.pesos(k.balance()) + " se paga en abonos.");
        if (k.isService()) {
            if (k.kind() == SaleKind.PLAN) {
                pdf.clause(numbers[n++], "Plan exequial", "El servicio se presta con cargo al plan exequial del difunto. "
                        + "Si se escoge un cofre o un servicio de mayor valor que el incluido, la diferencia la paga EL CONTRATANTE.");
            }
            pdf.clause(numbers[n++], "Transporte", "El servicio incluye la carroza fúnebre y un bus.");
            pdf.clause(numbers[n++], "Documentos", "Para prestar el servicio se necesita el certificado de defunción. "
                    + "En las muertes violentas lo expide Medicina Legal.");
            boolean cremation = k.lines().stream().anyMatch(l -> l.description() != null
                    && l.description().toLowerCase(java.util.Locale.ROOT).contains("cremaci"));
            if (cremation) {
                pdf.clause(numbers[n++], "Cremación", "En una muerte violenta no se puede cremar hasta que la Fiscalía cierre el caso.");
            }
        }
        pdf.signatures("Para constancia se firma el " + DATE.format(k.issued()) + ".", java.util.List.of(
                java.util.List.of("LA FUNERARIA", funeralHome, k.salesperson() == null ? "Nombre y firma de quien atiende"
                        : "Atiende: " + k.salesperson()),
                java.util.List.of("EL CONTRATANTE", orDash(k.clientName()), orDash(k.clientDocument()))));
    }

    /** "Sala 2 · 24/11/2026 3:00 p. m." o "Sin sala asignada". */
    public static String room(ContractInfo c) {
        if (c.roomName() == null) {
            return "Sin sala asignada";
        }
        return c.roomName() + (c.wakeStart() == null ? "" : " · " + DATE_HOUR.format(c.wakeStart()));
    }

    public static String contractStatus(ContractStatus status) {
        if (status == null) {
            return "Activo";
        }
        return switch (status) {
            case ACTIVE -> "Activo";
            case COMPLETED -> "Completado";
            case CANCELLED -> "Cancelado";
        };
    }

    /** "3104567890" -> "310 456 7890": más fácil de leer y queda a la izquierda como el resto de la tabla. */
    private static String phone(String value) {
        if (value == null || value.isBlank()) {
            return "—";
        }
        String d = value.replaceAll("\\D", "");
        return d.length() == 10 ? d.substring(0, 3) + " " + d.substring(3, 6) + " " + d.substring(6) : value;
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
