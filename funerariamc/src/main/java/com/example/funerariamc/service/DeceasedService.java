package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.MissingDeathCertificateException;
import com.example.funerariamc.exception.ResourceNotFoundException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.exception.WaitingPeriodException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Beneficiary;
import com.example.funerariamc.model.Client;
import com.example.funerariamc.model.DeathCertificate;
import com.example.funerariamc.model.Deceased;
import com.example.funerariamc.model.FuneralPlan;
import com.example.funerariamc.model.ServiceTransport;
import com.example.funerariamc.model.enums.ClientType;
import com.example.funerariamc.model.enums.ContractStatus;
import com.example.funerariamc.model.enums.DeathType;
import com.example.funerariamc.model.enums.DocumentType;
import com.example.funerariamc.model.enums.ExternalCause;
import com.example.funerariamc.model.enums.PlanStatus;
import com.example.funerariamc.model.enums.Relationship;
import com.example.funerariamc.model.enums.StopType;
import com.example.funerariamc.model.enums.TransportStatus;
import com.example.funerariamc.model.enums.VehicleType;
import com.example.funerariamc.persistence.BeneficiaryRepository;
import com.example.funerariamc.persistence.ClientRepository;
import com.example.funerariamc.persistence.DeathCertificateRepository;
import com.example.funerariamc.persistence.DeceasedRepository;
import com.example.funerariamc.persistence.DeceasedRepository.DeceasedRow;
import com.example.funerariamc.persistence.DeceasedRepository.DetailRow;
import com.example.funerariamc.persistence.DeceasedRepository.DepartureRow;
import com.example.funerariamc.persistence.FuneralPlanRepository;
import com.example.funerariamc.persistence.ServiceContractRepository;
import com.example.funerariamc.persistence.ServiceTransportRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Difuntos (propuesta P-13 / P-13.1): registro con indicadores, servicios en curso, documentos
 * pendientes y salidas del día; expediente con el recorrido del servicio; registro de un difunto
 * con su certificado (RF-DIF-01, RN-01, RN-02), NN (RF-DIF-02), documentos (RF-DIF-03) y
 * vínculo con un plan exequial (HU-56, RN-08). Lo usan Secretaría, Administrador de Cementerio
 * y Administrador Maestro.
 */
@Service
public class DeceasedService {

    /** Nombre con que se guarda un cuerpo no identificado (RF-DIF-02). */
    public static final String UNIDENTIFIED_NAME = "NN";

    private static final Set<String> NN_ROLES = Set.of(RoleNames.CEMETERY_ADMIN, RoleNames.MASTER_ADMIN);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final DeceasedRepository deceasedRepository;
    private final DeathCertificateRepository certificateRepository;
    private final DeathCertificateService certificateService;
    private final ClientRepository clientRepository;
    private final FuneralPlanRepository planRepository;
    private final BeneficiaryRepository beneficiaryRepository;
    private final ServiceContractRepository contractRepository;
    private final ServiceTransportRepository transportRepository;
    private final PlanParameters parameters;
    private final AccessLogService accessLogService;
    private final SessionContext sessionContext;
    private final Clock clock;

    @Autowired
    public DeceasedService(DeceasedRepository deceasedRepository, DeathCertificateRepository certificateRepository,
                           DeathCertificateService certificateService, ClientRepository clientRepository,
                           FuneralPlanRepository planRepository, BeneficiaryRepository beneficiaryRepository,
                           ServiceContractRepository contractRepository, ServiceTransportRepository transportRepository,
                           PlanParameters parameters, AccessLogService accessLogService,
                           SessionContext sessionContext) {
        this(deceasedRepository, certificateRepository, certificateService, clientRepository, planRepository,
                beneficiaryRepository, contractRepository, transportRepository,
                parameters, accessLogService, sessionContext, Clock.system(AccessLogService.APP_ZONE));
    }

    DeceasedService(DeceasedRepository deceasedRepository, DeathCertificateRepository certificateRepository,
                    DeathCertificateService certificateService, ClientRepository clientRepository,
                    FuneralPlanRepository planRepository, BeneficiaryRepository beneficiaryRepository,
                    ServiceContractRepository contractRepository, ServiceTransportRepository transportRepository,
                    PlanParameters parameters, AccessLogService accessLogService, SessionContext sessionContext,
                    Clock clock) {
        this.deceasedRepository = deceasedRepository;
        this.certificateRepository = certificateRepository;
        this.certificateService = certificateService;
        this.clientRepository = clientRepository;
        this.planRepository = planRepository;
        this.beneficiaryRepository = beneficiaryRepository;
        this.contractRepository = contractRepository;
        this.transportRepository = transportRepository;
        this.parameters = parameters;
        this.accessLogService = accessLogService;
        this.sessionContext = sessionContext;
        this.clock = clock;
    }

