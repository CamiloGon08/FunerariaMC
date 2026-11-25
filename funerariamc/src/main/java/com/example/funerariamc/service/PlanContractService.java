package com.example.funerariamc.service;

import com.example.funerariamc.service.BrandingService.BrandView;
import com.example.funerariamc.service.FuneralPlanService.BeneficiaryInfo;
import com.example.funerariamc.service.FuneralPlanService.PlanCard;
import com.example.funerariamc.service.FuneralPlanService.PlanDetail;
import com.example.funerariamc.service.FuneralPlanService.PlanOffer;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.persistence.FuneralPlanRepository;
import com.example.funerariamc.utils.enums.PlanPaymentStatus;
import com.example.funerariamc.utils.enums.PlanStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Contrato de afiliación a un plan exequial ya guardado: junta los datos del plan (titular, tipo,
 * cuota, inicio, carencia, personas y lo que incluye según Parámetros) para verlo en pantalla y
 * exportarlo a PDF con el diseño de la marca. Las cláusulas solo dicen lo que el sistema ya maneja.
 */
@Service
public class PlanContractService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final FuneralPlanService planService;
    private final BrandingService brandingService;
    private final FuneralPlanRepository planRepository;
    private final SessionContext sessionContext;
    private final PlanParameters parameters;

    public PlanContractService(FuneralPlanService planService, BrandingService brandingService,
                               FuneralPlanRepository planRepository, SessionContext sessionContext,
                               PlanParameters parameters) {
        this.planService = planService;
        this.brandingService = brandingService;
        this.planRepository = planRepository;
        this.sessionContext = sessionContext;
        this.parameters = parameters;
    }

    /**
     * Lo que ofrece el plan según Parámetros: ataúd, sala, cuántas personas entran sin recargo, los
     * recargos y qué pasa con el atraso. Un valor queda en null si todavía no está configurado.
     */
    public record PlanTerms(boolean premium, BigDecimal coffinPrice, BigDecimal roomSurcharge, Integer beneficiaryLimit,
                            BigDecimal nonDirectSurcharge, BigDecimal extraSurcharge, Integer arrearsResetMonths,
                            List<Integer> letterDays) {

        /** Sin valores de Parámetros (solo se sabe si el plan es premium). */
        public static PlanTerms empty(boolean premium) {
            return new PlanTerms(premium, null, null, null, null, null, null, List.of());
        }
    }

    /** Plan para el buscador de "Contratos de planes": código y titular, y al lado el tipo y el documento. */
    public record PlanOption(Long planId, String label, String detail) {
    }

    /**
     * Planes cuyo titular, documento o código contienen el texto (sin tildes ni mayúsculas); con el
     * texto vacío, los más recientes. Máximo 20.
     */
    @Transactional(readOnly = true)
    public List<PlanOption> searchPlans(String text) {
        boolean allowed = sessionContext.getRoleName().map(AppModule.PLANES::isAllowedFor).orElse(false);
        if (!allowed) {
            throw new UnauthorizedActionException();
        }
        // Igual que los demás buscadores: el nombre o el documento deben empezar por lo escrito
        // (al comienzo de una palabra); el código del plan se encuentra en cualquier parte.
        // El código solo se busca cuando lo escrito lleva números (con «l» no deben salir todos los PL-).
        String code = WordSearch.clean(text).replace(" ", "");
        boolean byCode = code.chars().anyMatch(Character::isDigit);
        LocalDate month = LocalDate.now(AccessLogService.APP_ZONE).withDayOfMonth(1);
        List<PlanOption> found = planRepository.findCards(PlanPaymentStatus.PAID, month).stream()
                .filter(r -> code.isEmpty()
                        || (byCode && WordSearch.clean(FuneralPlanService.code(r.getPlanId())).replace(" ", "").contains(code))
                        || WordSearch.matchesAny(text, fullName(r.getHolderName(), r.getHolderLastname()),
                                r.getHolderLastname(), r.getHolderDocument()))
                .map(r -> new PlanOption(r.getPlanId(),
                        FuneralPlanService.code(r.getPlanId()) + " · " + fullName(r.getHolderName(), r.getHolderLastname()),
                        r.getPlanName() + " · " + (r.getHolderDocumentType() == null ? "" : r.getHolderDocumentType() + " ")
                                + (r.getHolderDocument() == null ? "" : r.getHolderDocument())
                                + (r.getStatus() == PlanStatus.ACTIVE ? "" : " · " + statusLabel(r.getStatus()))))
                .toList();
        // Primero los titulares cuyo nombre empieza por lo escrito.
        return WordSearch.startsFirst(found, text,
                        o -> o.label().substring(o.label().indexOf(" · ") + 3)).stream()
                .limit(20)
                .toList();
    }

    /** Guarda el PDF en un archivo temporal para abrirlo con el visor del sistema ("Ver PDF"). */
    public Path temporaryPdf(PlanContract contract) {
        try {
            Path file = Files.createTempFile("Contrato_" + contract.code() + "_", ".pdf");
            file.toFile().deleteOnExit();
            exportPdf(contract, file);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo preparar el PDF del contrato.", e);
        }
    }

    /** Persona del contrato: el titular va primero; surcharge es el recargo que se cobra en el servicio. */
    public record Person(String name, String document, String role, LocalDate birthDate, BigDecimal surcharge,
                         boolean deceased) {
    }

    /** Todo lo que lleva el contrato. issued es la fecha en que se imprime o se ve. */
    public record PlanContract(Long planId, String code, LocalDate issued, String holderName, String holderDocument,
                               String holderPhone, String holderEmail, boolean corporate, String planName,
                               BigDecimal monthlyFee, LocalDate startDate, LocalDate waitingEnds, int waitingDays,
                               PlanStatus status, List<Person> people, BigDecimal totalSurcharge,
                               List<String> includes, PlanTerms terms) {
    }

    /** Arma el contrato del plan con lo que hay guardado hoy. */
    @Transactional(readOnly = true)
    public PlanContract contract(Long planId) {
        PlanDetail d = planService.detail(planId);
        PlanCard c = d.card();
        List<Person> people = new ArrayList<>();
        people.add(new Person(c.holderName(), digits(c.holderDocument()), c.corporate() ? "Empresa titular" : "Titular",
                null, null, d.holderDeceased()));
        for (BeneficiaryInfo b : d.beneficiaries()) {
            people.add(new Person(b.fullName(), b.documentNumber(), b.relationshipLabel(), b.birthDate(),
                    b.surcharge(), b.isDeceased()));
        }
        List<String> includes = planService.planOffers().stream()
                .filter(offer -> offer.name().equalsIgnoreCase(c.planName()))
                .map(PlanOffer::includes)
                .findFirst().orElse(List.of());
        return new PlanContract(c.id(), c.code(), LocalDate.now(AccessLogService.APP_ZONE), c.holderName(),
                c.holderDocument(), d.holderPhone(), d.holderEmail(), c.corporate(), c.planName(), c.monthlyFee(),
                c.startDate(), c.waitingEnds(), c.waitingDays(), c.status(), List.copyOf(people),
                d.totalSurcharge(), includes, terms(c.planName()));
    }

    /** Lee de Parámetros lo que ofrece ese plan. */
    private PlanTerms terms(String planName) {
        List<Integer> letters = optional(parameters::overdueLetterDays);
        return new PlanTerms(PlanParameters.isPremium(planName), optional(() -> parameters.coveredCoffinPrice(planName)),
                optional(parameters::specialRoomSurcharge), optional(parameters::beneficiaryLimit),
                optional(parameters::nonDirectSurcharge), optional(parameters::extraBeneficiarySurcharge),
                optional(parameters::arrearsResetMonths), letters == null ? List.of() : letters);
    }

    /** Un parámetro que falta no impide ver el contrato: esa parte simplemente no sale. */
    private static <T> T optional(java.util.function.Supplier<T> read) {
        try {
            return read.get();
        } catch (com.example.funerariamc.exception.BusinessException missing) {
            return null;
        }
    }

    /** La hoja que se ve en pantalla: titular, plan, personas cubiertas y lo que incluye. */
    public static ContractDocument document(PlanContract k) {
        List<ContractDocument.Row> people = new ArrayList<>();
        for (Person p : k.people()) {
            StringBuilder detail = new StringBuilder(p.role());
            if (p.document() != null && !p.document().isBlank()) {
                detail.append(" · Doc. ").append(p.document());
            }
            if (p.surcharge() != null && p.surcharge().signum() > 0) {
                detail.append(" · recargo ").append(ReportService.pesos(p.surcharge()));
            }
            if (p.deceased()) {
                detail.append(" · fallecido");
            }
            people.add(new ContractDocument.Row(p.name(), detail.toString()));
        }
        return new ContractDocument(k.code(), k.issued(), "CONTRATO DE AFILIACIÓN A PLAN EXEQUIAL",
                "Contrato del plan " + k.code(), k.holderName() + " · " + k.planName(),
                List.of(new ContractDocument.Section("TITULAR", List.of(
                                new ContractDocument.Row("Nombre", k.holderName()),
                                new ContractDocument.Row("Documento", k.holderDocument()),
                                new ContractDocument.Row("Teléfono", k.holderPhone()),
                                new ContractDocument.Row("Correo", k.holderEmail()))),
                        new ContractDocument.Section("PLAN", List.of(
                                new ContractDocument.Row("Tipo", k.planName()),
                                new ContractDocument.Row("Cuota mensual", k.monthlyFee() == null ? null : ReportService.pesos(k.monthlyFee())),
                                new ContractDocument.Row("Fecha de inicio", k.startDate() == null ? null : DATE.format(k.startDate())),
                                new ContractDocument.Row("Cubre servicios desde", k.waitingEnds() == null ? null : DATE.format(k.waitingEnds())),
                                new ContractDocument.Row("Estado", statusLabel(k.status())))),
                        new ContractDocument.Section("PERSONAS CUBIERTAS", people)),
                "LO QUE INCLUYE", k.includes(), suggestedName(k));
    }

    /** Nombre que se sugiere al guardar: Contrato_PLAN-0012.pdf. */
    public static String suggestedName(PlanContract contract) {
        return "Contrato_" + contract.code() + ".pdf";
    }

    /** Escribe el PDF del contrato en el archivo elegido. */
    public void exportPdf(PlanContract contract, Path file) {
        try (OutputStream out = Files.newOutputStream(file)) {
            writePdf(contract, out, ExportDesign.brandOf(brandingService), BrandingService.fullNameOf(brandingService));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar el contrato.", e);
        }
    }

    /**
     * El PDF con forma de contrato: membrete, presentación de las partes, datos del contratante y
     * del plan, personas cubiertas, cláusulas con lo que ofrece el plan y las firmas.
     */
    static void writePdf(PlanContract k, OutputStream out, BrandView brand, String funeralHome) {
        ExportDesign.PdfReport pdf = ExportDesign.document(brand, funeralHome, out, "Contrato N.° " + k.code());
        pdf.letterhead("Contrato de afiliación al " + planTitle(k.planName()),
                "Expedido el " + date(k.issued()) + " · Estado: " + statusLabel(k.status()));
        pdf.paragraph("Entre " + funeralHome + ", en adelante LA FUNERARIA, y " + k.holderName()
                + (k.holderDocument() == null || k.holderDocument().isBlank() ? "" : ", identificado(a) con " + k.holderDocument())
                + ", en adelante EL CONTRATANTE, se celebra el presente contrato de afiliación al " + planTitle(k.planName())
                + ", que se rige por los datos y las cláusulas siguientes.");

        pdf.section("Datos del contratante");
        pdf.fields("Nombre o razón social", k.holderName(), "Documento", orDash(k.holderDocument()),
                "Teléfono", phone(k.holderPhone()), "Correo", orDash(k.holderEmail()),
                "Tipo de titular", k.corporate() ? "Empresa" : "Persona natural");

        pdf.section("Datos del plan");
        pdf.fields("Plan", k.planName(), "Cuota mensual", k.monthlyFee() == null ? "—" : ReportService.pesos(k.monthlyFee()),
                "Fecha de inicio", date(k.startDate()), "Cubre servicios desde", date(k.waitingEnds()));

        pdf.section("Personas cubiertas");
        // Frases cortas para que cada persona quepa en un renglón y el contrato no pase a otra hoja.
        List<String> people = new ArrayList<>();
        for (Person p : k.people()) {
            StringBuilder line = new StringBuilder(p.name());
            if (p.document() != null && !p.document().isBlank()) {
                line.append(", doc. ").append(p.document());
            }
            line.append(", ").append(p.role().toLowerCase(Locale.ROOT));
            if (p.birthDate() != null) {
                line.append(", nació el ").append(date(p.birthDate()));
            }
            if (p.surcharge() != null && p.surcharge().signum() > 0) {
                line.append(", recargo ").append(ReportService.pesos(p.surcharge()));
            }
            if (p.deceased()) {
                line.append(" (fallecido)");
            }
            people.add(line.append(".").toString());
        }
        pdf.numbered(people);

        clauses(pdf, k, funeralHome);
        pdf.close();
    }

    /** Cláusulas del contrato del plan. Solo dicen lo que el sistema ya maneja, con los valores de este plan. */
    private static void clauses(ExportDesign.PdfReport pdf, PlanContract k, String funeralHome) {
        PlanTerms t = k.terms() == null ? PlanTerms.empty(false) : k.terms();
        String plan = planTitle(k.planName());
        Numbering n = new Numbering();
        pdf.section("Cláusulas");
        pdf.clause(n.next(), "Objeto", "LA FUNERARIA se compromete a prestar el servicio funerario a las personas "
                + "cubiertas que aparecen en este contrato, con lo que ofrece el " + plan + ".");
        StringBuilder services = new StringBuilder("El plan incluye ");
        services.append(t.coffinPrice() == null ? "el ataúd" : "un ataúd de hasta " + ReportService.pesos(t.coffinPrice()));
        services.append(t.premium() ? ", la sala de velación especial sin recargo" : ", la sala de velación");
        services.append(", la carroza fúnebre y un bus para el acompañamiento.");
        services.append(" Si se escoge un ataúd o un servicio de mayor valor que el incluido, EL CONTRATANTE paga solo la diferencia.");
        if (!t.premium() && t.roomSurcharge() != null) {
            services.append(" La sala especial tiene un recargo de ").append(ReportService.pesos(t.roomSurcharge())).append(".");
        }
        pdf.clause(n.next(), "Servicios incluidos", services.toString());
        StringBuilder people = new StringBuilder();
        if (k.corporate()) {
            people.append("Quedan cubiertos los beneficiarios que la empresa registre en este contrato.");
        } else if (t.beneficiaryLimit() != null) {
            people.append("Quedan cubiertos el titular y hasta ").append(t.beneficiaryLimit()).append(" beneficiarios sin recargo.");
            if (t.extraSurcharge() != null && t.extraSurcharge().signum() > 0) {
                people.append(" Cada beneficiario adicional tiene un recargo de ").append(ReportService.pesos(t.extraSurcharge())).append(".");
            }
        } else {
            people.append("Quedan cubiertos el titular y los beneficiarios registrados.");
        }
        if (t.nonDirectSurcharge() != null && t.nonDirectSurcharge().signum() > 0) {
            people.append(" Si el beneficiario no es familiar directo, el recargo es de ")
                    .append(ReportService.pesos(t.nonDirectSurcharge())).append(".");
        }
        people.append(" Los recargos se pagan en la venta del servicio.");
        pdf.clause(n.next(), "Personas cubiertas", people.toString());
        pdf.clause(n.next(), "Cuota", "EL CONTRATANTE paga una cuota mensual de " + ReportService.pesos(k.monthlyFee())
                + " a partir del " + date(k.startDate()) + ".");
        pdf.clause(n.next(), "Carencia", "El plan cubre servicios desde el " + date(k.waitingEnds())
                + (k.waitingDays() > 0 ? ", " + k.waitingDays() + " días después de la fecha de inicio" : "")
                + ". Antes de esa fecha el servicio no queda cubierto por el plan.");
        if (t.arrearsResetMonths() != null || !t.letterDays().isEmpty()) {
            StringBuilder late = new StringBuilder();
            if (!t.letterDays().isEmpty()) {
                late.append("Si EL CONTRATANTE se atrasa en las cuotas, LA FUNERARIA le envía una carta a los ")
                        .append(joinDays(t.letterDays())).append(" días de atraso.");
            }
            if (t.arrearsResetMonths() != null) {
                late.append(late.isEmpty() ? "" : " ").append("Si debe más de ").append(t.arrearsResetMonths())
                        .append(t.arrearsResetMonths() == 1 ? " cuota" : " cuotas")
                        .append(" y luego se pone al día, la carencia vuelve a empezar.");
            }
            pdf.clause(n.next(), "Atraso en las cuotas", late.toString());
        }
        pdf.clause(n.next(), "Documentos", "Para prestar el servicio se necesita el certificado de defunción. "
                + "En las muertes violentas lo expide Medicina Legal.");
        pdf.clause(n.next(), "Otras ciudades", "Si el fallecimiento ocurre en otra ciudad, el servicio se puede "
                + "prestar por medio de una funeraria aliada de la red nacional.");
        pdf.signatures("Para constancia se firma el " + date(k.issued()) + ".", List.of(
                List.of("LA FUNERARIA", funeralHome, "Nombre y firma de quien atiende"),
                List.of("EL CONTRATANTE", k.holderName(), orDash(k.holderDocument()))));
    }

    /** "30, 60 y 90". */
    private static String joinDays(List<Integer> days) {
        List<String> text = days.stream().map(String::valueOf).toList();
        return text.size() == 1 ? text.get(0)
                : String.join(", ", text.subList(0, text.size() - 1)) + " y " + text.get(text.size() - 1);
    }

    /** Numera las cláusulas en palabras: PRIMERA, SEGUNDA... (las que no aplican no dejan hueco). */
    private static final class Numbering {
        private static final String[] WORDS = {"PRIMERA", "SEGUNDA", "TERCERA", "CUARTA", "QUINTA", "SEXTA",
                "SÉPTIMA", "OCTAVA", "NOVENA", "DÉCIMA"};
        private int i;

        String next() {
            return WORDS[Math.min(i++, WORDS.length - 1)];
        }
    }

    static String statusLabel(PlanStatus status) {
        if (status == null) {
            return "Activo";
        }
        return switch (status) {
            case ACTIVE -> "Activo";
            case SUSPENDED -> "Suspendido";
            case CANCELLED -> "Cancelado";
        };
    }

    private static String date(LocalDate value) {
        return value == null ? "—" : DATE.format(value);
    }

    /** "Plan Premium" queda igual; "Premium" pasa a "plan Premium". */
    static String planTitle(String planName) {
        if (planName == null) {
            return "plan";
        }
        return planName.toLowerCase(java.util.Locale.ROOT).startsWith("plan ") ? planName : "plan " + planName;
    }

    /** "3104567890" -> "310 456 7890", más fácil de leer en el contrato. */
    private static String phone(String value) {
        if (value == null || value.isBlank()) {
            return "—";
        }
        String d = value.replaceAll("\\D", "");
        return d.length() == 10 ? d.substring(0, 3) + " " + d.substring(3, 6) + " " + d.substring(6) : value;
    }

    private static String fullName(String name, String lastname) {
        return ((name == null ? "" : name) + " " + (lastname == null ? "" : lastname)).trim();
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    /** "CC 1065123456" -> "1065123456", para que la columna de documentos quede pareja. */
    private static String digits(String document) {
        if (document == null) {
            return null;
        }
        String only = document.replaceAll("\\D", "");
        return only.isEmpty() ? document : only;
    }
}
