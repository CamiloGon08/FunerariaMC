package com.example.funerariamc.service;

import com.example.funerariamc.exception.CremationNotAuthorizedException;
import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.ResourceNotFoundException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.Cremation;
import com.example.funerariamc.model.CremationBatch;
import com.example.funerariamc.model.CremationProvider;
import com.example.funerariamc.model.CremationPurchase;
import com.example.funerariamc.model.Deceased;
import com.example.funerariamc.model.Employee;
import com.example.funerariamc.model.ServiceContract;
import com.example.funerariamc.model.Thanatopractor;
import com.example.funerariamc.model.enums.ContractStatus;
import com.example.funerariamc.model.enums.CremationBatchStatus;
import com.example.funerariamc.model.enums.CremationPurchaseStatus;
import com.example.funerariamc.model.enums.DeathType;
import com.example.funerariamc.model.enums.DocumentType;
import com.example.funerariamc.model.enums.EmployeeStatus;
import com.example.funerariamc.model.enums.ExhumationDestination;
import com.example.funerariamc.persistence.CremationBatchRepository;
import com.example.funerariamc.persistence.CremationBatchRepository.BoardRow;
import com.example.funerariamc.persistence.CremationBatchRepository.FinishedCount;
import com.example.funerariamc.persistence.CremationProviderRepository;
import com.example.funerariamc.persistence.CremationPurchaseRepository;
import com.example.funerariamc.persistence.CremationRepository;
import com.example.funerariamc.persistence.CremationRepository.CandidateRow;
import com.example.funerariamc.persistence.DeceasedRepository;
import com.example.funerariamc.persistence.ServiceContractRepository;
import com.example.funerariamc.persistence.StaffOptionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Módulo Cremaciones: programar lotes, agregar difuntos al lote con
 * el control de la Fiscalía, registrar el certificado de cada cremación
 * y ver el estado de los hornos o proveedores y las cremaciones del día.
 * La funeraria no tiene horno propio (entrevista del 19/09): el "horno" es el código del horno o
 * del proveedor externo que se escribe al programar el lote.
 * Cada difunto que entra a un lote separa un cupo prepagado del proveedor del lote; sin cupos
 * disponibles no se puede agregar (ver CremationStockService).
 * Lo usan el Administrador de Cementerio y el Administrador Maestro.
 * La pantalla completa sale de dos consultas (filas de los lotes y conteo del mes).
 */
@Service
public class CremationService {

    /** Largo de cremation_batch.furnace_code en la BD. */
    public static final int MAX_FURNACE_CODE = 15;
    /** Formato corto de fecha para la bitácora (access_log.action admite 100 caracteres). */
    private static final DateTimeFormatter LOG_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** Largo de cremation.certificate_number en la BD. */
    public static final int MAX_CERTIFICATE = 20;
    /** Resultados del buscador de difuntos del diálogo. */
    public static final int SHORT_LIST = 20;
    /** Días hacia atrás que se cargan de lotes ya terminados (filtro "Realizados"). */
    public static final int FINISHED_DAYS_SHOWN = 30;

    private static final EnumSet<CremationBatchStatus> OPEN =
            EnumSet.of(CremationBatchStatus.SCHEDULED, CremationBatchStatus.IN_PROGRESS);

    /** Difunto dentro de un lote. authorized es false si la muerte es no natural y falta la orden de entrega. */
    public record Item(Long cremationId, Long deceasedId, String deceasedCode, String deceasedName,
                       String documentNumber, DeathType deathType, boolean releaseOrderRegistered,
                       boolean authorized, String thanatopractor, LocalDateTime completedAt,
                       String certificateNumber) {

        public boolean certificatePending() {
            return completedAt == null;
        }
    }

    /** Lote con sus difuntos, en el orden en que se agregaron. */
    public record Batch(Long id, String code, LocalDateTime scheduledAt, String furnaceCode,
                        CremationBatchStatus status, List<Item> items) {

        public long pendingCertificates() {
            return items.stream().filter(item -> item.certificatePending()).count();
        }

        /** Un difunto del lote sin autorización lo deja bloqueado. */
        public boolean blocked() {
            return items.stream().anyMatch(i -> !i.authorized());
        }
    }

    /** Estado de un horno o proveedor según sus lotes abiertos. */
    public enum FurnaceState {
        IN_USE("En uso"),
        SCHEDULED("Con lote programado");