    // ---------- lo que recibe la pantalla ----------

    /**
     * Etapa del servicio de un difunto. step es el paso de la barra Ingreso / Preparación /
     * Velación / Destino (0 a 3); FINISHED es 4: todos los pasos completos.
     */
    public enum Stage {
        REGISTERED("Ingresado", 0),
        PREPARATION("En preparación", 1),
        READY("Listo para velación", 1),
        WAKE("En velación", 2),
        AWAITING_DESTINATION("Pendiente de destino", 3),
        TRANSFER("En traslado", 3),
        CREMATION_SCHEDULED("Cremación programada", 3),
        FINISHED("Finalizado", 4);

        private final String label;
        private final int step;

        Stage(String label, int step) {
            this.label = label;
            this.step = step;
        }

        public String getLabel() {
            return label;
        }

        public int getStep() {
            return step;
        }
    }

    /** Segmento de la barra de filtros. */
    public enum Segment {
        ALL("Todos"),
        IN_SERVICE("En servicio"),
        WITH_PLAN("Con plan"),
        WITHOUT_PLAN("Sin plan");

        private final String label;

        Segment(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** Opción de una lista de búsqueda (responsable). */
    public record Option(Long id, String label, String detail) {
    }

    /**
     * Fila del registro. code es "DF-0042"; age es null sin fecha de nacimiento; missing son los
     * documentos obligatorios que faltan; blocksCremation indica que falta la orden de entrega
     * de un cuerpo con cremación programada (RN-03).
     */
    public record DeceasedItem(Long id, String code, boolean unidentified, String fullName, String documentNumber,
                               Integer age, LocalDate deathDate, DeathType deathType, ExternalCause externalCause,
                               String responsible, Long planId, String planCode, String planName,
                               String coverage, String contractNumber, Stage stage, String wakeRoom,
                               LocalDateTime wakeStartAt, LocalDateTime wakeEndAt, String destination,
                               LocalDateTime nextTransportAt, List<DocumentType> missing, boolean blocksCremation) {

        public boolean hasPlan() {
            return planId != null;
        }

        public boolean inService() {
            return contractNumber != null && stage != Stage.FINISHED;
        }
    }

    /** Indicadores de arriba. byStage cuenta los difuntos en servicio por paso de la barra. */
    public record Kpis(long thisMonth, long lastMonth, long inService, Map<Stage, Long> byStage,
                       long pendingDocuments, long blockingCremation, long withPlan, long total) {

        public long planPercent() {
            return total == 0 ? 0 : Math.round(withPlan * 100.0 / total);
        }

        /** Variación contra el mes anterior, en porcentaje; null si el mes anterior fue 0. */
        public Long changePercent() {
            return lastMonth == 0 ? null : Math.round((thisMonth - lastMonth) * 100.0 / lastMonth);
        }
    }

    /** Documento pendiente de un difunto. */
    public record PendingDocument(Long deceasedId, String fullName, DocumentType type, boolean blocksCremation) {
    }

    /** Salida de hoy: traslado programado o en ruta. */
    public record Departure(Long deceasedId, String fullName, LocalDateTime scheduledAt, TransportStatus status,
                            String vehicle, String destination) {
    }

    public record Overview(Kpis kpis, List<DeceasedItem> items, List<PendingDocument> pendingDocuments,
                           List<Departure> departures) {
    }

    /** Filtros de la pantalla. month null = todos los meses; deathType null = ambos. */
    public record Filter(Segment segment, DeathType deathType, YearMonth month, String text) {
    }

    /** Paso del recorrido del servicio en el expediente. */
    public record TimelineStep(String title, String detail, LocalDateTime at, boolean done) {
    }

    public record DocumentInfo(Long id, DocumentType type, String issuingAuthority, String documentNumber,
                               LocalDate issueDate, String fileUrl, String notes, String registeredBy,
                               LocalDateTime createdAt) {
    }

    /** Plan que cubre al difunto: titular o beneficiario, con su carencia (RN-08). */
    public record PlanCoverage(Long planId, String code, String planName, PlanStatus status, String holder,
                               String holderDocument, String relationship, LocalDate startDate,
                               LocalDate waitingEnds, boolean covered, long beneficiaries) {
    }

    public record DeceasedDetail(DeceasedItem item, Deceased deceased, String responsibleDocument,
                                 String responsiblePhone, List<TimelineStep> timeline, PlanCoverage plan,
                                 List<String> otherBeneficiaries, List<DocumentInfo> documents) {
    }

    /** Plan sugerido al registrar: el documento es titular o beneficiario de un plan activo. */
    public record PlanSuggestion(Long planId, String code, String planName, String holder, String relationship,
                                 LocalDate waitingEnds, boolean covered, long waitingDaysLeft) {
    }

    // ---------- consultas ----------

    @Transactional(readOnly = true)
    public Overview overview() {
        requireAccess();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        List<DeceasedItem> items = deceasedRepository.findRegistry(
                        List.of(TransportStatus.SCHEDULED, TransportStatus.IN_PROGRESS), TransportStatus.IN_PROGRESS,
                        DocumentType.MEDICAL_CERTIFICATE, DocumentType.FORENSIC_REPORT,
                        DocumentType.BODY_RELEASE_ORDER).stream()
                .map(row -> item(row, now))
                .toList();

        YearMonth month = YearMonth.from(today);
        long thisMonth = items.stream().filter(i -> inMonth(i.deathDate(), month)).count();
        long lastMonth = items.stream().filter(i -> inMonth(i.deathDate(), month.minusMonths(1))).count();
        Map<Stage, Long> byStage = new EnumMap<>(Stage.class);
        for (DeceasedItem item : items) {
            if (item.inService()) {
                byStage.merge(item.stage(), 1L, Long::sum);
            }
        }
        List<PendingDocument> pending = new ArrayList<>();
        for (DeceasedItem item : items) {
            for (DocumentType type : item.missing()) {
                pending.add(new PendingDocument(item.id(), item.fullName(), type,
                        item.blocksCremation() && type == DocumentType.BODY_RELEASE_ORDER));
            }
        }
        // Lo que bloquea una cremación va primero.
        pending.sort((a, b) -> Boolean.compare(b.blocksCremation(), a.blocksCremation()));

        Kpis kpis = new Kpis(thisMonth, lastMonth, items.stream().filter(DeceasedItem::inService).count(), byStage,
                items.stream().filter(i -> !i.missing().isEmpty()).count(),
                items.stream().filter(DeceasedItem::blocksCremation).count(),
                items.stream().filter(DeceasedItem::hasPlan).count(), items.size());

        List<Departure> departures = deceasedRepository.findDepartures(today.atStartOfDay(),
                        today.plusDays(1).atStartOfDay(), TransportStatus.CANCELLED).stream()
                .map(DeceasedService::departure)
                .toList();
        return new Overview(kpis, items, pending, departures);
    }

    /** Aplica los filtros en memoria (la lista ya está cargada y es corta). */
    public static List<DeceasedItem> filter(List<DeceasedItem> items, Filter filter) {
        if (filter == null) {
            return items;
        }
        String text = filter.text() == null ? "" : normalize(filter.text());
        return items.stream()
                .filter(i -> switch (filter.segment() == null ? Segment.ALL : filter.segment()) {
                    case ALL -> true;
                    case IN_SERVICE -> i.inService();
                    case WITH_PLAN -> i.hasPlan();
                    case WITHOUT_PLAN -> !i.hasPlan();
                })
                .filter(i -> filter.deathType() == null || filter.deathType() == i.deathType())
                .filter(i -> filter.month() == null || inMonth(i.deathDate(), filter.month()))
                .filter(i -> text.isEmpty()
                        || normalize(i.fullName()).contains(text)
                        || normalize(i.code()).contains(text)
                        || (i.documentNumber() != null && i.documentNumber().contains(text))
                        || (i.contractNumber() != null && normalize(i.contractNumber()).contains(text)))
                .toList();
    }

    /** Expediente del difunto (P-13.1). */
    @Transactional(readOnly = true)
    public DeceasedDetail detail(Long deceasedId) {
        requireAccess();
        LocalDateTime now = LocalDateTime.now(clock);
        Deceased deceased = deceasedRepository.findDetailById(deceasedId)
                .orElseThrow(() -> new ResourceNotFoundException("Difunto", deceasedId));
        // Supabase está lejos: la fila trae de una vez contrato, bóveda y cremación (antes 4 consultas).
        DetailRow row = deceasedRepository.findDetailRow(deceasedId,
                        List.of(TransportStatus.SCHEDULED, TransportStatus.IN_PROGRESS), TransportStatus.IN_PROGRESS,
                        DocumentType.MEDICAL_CERTIFICATE, DocumentType.FORENSIC_REPORT, DocumentType.BODY_RELEASE_ORDER)
                .orElseThrow(() -> new ResourceNotFoundException("Difunto", deceasedId));
        DeceasedItem item = item(row, now);

        List<DeathCertificate> certificates = certificateRepository.findByDeceased(deceasedId);
        List<DocumentInfo> documents = certificates.stream()
                .map(c -> new DocumentInfo(c.getId(), c.getDocumentType(), c.getIssuingAuthority(),
                        c.getDocumentNumber(), c.getIssueDate(), c.getFileUrl(), c.getNotes(),
                        c.getRegisteredByEmployee() == null ? null
                                : fullName(c.getRegisteredByEmployee().getName(),
                                c.getRegisteredByEmployee().getLastname()),
                        c.getCreatedAt()))
                .toList();

        List<TimelineStep> timeline = timeline(row, item, certificates, now);

        PlanCoverage plan = null;
        List<String> others = List.of();
        FuneralPlan funeralPlan = deceased.getFuneralPlan();
        if (funeralPlan != null) {
            // Una sola lista de beneficiarios sirve para el parentesco, el conteo y "otros beneficiarios".
            List<Beneficiary> beneficiaries = beneficiaryRepository.findByFuneralPlanIdOrderByIdAsc(funeralPlan.getId());
            plan = coverage(funeralPlan, deceased.getDocumentNumber(), deceased.getDeathDate(), beneficiaries);
            others = beneficiaries.stream()
                    .filter(b -> !Objects.equals(b.getDocumentNumber(), deceased.getDocumentNumber()))
                    .map(b -> fullName(b.getName(), b.getLastname()) + relationshipSuffix(b.getRelationship()))
                    .toList();
        }
        Client responsible = deceased.getClient();
        return new DeceasedDetail(item, deceased,
                responsible == null ? null : responsible.getDocumentNumber(),
                responsible == null ? null : responsible.getPhone(),
                timeline, plan, others, documents);
    }

    /** Busca el responsable (cliente) del servicio por nombre o documento. */
    @Transactional(readOnly = true)
    public List<Option> searchClients(String text) {
        requireAccess();
        return clientRepository.search(pattern(text), List.of(ClientType.values()), PageRequest.of(0, 20))
                .getContent().stream()
                .map(c -> new Option(c.getId(), ClientService.displayName(c),
                        orDash(c.getDocumentType()) + " " + orDash(c.getDocumentNumber())))
                .toList();
    }

    /**
     * HU-56: planes activos donde ese documento es titular o beneficiario, con la carencia
     * calculada a la fecha de defunción (hoy si todavía no la tiene).
     */
    @Transactional(readOnly = true)
    public List<PlanSuggestion> suggestPlans(String documentNumber, LocalDate deathDate) {
        requireAccess();
        String document = documentNumber == null ? "" : documentNumber.trim();
        if (document.isEmpty()) {
            return List.of();
        }
        LocalDate date = deathDate == null ? LocalDate.now(clock) : deathDate;
        Map<Long, PlanSuggestion> result = new LinkedHashMap<>();
        clientRepository.findByDocumentNumber(document).ifPresent(client -> {
            for (FuneralPlan plan : planRepository.findByClientIdAndStatus(client.getId(), PlanStatus.ACTIVE)) {
                result.put(plan.getId(), suggestion(plan, "Titular", date));
            }
        });
        for (Beneficiary b : beneficiaryRepository.findByDocumentWithPlan(document)) {
            FuneralPlan plan = b.getFuneralPlan();
            if (plan.getStatus() == PlanStatus.ACTIVE) {
                result.putIfAbsent(plan.getId(), suggestion(plan, relationshipLabel(b.getRelationship()), date));
            }
        }
        return List.copyOf(result.values());
    }

    // ---------- operaciones ----------

    /**
     * RF-DIF-01 / RF-DIF-02: registra el difunto con su certificado de defunción en una sola
     * transacción. Sin certificado no se registra (RN-02). Si trae plan, valida que lo cubra.
     */
    @Transactional
    public Deceased register(DeceasedData data, DeathDocumentData certificate) {
        requireAccess();
        AppUser actor = sessionContext.getCurrentUser().orElseThrow(UnauthorizedActionException::new);
        if (data == null) {
            throw new InvalidDataException("Ingrese los datos del difunto.");
        }
        LocalDate today = LocalDate.now(clock);
        Deceased deceased = new Deceased();
        if (data.unidentified()) {
            if (!sessionContext.getRoleName().map(NN_ROLES::contains).orElse(false)) {
                throw new UnauthorizedActionException();
            }
            deceased.setName(UNIDENTIFIED_NAME);
            // RF-DIF-02: solo el nombre «NN», sin apellidos ni documento (lastname es NOT NULL en la BD)
            deceased.setLastname("");
            if (data.planId() != null) {
                throw new InvalidDataException("Un cuerpo no identificado no se puede vincular a un plan.");
            }
        } else {
            deceased.setName(required(data.name(), "los nombres", 25));
            deceased.setLastname(required(data.lastname(), "los apellidos", 25));
            String document = required(data.documentNumber(), "el número de documento", 11);
            if (!document.matches("\\d{5,11}")) {
                throw new InvalidDataException("El documento debe tener entre 5 y 11 dígitos.");
            }
            if (deceasedRepository.existsByDocumentNumber(document)) {
                throw new InvalidDataException("Ya existe un difunto registrado con el documento " + document + ".");
            }
            deceased.setDocumentNumber(document);
        }
        if (data.deathDate() == null) {
            throw new InvalidDataException("Ingrese la fecha de defunción.");
        }
        if (data.deathDate().isAfter(today)) {
            throw new InvalidDataException("La fecha de defunción no puede ser futura.");
        }
        if (data.birthDate() != null && data.birthDate().isAfter(data.deathDate())) {
            throw new InvalidDataException("La fecha de nacimiento no puede ser posterior a la defunción.");
        }
        deceased.setBirthDate(data.birthDate());
        deceased.setDeathDate(data.deathDate());
        setDeathType(deceased, data.deathType(), data.externalCause());

        if (data.clientId() != null) {
            deceased.setClient(clientRepository.findById(data.clientId())
                    .orElseThrow(() -> new ResourceNotFoundException("Cliente", data.clientId())));
        } else if (!data.unidentified()) {
            throw new InvalidDataException("Seleccione el cliente responsable del servicio.");
        }
        if (data.planId() != null) {
            deceased.setFuneralPlan(coveringPlan(data.planId(), deceased));
        }
        if (certificate == null || certificate.type() != DocumentType.MEDICAL_CERTIFICATE
                || certificate.file() == null) {
            throw new MissingDeathCertificateException();
        }
        Deceased saved = deceasedRepository.save(deceased);
        certificateService.attach(saved, certificate, actor);
        accessLogService.record(actor.getId(), AccessLogService.DECEASED_REGISTERED + code(saved)
                + (saved.getDocumentNumber() == null ? "" : " " + saved.getDocumentNumber()));
        return saved;
    }

    /**
     * HU-56: vincula (o quita, con planId null) el plan exequial que cubre al difunto. Solo antes
     * de firmar el contrato de servicio, porque el contrato ya se cotizó con o sin plan.
     */
    @Transactional
    public void linkPlan(Long deceasedId, Long planId) {
        requireAccess();
        Long actorId = currentUserId();
        Deceased deceased = deceasedRepository.findDetailById(deceasedId)
                .orElseThrow(() -> new ResourceNotFoundException("Difunto", deceasedId));
        if (contractRepository.findByDeceasedId(deceasedId).isPresent()) {
            throw new InvalidDataException("El difunto ya tiene contrato de servicio; el plan se cambia desde Contratos.");
        }
        if (planId == null) {
            deceased.setFuneralPlan(null);
        } else {
            if (deceased.getDocumentNumber() == null) {
                throw new InvalidDataException("Un cuerpo no identificado no se puede vincular a un plan.");
            }
            deceased.setFuneralPlan(coveringPlan(planId, deceased));
        }
        deceasedRepository.save(deceased);
        accessLogService.record(actorId, AccessLogService.DECEASED_PLAN_LINKED + code(deceased) + " "
                + (planId == null ? "PARTICULAR" : FuneralPlanService.code(planId)));
    }

    // ---------- utilidades ----------

    /** Código visible del difunto: "DF-0042". */
    public static String code(Deceased deceased) {
        return code(deceased.getId());
    }

    public static String code(Long id) {
        return id == null ? "DF-nuevo" : "DF-%04d".formatted(id);
    }

    /** Edad cumplida a la fecha de defunción. */
    public static Integer age(LocalDate birthDate, LocalDate deathDate) {
        if (birthDate == null || deathDate == null || birthDate.isAfter(deathDate)) {
            return null;
        }
        return Period.between(birthDate, deathDate).getYears();
    }

    private DeceasedItem item(DeceasedRow row, LocalDateTime now) {
        boolean unidentified = UNIDENTIFIED_NAME.equals(row.getName()) && row.getDocumentNumber() == null;
        Stage stage = stage(row, now);
        List<DocumentType> missing = new ArrayList<>();
        if (row.getMedicalCertificates() == 0) {
            missing.add(DocumentType.MEDICAL_CERTIFICATE);
        }
        boolean nonNatural = row.getDeathType() == DeathType.NON_NATURAL;
        if (nonNatural && row.getForensicReports() == 0) {
            missing.add(DocumentType.FORENSIC_REPORT);
        }
        if (nonNatural && row.getReleaseOrders() == 0) {
            missing.add(DocumentType.BODY_RELEASE_ORDER);
        }
        boolean blocks = nonNatural && row.getReleaseOrders() == 0
                && row.getCremationId() != null && row.getCremationCompletedAt() == null;

        String coverage = null;
        if (row.getPlanId() != null) {
            coverage = Objects.equals(row.getPlanHolderDocument(), row.getDocumentNumber())
                    ? "Titular" : relationshipLabel(row.getBeneficiaryRelationship());
        }
        String destination = row.getVaultCode() != null ? "Bóveda " + row.getVaultCode()
                : row.getCremationId() != null ? "Cremación" : null;
        return new DeceasedItem(row.getId(), code(row.getId()), unidentified,
                fullName(row.getName(), row.getLastname()), row.getDocumentNumber(),
                age(row.getBirthDate(), row.getDeathDate()), row.getDeathDate(), row.getDeathType(),
                row.getExternalCause(),
                row.getResponsibleName() == null ? null
                        : fullName(row.getResponsibleName(), row.getResponsibleLastname()),
                row.getPlanId(), row.getPlanId() == null ? null : FuneralPlanService.code(row.getPlanId()),
                row.getPlanName(), coverage, row.getContractNumber(), stage, row.getWakeRoom(),
                row.getWakeStartAt(), row.getWakeEndAt(), destination, row.getNextTransportAt(),
                List.copyOf(missing), blocks);
    }

    /**
     * Etapa según el contrato, la velación, los traslados y el destino. La bóveda se asigna antes
     * de la inhumación, así que tener bóveda solo cuenta como finalizado cuando la velación ya
     * terminó y no queda un traslado pendiente.
     */
    static Stage stage(DeceasedRow row, LocalDateTime now) {
        if (row.getCremationCompletedAt() != null) {
            return Stage.FINISHED;
        }
        if (row.getContractNumber() == null || row.getContractStatus() == ContractStatus.CANCELLED) {
            return row.getVaultCode() != null ? Stage.FINISHED : Stage.REGISTERED;
        }
        if (row.getContractStatus() == ContractStatus.COMPLETED) {
            return Stage.FINISHED;
        }
        if (row.getTransportsInProgress() > 0) {
            return Stage.TRANSFER;
        }
        if (row.getWakeStartAt() != null && !now.isBefore(row.getWakeStartAt())
                && (row.getWakeEndAt() == null || now.isBefore(row.getWakeEndAt()))) {
            return Stage.WAKE;
        }
        if (row.getWakeEndAt() != null && !now.isBefore(row.getWakeEndAt())) {
            if (row.getCremationId() != null) {
                return Stage.CREMATION_SCHEDULED;
            }
            return row.getVaultCode() != null && row.getNextTransportAt() == null
                    ? Stage.FINISHED : Stage.AWAITING_DESTINATION;
        }
        return row.getPreparationCompletedAt() != null ? Stage.READY : Stage.PREPARATION;
    }

    private List<TimelineStep> timeline(DetailRow row, DeceasedItem item, List<DeathCertificate> certificates,
                                        LocalDateTime now) {
        List<TimelineStep> steps = new ArrayList<>();
        DeathCertificate first = certificates.stream()
                .filter(c -> c.getDocumentType() == DocumentType.MEDICAL_CERTIFICATE)
                .findFirst()
                .orElse(certificates.isEmpty() ? null : certificates.get(0));
        String by = first == null || first.getRegisteredByEmployee() == null ? "Registro del difunto"
                : "Registrado por " + fullName(first.getRegisteredByEmployee().getName(),
                first.getRegisteredByEmployee().getLastname());
        steps.add(new TimelineStep("Ingreso", by, first == null ? null : first.getCreatedAt(), true));

        if (row.getContractId() == null) {
            steps.add(new TimelineStep("Contrato de servicio", "Pendiente en Contratos y ventas", null, false));
        } else {
            String thanato = row.getThanatopractorName() == null ? "Sin tanatopractor asignado"
                    : "Tanatopractor " + fullName(row.getThanatopractorName(), row.getThanatopractorLastname());
            steps.add(new TimelineStep("Preparación", thanato, row.getPreparationCompletedAt(),
                    row.getPreparationCompletedAt() != null));
            String room = row.getWakeRoom() == null ? "Sin sala asignada" : row.getWakeRoom();
            boolean wakeDone = row.getWakeEndAt() != null && !now.isBefore(row.getWakeEndAt());
            steps.add(new TimelineStep("Velación", room, row.getWakeStartAt(), wakeDone));
            for (ServiceTransportRepository.TransportRow transport
                    : transportRepository.findByContract(row.getContractId(), StopType.DESTINATION)) {
                ServiceTransport t = transport.getTransport();
                String vehicle = vehicleLabel(t.getVehicle().getVehicleType()) + " " + t.getVehicle().getPlate();
                String detail = vehicle + (transport.getDestination() == null ? "" : " → " + transport.getDestination())
                        + " · " + t.getStatus().getDisplayName();
                steps.add(new TimelineStep("Traslado", detail, t.getScheduledAt(),
                        t.getStatus() == TransportStatus.COMPLETED));
            }
        }
        if (row.getVaultCode() != null) {
            // La bóveda se asigna antes de la inhumación: el paso queda hecho cuando el servicio terminó.
            String since = row.getVaultLeaseStart() == null ? ""
                    : " · desde el " + row.getVaultLeaseStart().format(DATE);
            steps.add(new TimelineStep("Destino", "Inhumación en bóveda " + row.getVaultCode() + " · "
                    + orDash(row.getCemeteryName()) + since, null, item.stage() == Stage.FINISHED));
        } else if (row.getCremationId() != null) {
            String detail = "Cremación · horno " + orDash(row.getFurnaceCode())
                    + (item.blocksCremation() ? " · falta la orden de entrega (RN-03)" : "");
            LocalDateTime at = row.getCremationCompletedAt() != null ? row.getCremationCompletedAt()
                    : row.getCremationScheduledAt();
            steps.add(new TimelineStep("Destino", detail, at, row.getCremationCompletedAt() != null));
        } else {
            steps.add(new TimelineStep("Destino", "Bóveda o cremación sin asignar", null, false));
        }
        return steps;
    }

    /** Plan activo que cubre al difunto: es titular o beneficiario y ya pasó la carencia (RN-08). */
    private FuneralPlan coveringPlan(Long planId, Deceased deceased) {
        FuneralPlan plan = planRepository.findDetailById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("Plan", planId));
        String code = FuneralPlanService.code(planId);
        if (plan.getStatus() != PlanStatus.ACTIVE) {
            throw new InvalidDataException("El plan " + code + " no está activo.");
        }
        String document = deceased.getDocumentNumber();
        boolean holder = Objects.equals(plan.getClient().getDocumentNumber(), document);
        if (!holder && !beneficiaryRepository.existsByFuneralPlanIdAndDocumentNumber(planId, document)) {
            throw new InvalidDataException("El documento " + document + " no es titular ni beneficiario del plan "
                    + code + ".");
        }
        LocalDate ends = waitingEnds(plan);
        LocalDate date = deceased.getDeathDate() == null ? LocalDate.now(clock) : deceased.getDeathDate();
        if (ends == null || date.isBefore(ends)) {
            long left = ends == null ? parameters.waitingDays() : ChronoUnit.DAYS.between(date, ends);
            throw new WaitingPeriodException(left);
        }
        return plan;
    }

    private PlanCoverage coverage(FuneralPlan plan, String document, LocalDate deathDate,
                                  List<Beneficiary> beneficiaries) {
        boolean holder = Objects.equals(plan.getClient().getDocumentNumber(), document);
        String relationship = holder ? "Titular" : beneficiaries.stream()
                .filter(b -> Objects.equals(b.getDocumentNumber(), document))
                .map(b -> relationshipLabel(b.getRelationship()))
                .findFirst().orElse("Beneficiario");
        LocalDate ends = waitingEnds(plan);
        return new PlanCoverage(plan.getId(), FuneralPlanService.code(plan.getId()), planName(plan), plan.getStatus(),
                ClientService.displayName(plan.getClient()), plan.getClient().getDocumentNumber(), relationship,
                plan.getStartDate(), ends, ends != null && deathDate != null && !deathDate.isBefore(ends),
                beneficiaries.size());
    }

    private PlanSuggestion suggestion(FuneralPlan plan, String relationship, LocalDate date) {
        LocalDate ends = waitingEnds(plan);
        long left = ends == null ? parameters.waitingDays() : Math.max(0, ChronoUnit.DAYS.between(date, ends));
        return new PlanSuggestion(plan.getId(), FuneralPlanService.code(plan.getId()), planName(plan),
                ClientService.displayName(plan.getClient()), relationship, ends, left == 0, left);
    }