        private final String label;

        FurnaceState(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** Tarjeta de un horno o proveedor: el lote en curso (si hay) y el siguiente programado. */
    public record Furnace(String code, FurnaceState state, Batch current, Batch next) {
    }

    /** Indicadores de la banda verde. */
    public record Kpis(int inProgress, String inProgressDetail, int scheduledToday, int batchesToday,
                       long finishedThisMonth, long batchesThisMonth, YearMonth month,
                       long pendingCertificates) {
    }

    /** Toda la pantalla: lotes abiertos y recientes, hornos e indicadores. */
    public record Screen(LocalDateTime now, Kpis kpis, List<Furnace> furnaces, List<Batch> batches) {
    }

    /**
     * Opción del buscador de difuntos. contractNumber y thanatopractor vienen del contrato activo;
     * preparationRegistered dice si la preparación ya está registrada en ese contrato.
     */
    public record Candidate(Long deceasedId, String code, String name, String documentNumber, DeathType deathType,
                            boolean authorized, Long contractId, String contractNumber, Long thanatopractorId,
                            String thanatopractor, LocalDateTime preparationCompletedAt) {

        public boolean preparationRegistered() {
            return preparationCompletedAt != null;
        }
    }

    /** Opción de una lista (tanatólogos). */
    public record Option(Long id, String label) {
    }

    private final CremationBatchRepository batchRepository;
    private final CremationRepository cremationRepository;
    private final DeceasedRepository deceasedRepository;
    private final ServiceContractRepository contractRepository;
    private final StaffOptionRepository staffRepository;
    private final CremationProviderRepository providerRepository;
    private final CremationPurchaseRepository purchaseRepository;
    private final AccessLogService accessLogService;
    private final SessionContext sessionContext;
    private final Clock clock;

    @Autowired
    public CremationService(CremationBatchRepository batchRepository, CremationRepository cremationRepository,
                            DeceasedRepository deceasedRepository, ServiceContractRepository contractRepository,
                            StaffOptionRepository staffRepository, CremationProviderRepository providerRepository,
                            CremationPurchaseRepository purchaseRepository, AccessLogService accessLogService,
                            SessionContext sessionContext) {
        this(batchRepository, cremationRepository, deceasedRepository, contractRepository, staffRepository,
                providerRepository, purchaseRepository, accessLogService, sessionContext,
                Clock.system(AccessLogService.APP_ZONE));
    }

    /** Las pruebas fijan la fecha y la hora con un Clock. */
    CremationService(CremationBatchRepository batchRepository, CremationRepository cremationRepository,
                     DeceasedRepository deceasedRepository, ServiceContractRepository contractRepository,
                     StaffOptionRepository staffRepository, CremationProviderRepository providerRepository,
                     CremationPurchaseRepository purchaseRepository, AccessLogService accessLogService,
                     SessionContext sessionContext, Clock clock) {
        this.batchRepository = batchRepository;
        this.cremationRepository = cremationRepository;
        this.deceasedRepository = deceasedRepository;
        this.contractRepository = contractRepository;
        this.staffRepository = staffRepository;
        this.providerRepository = providerRepository;
        this.purchaseRepository = purchaseRepository;
        this.accessLogService = accessLogService;
        this.sessionContext = sessionContext;
        this.clock = clock;
    }

    // ---------- pantalla ----------

    /**
     * Lotes abiertos de cualquier fecha más los programados en los últimos 30 días, los hornos o
     * proveedores con su estado y los indicadores. Criterio de prueba: un lote de hoy
     * a las 15:00 en el Horno 1 aparece en la agenda del día y como siguiente lote del Horno 1.
     */
    @Transactional(readOnly = true)
    public Screen screen() {
        requireAccess();
        LocalDateTime now = now();
        LocalDate today = now.toLocalDate();
        List<Batch> batches = batches(batchRepository.findBoardRows(OPEN,
                today.minusDays(FINISHED_DAYS_SHOWN).atStartOfDay(), DocumentType.BODY_RELEASE_ORDER));
        YearMonth month = YearMonth.from(today);
        FinishedCount finished = batchRepository.countFinished(month.atDay(1).atStartOfDay(),
                month.plusMonths(1).atDay(1).atStartOfDay());
        return new Screen(now, kpis(batches, finished, today), furnaces(batches), batches);
    }

    /** Agrupa las filas de la consulta en lotes con sus difuntos, sin cambiar el orden. */
    static List<Batch> batches(List<BoardRow> rows) {
        Map<Long, List<BoardRow>> byBatch = new LinkedHashMap<>();
        for (BoardRow row : rows) {
            byBatch.computeIfAbsent(row.getBatchId(), id -> new ArrayList<>()).add(row);
        }
        List<Batch> result = new ArrayList<>();
        for (List<BoardRow> group : byBatch.values()) {
            BoardRow first = group.get(0);
            List<Item> items = group.stream().filter(r -> r.getCremationId() != null).map(CremationService::item).toList();
            result.add(new Batch(first.getBatchId(), batchCode(first.getBatchId()), first.getScheduledDate(),
                    first.getFurnaceCode(), first.getStatus(), items));
        }
        return result;
    }

    private static Item item(BoardRow row) {
        boolean release = row.getReleaseOrders() != null && row.getReleaseOrders() > 0;
        String thanatopractor = row.getThanatopractorName() == null ? null
                : fullName(row.getThanatopractorName(), row.getThanatopractorLastname());
        return new Item(row.getCremationId(), row.getDeceasedId(), DeceasedService.code(row.getDeceasedId()),
                fullName(row.getName(), row.getLastname()), row.getDocumentNumber(), row.getDeathType(), release,
                isAuthorized(row.getDeathType(), release), thanatopractor, row.getCompletedAt(),
                row.getCertificateNumber());
    }

    static Kpis kpis(List<Batch> batches, FinishedCount finished, LocalDate today) {
        List<Batch> inProgress = batches.stream().filter(b -> b.status() == CremationBatchStatus.IN_PROGRESS).toList();
        String detail = inProgress.isEmpty() ? null
                : inProgress.get(0).furnaceCode() + " · lote " + inProgress.get(0).code()
                + (inProgress.size() > 1 ? " y " + (inProgress.size() - 1) + " más" : "");
        List<Batch> todays = batches.stream()
                .filter(b -> b.scheduledAt() != null && b.scheduledAt().toLocalDate().equals(today)).toList();
        int deceasedToday = todays.stream().mapToInt(b -> b.items().size()).sum();
        long pending = inProgress.stream().mapToLong(batch -> batch.pendingCertificates()).sum();
        long cremations = finished == null || finished.getCremations() == null ? 0 : finished.getCremations();
        long lots = finished == null || finished.getBatches() == null ? 0 : finished.getBatches();
        return new Kpis(inProgress.size(), detail, deceasedToday, todays.size(), cremations, lots,
                YearMonth.from(today), pending);
    }

    /**
     * Un horno o proveedor por cada código con lotes abiertos. "En uso" si tiene un lote en curso;
     * los que están en uso van primero y después por la hora del siguiente lote.
     */
    static List<Furnace> furnaces(List<Batch> batches) {
        Map<String, List<Batch>> byCode = new LinkedHashMap<>();
        for (Batch batch : batches) {
            if (OPEN.contains(batch.status()) && batch.furnaceCode() != null) {
                byCode.computeIfAbsent(batch.furnaceCode().trim().toUpperCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(batch);
            }
        }
        List<Furnace> result = new ArrayList<>();
        for (List<Batch> group : byCode.values()) {
            Batch current = group.stream().filter(b -> b.status() == CremationBatchStatus.IN_PROGRESS)
                    .min(Comparator.comparing((Batch batch) -> batch.scheduledAt(),
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElse(null);
            Batch next = group.stream().filter(b -> b.status() == CremationBatchStatus.SCHEDULED)
                    .min(Comparator.comparing((Batch batch) -> batch.scheduledAt(),
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElse(null);
            result.add(new Furnace(group.get(0).furnaceCode(),
                    current != null ? FurnaceState.IN_USE : FurnaceState.SCHEDULED, current, next));
        }
        result.sort(Comparator.comparing((Furnace f) -> f.state() != FurnaceState.IN_USE)
                .thenComparing(f -> f.next() == null || f.next().scheduledAt() == null ? LocalDateTime.MAX
                        : f.next().scheduledAt()));
        return result;
    }

    // ---------- buscadores de los diálogos ----------

    /** Difuntos que se pueden agregar a un lote: sin cremación, fuera de bóveda y sin otro destino. */
    @Transactional(readOnly = true)
    public List<Candidate> searchCandidates(String text) {
        requireAccess();
        return cremationRepository.searchCandidates(pattern(text), DocumentType.BODY_RELEASE_ORDER,
                        ContractStatus.ACTIVE, ExhumationDestination.CREMATION, PageRequest.of(0, SHORT_LIST))
                .stream().map(CremationService::candidate).toList();
    }

    private static Candidate candidate(CandidateRow row) {
        boolean release = row.getReleaseOrders() != null && row.getReleaseOrders() > 0;
        String thanatopractor = row.getThanatopractorId() == null ? null
                : fullName(row.getThanatopractorName(), row.getThanatopractorLastname());
        return new Candidate(row.getId(), DeceasedService.code(row.getId()), fullName(row.getName(), row.getLastname()),
                row.getDocumentNumber(), row.getDeathType(), isAuthorized(row.getDeathType(), release),
                row.getContractId(), row.getContractNumber(), row.getThanatopractorId(), thanatopractor,
                row.getPreparationCompletedAt());
    }

    /** Tanatólogos activos para la lista del diálogo. */
    @Transactional(readOnly = true)
    public List<Option> thanatopractors() {
        requireAccess();
        return staffRepository.findActiveThanatopractors(EmployeeStatus.ACTIVE).stream()
                .map(e -> new Option(e.getId(), fullName(e.getName(), e.getLastname())))
                .toList();
    }

    // ---------- programar el lote ----------

    /**
     * Registra el lote en estado SCHEDULED. Criterio de prueba: un lote con fecha
     * de ayer se rechaza.
     */
    @Transactional
    public Batch schedule(CremationBatchData data) {
        Long actorId = requireAccess();
        String code = validBatchData(data);
        CremationBatch batch = new CremationBatch();
        batch.setFurnaceCode(code);
        batch.setScheduledDate(data.scheduledAt());
        batch.setStatus(CremationBatchStatus.SCHEDULED);
        CremationBatch saved = batchRepository.save(batch);
        accessLogService.record(actorId, AccessLogService.CREMATION_BATCH_SCHEDULED + batchCode(saved.getId())
                + " " + code);
        return new Batch(saved.getId(), batchCode(saved.getId()), saved.getScheduledDate(), saved.getFurnaceCode(),
                saved.getStatus(), List.of());
    }

    /**
     * Cambia la fecha, la hora o el horno de un lote que sigue programado (pedido de Camilo del
     * 06/11). Los difuntos del lote se quedan como están. Aplica las mismas validaciones de
     * La nueva fecha no puede ser anterior a la actual.
     */
    @Transactional
    public void reschedule(Long batchId, CremationBatchData data) {
        Long actorId = requireAccess();
        if (batchId == null) {
            throw new InvalidDataException("Seleccione el lote.");
        }
        CremationBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Lote de cremación", batchId));
        if (batch.getStatus() != CremationBatchStatus.SCHEDULED) {
            throw new InvalidDataException("Solo se puede reprogramar un lote programado; el lote "
                    + batchCode(batchId) + " ya se entregó al horno.");
        }
        String code = validBatchData(data);
        if (code.equals(batch.getFurnaceCode()) && data.scheduledAt().equals(batch.getScheduledDate())) {
            throw new InvalidDataException("El lote ya tiene esa fecha, hora y horno.");
        }
        if (!code.equals(batch.getFurnaceCode())) {
            // Otro proveedor: los difuntos sueltan los cupos del anterior y separan cupos del nuevo.
            List<Cremation> cremations = cremationRepository.findByBatchWithDeceased(batchId);
            for (Cremation cremation : cremations) {
                cremation.setCremationPurchase(null);
                cremationRepository.saveAndFlush(cremation);
            }
            CremationProvider provider = lockProvider(code);
            for (Cremation cremation : cremations) {
                cremation.setCremationPurchase(freeSlot(provider));
                cremationRepository.saveAndFlush(cremation);
            }
        }
        batch.setFurnaceCode(code);
        batch.setScheduledDate(data.scheduledAt());
        batchRepository.save(batch);
        accessLogService.record(actorId, AccessLogService.CREMATION_BATCH_RESCHEDULED + batchCode(batchId)
                + " " + code + " " + data.scheduledAt().format(LOG_FORMAT));
    }

    /** Valida los datos de programar o reprogramar un lote y devuelve el código del horno limpio. */
    private String validBatchData(CremationBatchData data) {
        if (data == null) {
            throw new InvalidDataException("Ingrese los datos del lote.");
        }
        String code = clean(data.furnaceCode());
        if (code == null) {
            throw new InvalidDataException("Ingrese el código del horno o del proveedor.");
        }
        code = code.toUpperCase(Locale.ROOT);
        if (code.length() > MAX_FURNACE_CODE) {
            throw new InvalidDataException("El código del horno o proveedor admite máximo "
                    + MAX_FURNACE_CODE + " caracteres.");
        }
        if (data.scheduledAt() == null) {
            throw new InvalidDataException("Ingrese la fecha y la hora programadas.");
        }
        if (data.scheduledAt().isBefore(now().truncatedTo(ChronoUnit.MINUTES))) {
            throw new InvalidDataException("La fecha y la hora del lote no pueden ser anteriores a la actual.");
        }
        String providerCode = code;
        CremationProvider provider = providerRepository.findByCode(providerCode)
                .orElseThrow(() -> new InvalidDataException("El proveedor " + providerCode
                        + " no está registrado. Regístrelo en Cupos prepagados."));
        if (!Boolean.TRUE.equals(provider.getActive())) {
            throw new InvalidDataException("El proveedor " + code + " está inactivo.");
        }
        return code;
    }

    // ---------- agregar difuntos al lote ----------

    /**
     * Agrega el difunto al lote con el tanatólogo que preparó el cuerpo. Si la muerte fue no
     * natural, sin la orden de entrega de la Fiscalía registrada no se puede ni programar (decisión
     * de Camilo del 06/11). Si el contrato activo no tiene la preparación registrada, se registra
     * aquí a nombre del tanatólogo.
     */
    @Transactional
    public Cremation assign(AssignCremationData data) {
        Long actorId = requireAccess();
        if (data == null || data.batchId() == null) {
            throw new InvalidDataException("Seleccione el lote.");
        }
        if (data.deceasedId() == null) {
            throw new InvalidDataException("Seleccione el difunto.");
        }
        CremationBatch batch = batchRepository.findById(data.batchId())
                .orElseThrow(() -> new ResourceNotFoundException("Lote de cremación", data.batchId()));
        if (batch.getStatus() != CremationBatchStatus.SCHEDULED) {
            throw new InvalidDataException("Solo se agregan difuntos a un lote programado; el lote "
                    + batchCode(batch.getId()) + " ya se entregó al horno.");
        }
        Deceased deceased = deceasedRepository.findById(data.deceasedId())
                .orElseThrow(() -> new ResourceNotFoundException("Difunto", data.deceasedId()));
        if (cremationRepository.existsByDeceasedId(deceased.getId())) {
            throw new InvalidDataException("El difunto ya tiene una cremación registrada.");
        }
        if (cremationRepository.isInVault(deceased.getId())) {
            throw new InvalidDataException("El difunto está en una bóveda. Registre primero su exhumación "
                    + "con destino cremación en Cementerio y bóvedas.");
        }
        if (cremationRepository.hasOtherDestination(deceased.getId(), ExhumationDestination.CREMATION)) {
            throw new InvalidDataException("La exhumación del difunto registró otro destino para los restos.");
        }
        checkAuthorization(deceased);
        if (data.thanatopractorId() == null) {
            throw new InvalidDataException("Seleccione el tanatólogo que preparó el cuerpo.");
        }

        Thanatopractor thanatopractor = thanatopractor(data.thanatopractorId());
        ServiceContract contract = contractRepository.findByDeceasedId(deceased.getId())
                .filter(c -> c.getStatus() == ContractStatus.ACTIVE)
                .orElse(null);
        boolean registersPreparation = false;
        if (contract != null && contract.getPreparationCompletedAt() != null) {
            Thanatopractor registered = contract.getThanatopractor();
            if (registered != null && !Objects.equals(registered.getId(), thanatopractor.getId())) {
                throw new InvalidDataException("La preparación ya está registrada con otro tanatólogo. "
                        + "Seleccione el mismo profesional.");
            }
        } else if (contract != null) {
            LocalDateTime finishedAt = data.preparationCompletedAt();
            if (finishedAt == null) {
                throw new InvalidDataException("Ingrese la fecha y hora en que terminó la preparación del cuerpo.");
            }
            if (finishedAt.isAfter(now())) {
                throw new InvalidDataException("La preparación no puede terminar en el futuro.");
            }
            contract.setThanatopractor(thanatopractor);
            contract.setPreparationCompletedAt(finishedAt);
            contractRepository.save(contract);
            registersPreparation = true;
        }

        // Separa un cupo prepagado del proveedor del lote; sin cupos no se agrega.
        CremationPurchase slot = freeSlot(lockProvider(batch.getFurnaceCode()));

        Cremation cremation = new Cremation();
        cremation.setCremationBatch(batch);
        cremation.setDeceased(deceased);
        cremation.setThanatopractor(thanatopractor);
        cremation.setCremationPurchase(slot);
        Cremation saved = cremationRepository.save(cremation);
        if (registersPreparation && contract != null) {
            accessLogService.record(actorId, AccessLogService.PREPARATION_REGISTERED + contract.getContractNumber());
        }
        accessLogService.record(actorId, AccessLogService.CREMATION_ASSIGNED + batchCode(batch.getId()) + " "
                + DeceasedService.code(deceased.getId()));
        return saved;
    }

    /** Quita un difunto de un lote que todavía no se ha entregado al horno. */
    @Transactional
    public void remove(Long cremationId) {
        Long actorId = requireAccess();
        Cremation cremation = cremationRepository.findDetailById(cremationId)
                .orElseThrow(() -> new ResourceNotFoundException("Cremación", cremationId));
        CremationBatch batch = cremation.getCremationBatch();
        if (batch.getStatus() != CremationBatchStatus.SCHEDULED) {
            throw new InvalidDataException("No se puede quitar un difunto de un lote que ya se entregó al horno.");
        }
        cremationRepository.delete(cremation);
        accessLogService.record(actorId, AccessLogService.CREMATION_REMOVED + batchCode(batch.getId()) + " "
                + DeceasedService.code(cremation.getDeceased().getId()));
    }

    // ---------- entregar el lote al horno ----------

    /**
     * Pasa el lote a IN_PROGRESS (entregado al horno o al proveedor). Necesita al menos un difunto
     * y vuelve a revisar el control de la Fiscalía por si se borró una orden de entrega después de programarlo.
     */
    @Transactional
    public void start(Long batchId) {
        Long actorId = requireAccess();
        CremationBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Lote de cremación", batchId));
        if (batch.getStatus() != CremationBatchStatus.SCHEDULED) {
            throw new InvalidDataException("El lote " + batchCode(batchId) + " ya se entregó al horno.");
        }
        List<Cremation> cremations = cremationRepository.findByBatchWithDeceased(batchId);
        if (cremations.isEmpty()) {
            throw new InvalidDataException("Agregue al menos un difunto antes de entregar el lote al horno.");
        }
        for (Cremation cremation : cremations) {
            checkAuthorization(cremation.getDeceased());
        }
        batch.setStatus(CremationBatchStatus.IN_PROGRESS);
        batchRepository.save(batch);
        accessLogService.record(actorId, AccessLogService.CREMATION_BATCH_STARTED + batchCode(batchId));
    }

    // ---------- certificado de cremación ----------

    /**
     * Registra el número del certificado y la fecha y hora en que terminó la cremación. Cuando
     * todos los difuntos del lote tienen certificado, el lote pasa a FINISHED.
     */
    @Transactional
    public void registerCertificate(CremationCertificateData data) {
        Long actorId = requireAccess();
        if (data == null || data.cremationId() == null) {
            throw new InvalidDataException("Seleccione la cremación.");
        }
        String number = clean(data.certificateNumber());
        if (number == null) {
            throw new InvalidDataException("Ingrese el número del certificado de cremación.");
        }
        if (number.length() > MAX_CERTIFICATE) {
            throw new InvalidDataException("El número del certificado admite máximo " + MAX_CERTIFICATE
                    + " caracteres.");
        }
        if (data.completedAt() == null) {
            throw new InvalidDataException("Ingrese la fecha y hora en que terminó la cremación.");
        }
        if (data.completedAt().isAfter(now())) {
            throw new InvalidDataException("La cremación no puede terminar en el futuro.");
        }
        Cremation cremation = cremationRepository.findDetailById(data.cremationId())
                .orElseThrow(() -> new ResourceNotFoundException("Cremación", data.cremationId()));
        CremationBatch batch = cremation.getCremationBatch();
        if (batch.getStatus() != CremationBatchStatus.IN_PROGRESS) {
            throw new InvalidDataException(batch.getStatus() == CremationBatchStatus.SCHEDULED
                    ? "Entregue el lote al horno antes de registrar los certificados."
                    : "El lote " + batchCode(batch.getId()) + " ya está terminado.");
        }
        if (cremation.getCompletedAt() != null) {
            throw new InvalidDataException("Esta cremación ya tiene su certificado registrado.");
        }
        checkAuthorization(cremation.getDeceased());
        cremation.setCertificateNumber(number);
        cremation.setCompletedAt(data.completedAt());
        cremationRepository.saveAndFlush(cremation);
        if (cremationRepository.countByCremationBatchIdAndCompletedAtIsNull(batch.getId()) == 0) {
            batch.setStatus(CremationBatchStatus.FINISHED);
            batchRepository.save(batch);
        }
        accessLogService.record(actorId, AccessLogService.CREMATION_REGISTERED + batchCode(batch.getId()) + " "
                + DeceasedService.code(cremation.getDeceased().getId()) + " " + number);
    }

    // ---------- reglas ----------

    /**
     * Muerte no natural sin orden de entrega de la Fiscalía registrada.
     * Sin tipo de muerte no se puede revisar.
     */
    private void checkAuthorization(Deceased deceased) {
        if (deceased.getDeathType() == null) {
            throw new InvalidDataException("El difunto no tiene registrado el tipo de muerte. Regístrelo en Difuntos.");
        }
        if (deceased.getDeathType() == DeathType.NON_NATURAL
                && cremationRepository.countReleaseOrders(deceased.getId(), DocumentType.BODY_RELEASE_ORDER) == 0) {
            throw new CremationNotAuthorizedException();
        }
    }

    /** true si la muerte es natural o, si es no natural, tiene la orden de entrega registrada. */
    static boolean isAuthorized(DeathType type, boolean releaseOrderRegistered) {
        return type == DeathType.NATURAL || (type == DeathType.NON_NATURAL && releaseOrderRegistered);
    }

    private Thanatopractor thanatopractor(Long id) {
        Employee employee = staffRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tanatólogo", id));
        if (!(employee instanceof Thanatopractor thanatopractor)) {
            throw new InvalidDataException("El empleado elegido no es tanatólogo.");
        }
        if (!employee.isActive()) {
            throw new InvalidDataException("El tanatólogo elegido está inactivo.");
        }
        return thanatopractor;
    }

    // ---------- utilidades ----------

    /** Código visible del lote: L-0033. */
    public static String batchCode(Long id) {
        return id == null ? "L-—" : "L-%04d".formatted(id);
    }

    static String fullName(String name, String lastname) {
        return ((name == null ? "" : name) + " " + (lastname == null ? "" : lastname)).trim();
    }

    private static String clean(String text) {
        if (text == null) {
            return null;
        }
        String result = text.trim().replaceAll("\\s+", " ");
        return result.isEmpty() ? null : result;
    }

    private static String pattern(String text) {
        String clean = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        return "%" + clean + "%";
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** Bloquea al proveedor mientras se separan cupos, para que dos usuarios no tomen el mismo. */
    private CremationProvider lockProvider(String code) {
        return providerRepository.lockByCode(code)
                .orElseThrow(() -> new InvalidDataException("El proveedor " + code
                        + " no está registrado. Regístrelo en Cupos prepagados."));
    }

    /** Primera compra recibida del proveedor con cupos libres; sin cupos, no deja seguir. */
    private CremationPurchase freeSlot(CremationProvider provider) {
        List<CremationPurchase> free = purchaseRepository.findWithFreeSlots(provider.getId(),
                CremationPurchaseStatus.RECEIVED, PageRequest.of(0, 1));
        if (free.isEmpty()) {
            throw new InvalidDataException("El proveedor " + provider.getCode() + " no tiene cremaciones "
                    + "prepagadas disponibles. Reciba una compra en Cupos prepagados.");
        }
        return free.get(0);
    }

    private Long requireAccess() {
        boolean allowed = sessionContext.getRoleName().map(AppModule.CREMACIONES::isAllowedFor).orElse(false);
        if (!allowed) {
            throw new UnauthorizedActionException();
        }
        return sessionContext.getCurrentUser().map(user -> user.getId()).orElseThrow(UnauthorizedActionException::new);
    }
}