    private LocalDate waitingEnds(FuneralPlan plan) {
        return plan.getStartDate() == null ? null : plan.getStartDate().plusDays(parameters.waitingDays());
    }

    /** RN-01: la causa externa solo aplica a una muerte no natural, y ahí es obligatoria. */
    private static void setDeathType(Deceased deceased, DeathType type, ExternalCause cause) {
        if (type == null) {
            throw new InvalidDataException("Seleccione el tipo de muerte.");
        }
        if (type == DeathType.NON_NATURAL && cause == null) {
            throw new InvalidDataException("Seleccione la causa externa de la muerte no natural.");
        }
        deceased.setDeathType(type);
        deceased.setExternalCause(type == DeathType.NON_NATURAL ? cause : null);
    }

    private static Departure departure(DepartureRow row) {
        String destination = row.getVaultCode() != null ? "Bóveda " + row.getVaultCode()
                : row.getCremationId() != null ? "Cremación" : "Destino sin asignar";
        return new Departure(row.getDeceasedId(), fullName(row.getName(), row.getLastname()), row.getScheduledAt(),
                row.getStatus(), vehicleLabel(row.getVehicleType()) + " " + row.getPlate(), destination);
    }

    private static String vehicleLabel(VehicleType type) {
        return type == VehicleType.BUS ? "Bus" : "Carroza";
    }

    private static String relationshipLabel(String code) {
        Relationship relationship = Relationship.fromCode(code);
        if (relationship != null) {
            return relationship.getDisplayName();
        }
        return code == null || code.isBlank() ? "Beneficiario" : code;
    }

    private static String relationshipSuffix(String code) {
        return " · " + relationshipLabel(code);
    }

    private static String planName(FuneralPlan plan) {
        return plan.getPlanName() == null || plan.getPlanName().isBlank() ? "Plan exequial" : plan.getPlanName();
    }

    private static boolean inMonth(LocalDate date, YearMonth month) {
        return date != null && YearMonth.from(date).equals(month);
    }

    private static String required(String value, String what, int max) {
        if (value == null || value.isBlank()) {
            throw new InvalidDataException("Ingrese " + what + ".");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new InvalidDataException("Máximo " + max + " caracteres en " + what + ".");
        }
        return trimmed;
    }

    private static String fullName(String name, String lastname) {
        if (name == null) {
            return lastname == null ? "" : lastname;
        }
        return lastname == null || lastname.isBlank() ? name : name + " " + lastname;
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String normalize(String text) {
        return text == null ? "" : java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    private static String pattern(String text) {
        return "%" + (text == null ? "" : text.trim().toLowerCase(Locale.ROOT)) + "%";
    }

    private void requireAccess() {
        boolean allowed = sessionContext.getRoleName()
                .map(AppModule.DIFUNTOS::isAllowedFor)
                .orElse(false);
        if (!allowed) {
            throw new UnauthorizedActionException();
        }
    }

    private Long currentUserId() {
        return sessionContext.getCurrentUser()
                .map(AppUser::getId)
                .orElseThrow(UnauthorizedActionException::new);
    }
}
