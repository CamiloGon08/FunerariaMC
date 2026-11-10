package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.ResourceNotFoundException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Cemetery;
import com.example.funerariamc.model.Client;
import com.example.funerariamc.model.Deceased;
import com.example.funerariamc.model.Exhumation;
import com.example.funerariamc.model.Vault;
import com.example.funerariamc.model.enums.ExhumationDestination;
import com.example.funerariamc.model.enums.ExhumationReason;
import com.example.funerariamc.model.enums.NotificationChannel;
import com.example.funerariamc.model.enums.NotificationStatus;
import com.example.funerariamc.model.enums.NotificationType;
import com.example.funerariamc.model.enums.VaultStatus;
import com.example.funerariamc.persistence.CemeteryRepository;
import com.example.funerariamc.persistence.ClientRepository;
import com.example.funerariamc.persistence.DeceasedRepository;
import com.example.funerariamc.persistence.ExhumationRepository;
import com.example.funerariamc.persistence.LeaseNotificationRepository;
import com.example.funerariamc.persistence.VaultRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Módulo Cementerio y bóvedas (propuesta P-17 / P-17.1): muro de bóvedas por pabellón,
 * indicadores, bóvedas que requieren atención, panel de detalle (RF-BOV-06), asignación
 * (RF-BOV-02), renovación del arriendo y exhumación (RF-BOV-05).
 * Lo usan el Administrador del Cementerio y el Administrador Maestro (RN-21, RN-22).
 * Devuelve records ya armados dentro de la transacción, así la pantalla no toca relaciones LAZY.
 * Rendimiento: el muro y el panel se leen con proyecciones (solo las columnas que se pintan),
 * la primera carga junta pestañas y muro en una sola transacción y el panel usa tres consultas.
 */
@Service
public class CemeteryService {

    /** RN-06: el arriendo dura 4 años y solo después procede la exhumación. */
    public static final int LEASE_YEARS = 4;
    /** Filas de las listas cortas: requieren atención, exhumaciones y búsquedas del diálogo. */
    public static final int SHORT_LIST = 20;
    /** Exhumaciones que se muestran en la columna derecha. */
    public static final int RECENT_EXHUMATIONS = 3;
    /** Carpeta de FileStorageService donde quedan las autorizaciones de exhumación (RN-25). */
    public static final String AUTHORIZATION_FOLDER = "exhumaciones";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Set<ExhumationDestination> UNCLAIMED_DESTINATIONS =
            EnumSet.of(ExhumationDestination.COMMON_GRAVE, ExhumationDestination.OSSUARY);

    private final CemeteryRepository cemeteryRepository;
    private final VaultRepository vaultRepository;
    private final ExhumationRepository exhumationRepository;
    private final LeaseNotificationRepository leaseNotificationRepository;
    private final ClientRepository clientRepository;
    private final DeceasedRepository deceasedRepository;
    private final FileStorageService fileStorageService;
    private final AccessLogService accessLogService;
    private final SessionContext sessionContext;
    private final Clock clock;

    // ---------- lo que recibe la pantalla ----------

    /** Opción de una lista: pestaña de cementerio o resultado de búsqueda del diálogo. */
    public record Option(Long id, String label, String detail) {
    }

    /** Una bóveda del muro. occupantLastname es el texto pequeño bajo el código ("Durán"). */
    public record WallCell(Long id, String code, VaultPosition position, VaultState state,
                           String deceasedName, String occupantLastname, String clientName,
                           LocalDate leaseEnd) {
    }

    /** Indicadores de un pabellón. */
    public record Kpis(int total, int occupied, int available, int overdue, int expiring) {

        public static Kpis of(List<WallCell> cells) {
            int available = 0;
            int overdue = 0;
            int expiring = 0;
            for (WallCell cell : cells) {
                switch (cell.state()) {
                    case AVAILABLE -> available++;
                    case OVERDUE -> overdue++;
                    case EXPIRING -> expiring++;
                    default -> {
                    }
                }
            }
            return new Kpis(cells.size(), cells.size() - available, available, overdue, expiring);
        }

        /** Porcentaje de bóvedas ocupadas, de 0 a 100. */
        public int occupancyPercent() {
            return total == 0 ? 0 : Math.round(occupied * 100f / total);
        }
    }

    /** Bóveda en mora o próxima a vencer, para el bloque "Requieren atención". */
    public record Attention(Long vaultId, String code, VaultState state, String holder, LocalDate leaseEnd) {
    }

    /** Exhumación reciente del cementerio. */
    public record RecentExhumation(Long vaultId, String code, String deceasedName, LocalDate date,
                                   ExhumationDestination destination) {
    }

    /** Todo lo que pinta la pantalla para un cementerio: pabellones con sus bóvedas y las listas laterales. */
    public record Overview(Map<String, List<WallCell>> pavilions, List<Attention> attention,
                           List<RecentExhumation> exhumations) {
    }

    /** Primera carga: pestañas de cementerios, el cementerio elegido y su muro (null si no hay cementerios). */
    public record Screen(List<Option> cemeteries, Long cemeteryId, Overview overview) {
    }

    /** Evento del historial de la bóveda; current marca lo que sigue vigente (punto verde). */
    public record HistoryEvent(LocalDate date, String text, boolean current) {
    }

    /** Aviso de arriendo enviado o programado (lease_notification). */
    public record Notice(LocalDate scheduledDate, NotificationType type, NotificationChannel channel,
                         NotificationStatus status) {
    }

    /**
     * Resultado de "Agregar bóvedas": cuántas se crearon y qué códigos se saltaron porque ya
     * existían o porque otra bóveda ya ocupa ese nivel y columna en el muro.
     */
    public record VaultBatch(int created, List<String> skipped) {
    }

    /** Panel de detalle de una bóveda (RF-BOV-06). Los campos del difunto y del titular van en null si está libre. */
    public record VaultDetail(Long id, String code, VaultPosition position, String cemeteryName,
                              VaultState state, String deceasedName, LocalDate deceasedBirth,
                              LocalDate deceasedDeath, Long clientId, String clientName, String clientDocument,
                              String clientPhone, String clientEmail, LocalDate leaseStart, LocalDate leaseEnd,
                              String contractNumber, LocalDate exhumationAllowedFrom, boolean canExhume,
                              List<Notice> notices, List<HistoryEvent> history) {

        public boolean isAvailable() {
            return state == VaultState.AVAILABLE;
        }
    }

    @Autowired
    public CemeteryService(CemeteryRepository cemeteryRepository, VaultRepository vaultRepository,
                           ExhumationRepository exhumationRepository,
                           LeaseNotificationRepository leaseNotificationRepository, ClientRepository clientRepository,
                           DeceasedRepository deceasedRepository, FileStorageService fileStorageService,
                           AccessLogService accessLogService, SessionContext sessionContext) {
        this(cemeteryRepository, vaultRepository, exhumationRepository, leaseNotificationRepository,
                clientRepository, deceasedRepository, fileStorageService, accessLogService, sessionContext,
                Clock.system(AccessLogService.APP_ZONE));
    }

    /** Las pruebas fijan la fecha de hoy con un Clock. */
    CemeteryService(CemeteryRepository cemeteryRepository, VaultRepository vaultRepository,
                    ExhumationRepository exhumationRepository, LeaseNotificationRepository leaseNotificationRepository,
                    ClientRepository clientRepository, DeceasedRepository deceasedRepository,
                    FileStorageService fileStorageService, AccessLogService accessLogService,
                    SessionContext sessionContext, Clock clock) {
        this.cemeteryRepository = cemeteryRepository;
        this.vaultRepository = vaultRepository;
        this.exhumationRepository = exhumationRepository;
        this.leaseNotificationRepository = leaseNotificationRepository;
        this.clientRepository = clientRepository;
        this.deceasedRepository = deceasedRepository;
        this.fileStorageService = fileStorageService;
        this.accessLogService = accessLogService;
        this.sessionContext = sessionContext;
        this.clock = clock;
    }

    // ---------- consultas ----------

    /**
     * Primera carga de la pantalla en una sola transacción de lectura (una conexión): las pestañas
     * de cementerios y el muro del pedido, o del primero si {@code cemeteryId} es null o ya no existe.
     */
    @Transactional(readOnly = true)
    public Screen screen(Long cemeteryId) {
        requireAccess();
        List<Option> cemeteries = cemeteryRepository.findAllByOrderByNameAsc().stream()
                .map(c -> new Option(c.getId(), c.getName(), c.getAddress()))
                .toList();
        if (cemeteries.isEmpty()) {
            return new Screen(cemeteries, null, null);
        }
        Long chosen = cemeteries.stream().map(option -> option.id()).filter(id -> id.equals(cemeteryId)).findFirst()
                .orElse(cemeteries.get(0).id());
        return new Screen(cemeteries, chosen, buildOverview(chosen));
    }

    /** Muro, requieren atención y últimas exhumaciones de un cementerio (al cambiar de pestaña). */
    @Transactional(readOnly = true)
    public Overview overview(Long cemeteryId) {
        requireAccess();
        return buildOverview(cemeteryId);
    }

    /** Dos consultas: las filas del muro (proyección) y las 3 últimas exhumaciones. */
    private Overview buildOverview(Long cemeteryId) {
        LocalDate today = today();
        Map<String, List<WallCell>> pavilions = new TreeMap<>(CemeteryService::comparePavilions);
        Map<String, Integer> generalCount = new LinkedHashMap<>();
        List<Attention> attention = new ArrayList<>();
        for (VaultRepository.WallRow row : vaultRepository.findWallRows(cemeteryId)) {
            VaultPosition position = VaultPosition.of(row.getCode());
            if (position == null) {
                // Código sin formato "A-07": se ubica en orden dentro del pabellón General.
                int number = generalCount.merge(VaultPosition.GENERAL, 1, (left, right) -> left + right);
                position = VaultPosition.at(VaultPosition.GENERAL, number);
            }
            WallCell cell = cell(row, position, today);
            pavilions.computeIfAbsent(position.pavilion(), key -> new ArrayList<>()).add(cell);
            if (cell.state() == VaultState.OVERDUE || cell.state() == VaultState.EXPIRING) {
                attention.add(new Attention(row.getId(), row.getCode(), cell.state(), holder(row),
                        row.getLeaseEndDate()));
            }
        }
        pavilions.values().forEach(cells -> cells.sort(Comparator.comparingInt(c -> c.position().number())));
        // Primero las que están en mora, luego las que vencen antes.
        attention.sort(Comparator.comparing((Attention a) -> a.state() != VaultState.OVERDUE)
                .thenComparing(attentionItem -> attentionItem.leaseEnd(), Comparator.nullsLast(Comparator.naturalOrder())));

        List<RecentExhumation> exhumations = exhumationRepository
                .findRecentRows(cemeteryId, PageRequest.of(0, RECENT_EXHUMATIONS)).stream()
                .map(e -> new RecentExhumation(e.getVaultId(), e.getVaultCode(),
                        fullName(e.getDeceasedName(), e.getDeceasedLastname()), e.getExhumationDate(),
                        e.getDestination()))
                .toList();
        return new Overview(pavilions, attention.size() > SHORT_LIST ? attention.subList(0, SHORT_LIST) : attention,
                exhumations);
    }

    /**
     * Panel de detalle (RF-BOV-06) con tres consultas en la misma transacción: la bóveda con su
     * difunto, titular y contrato; los avisos de arriendo; y las exhumaciones anteriores.
     */
    @Transactional(readOnly = true)
    public VaultDetail detail(Long vaultId) {
        requireAccess();
        LocalDate today = today();
        VaultRepository.DetailRow row = vaultRepository.findDetailRow(vaultId)
                .orElseThrow(() -> new ResourceNotFoundException("Bóveda", vaultId));
        VaultState state = VaultState.of(row.getStatus(), row.getLeaseEndDate(), today);
        boolean hasDeceased = row.getDeceasedId() != null;
        boolean hasClient = row.getClientId() != null;
        LocalDate allowedFrom = hasDeceased && row.getLeaseStartDate() != null
                ? leaseEnd(row.getLeaseStartDate()) : null;
        boolean canExhume = allowedFrom != null && !today.isBefore(allowedFrom);

        List<Notice> notices = leaseNotificationRepository.findByVaultIdOrderByScheduledDateDesc(vaultId).stream()
                .map(n -> new Notice(n.getScheduledDate(), n.getNotificationType(), n.getChannel(), n.getStatus()))
                .toList();

        String deceasedName = hasDeceased ? fullName(row.getDeceasedName(), row.getDeceasedLastname()) : null;
        String clientName = hasClient ? fullName(row.getClientName(), row.getClientLastname()) : null;
        return new VaultDetail(row.getId(), row.getCode(), VaultPosition.of(row.getCode()), row.getCemeteryName(),
                state, deceasedName,
                hasDeceased ? row.getDeceasedBirthDate() : null,
                hasDeceased ? row.getDeceasedDeathDate() : null,
                hasClient ? row.getClientId() : null, clientName,
                hasClient ? document(row.getClientDocumentType(), row.getClientDocumentNumber()) : null,
                hasClient ? row.getClientPhone() : null,
                hasClient ? row.getClientEmail() : null,
                state == VaultState.AVAILABLE ? null : row.getLeaseStartDate(),
                state == VaultState.AVAILABLE ? null : row.getLeaseEndDate(),
                hasDeceased ? row.getContractNumber() : null, allowedFrom, canExhume, notices,
                history(row, deceasedName, clientName));
    }

    /** Clientes para el diálogo de asignación, por nombre, razón social o documento (sin consulta de conteo). */
    @Transactional(readOnly = true)
    public List<Option> searchClients(String text) {
        requireAccess();
        return clientRepository.searchOptions(pattern(text), PageRequest.of(0, SHORT_LIST)).stream()
                .map(c -> new Option(c.getId(), ClientService.displayName(c),
                        document(c.getDocumentType(), c.getDocumentNumber())))
                .toList();
    }

    /** Difuntos sin bóveda, sin exhumación y sin cremación, para el diálogo de asignación. */
    @Transactional(readOnly = true)
    public List<Option> searchDeceasedWithoutVault(String text) {
        requireAccess();
        return deceasedRepository.searchWithoutVault(pattern(text), PageRequest.of(0, SHORT_LIST)).stream()
                .map(d -> new Option(d.getId(), fullName(d.getName(), d.getLastname()),
                        d.getDeathDate() == null ? "Sin fecha de defunción"
                                : "Falleció el " + DATE.format(d.getDeathDate())))
                .toList();
    }

    // ---------- operaciones ----------

    /**
     * RF-BOV-02: asigna una bóveda DISPONIBLE a un titular y un difunto. El arriendo vence
     * 4 años después del inicio (RN-06) y la bóveda queda OCUPADA.
     */
    @Transactional
    public Long assign(AssignVaultData data) {
        requireAccess();
        Long actorId = currentUserId();
        if (data == null || data.vaultId() == null) {
            throw new InvalidDataException("Seleccione la bóveda.");
        }
        if (data.clientId() == null) {
            throw new InvalidDataException("Seleccione el titular del arriendo.");
        }
        if (data.deceasedId() == null) {
            throw new InvalidDataException("Seleccione el difunto que se inhuma.");
        }
        if (data.leaseStartDate() == null) {
            throw new InvalidDataException("Ingrese la fecha de inicio del arriendo.");
        }
        Vault vault = getVault(data.vaultId());
        VaultState state = VaultState.of(vault, today());
        if (state != VaultState.AVAILABLE) {
            throw new InvalidDataException("La bóveda " + vault.getCode() + " no está disponible: está "
                    + state.getLabel().toLowerCase(Locale.ROOT) + ".");
        }
        if (vaultRepository.existsByDeceasedId(data.deceasedId())) {
            throw new InvalidDataException("Ese difunto ya reposa en otra bóveda.");
        }
        Client client = clientRepository.findById(data.clientId())
                .orElseThrow(() -> new ResourceNotFoundException("Cliente", data.clientId()));
        Deceased deceased = deceasedRepository.findById(data.deceasedId())
                .orElseThrow(() -> new ResourceNotFoundException("Difunto", data.deceasedId()));

        vault.setClient(client);
        vault.setDeceased(deceased);
        vault.setLeaseStartDate(data.leaseStartDate());
        vault.setLeaseEndDate(leaseEnd(data.leaseStartDate()));
        vault.setStatus(VaultStatus.OCCUPIED);
        vaultRepository.save(vault);
        accessLogService.record(actorId, AccessLogService.VAULT_ASSIGNED + vault.getCode());
        return vault.getId();
    }

    // ---------- RF-BOV-01: registrar cementerios y bóvedas ----------

    /** Solo el Administrador Maestro registra cementerios y bóvedas (RF-BOV-01, actor ACT-01). */
    public boolean canRegister() {
        return sessionContext.getRoleName().map(RoleNames.MASTER_ADMIN::equals).orElse(false);
    }

    /** RF-BOV-01 paso 1: registra un cementerio propio con su nombre y dirección. Devuelve su id. */
    @Transactional
    public Long registerCemetery(String name, String address) {
        requireRegister();
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty()) {
            throw new InvalidDataException("Ingrese el nombre del cementerio.");
        }
        String cleanAddress = address == null || address.isBlank() ? null : address.trim();
        Cemetery cemetery = new Cemetery(null, cleanName, cleanAddress);
        return cemeteryRepository.save(cemetery).getId();
    }

    /**
     * RF-BOV-01 pasos 2 y 3: crea las bóvedas de un pabellón, todas DISPONIBLES, con códigos
     * "A1-01" ... (pabellón, nivel y columna). No hay límite de columnas por nivel.
     * 2a: un código repetido en el mismo cementerio no se registra; se informa en {@code skipped}.
     * Tampoco se crea una bóveda donde otra (por ejemplo del formato "A-07") ya ocupa ese nivel y columna.
     */
    @Transactional
    public VaultBatch addPavilion(Long cemeteryId, String pavilion, int levels, int perLevel, int firstLevel) {
        requireRegister();
        String letter = pavilionLetter(pavilion);
        if (levels < 1) {
            throw new InvalidDataException("Ingrese cuántos niveles tiene el pabellón (1 o más).");
        }
        if (perLevel < 1) {
            throw new InvalidDataException("Ingrese cuántas bóvedas tiene cada nivel (1 o más).");
        }
        if (firstLevel < 1) {
            throw new InvalidDataException("El primer nivel es 1 o más.");
        }
        List<String> codes = new ArrayList<>();
        for (int level = firstLevel; level < firstLevel + levels; level++) {
            for (int column = 1; column <= perLevel; column++) {
                codes.add(VaultPosition.code(letter, level, column));
            }
        }
        return createVaults(cemeteryId, codes);
    }

    /** RF-BOV-01 paso 2: registra una sola bóveda con el código que escribe el administrador. */
    @Transactional
    public VaultBatch addVault(Long cemeteryId, String code) {
        requireRegister();
        String clean = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (clean.isEmpty()) {
            throw new InvalidDataException("Ingrese el código de la bóveda.");
        }
        VaultBatch result = createVaults(cemeteryId, List.of(clean));
        if (result.created() == 0) {
            throw new InvalidDataException("La bóveda " + clean + " ya existe en este cementerio o su lugar en el muro ya está ocupado.");
        }
        return result;
    }

    private VaultBatch createVaults(Long cemeteryId, List<String> codes) {
        if (cemeteryId == null) {
            throw new InvalidDataException("Seleccione el cementerio.");
        }
        Cemetery cemetery = cemeteryRepository.findById(cemeteryId)
                .orElseThrow(() -> new ResourceNotFoundException("Cementerio", cemeteryId));
        Set<String> taken = new HashSet<>();
        Set<String> usedSpots = new HashSet<>();
        for (String existing : vaultRepository.findCodesByCemeteryId(cemeteryId)) {
            if (existing != null) {
                taken.add(existing.trim().toUpperCase(Locale.ROOT));
                usedSpots.add(spot(VaultPosition.of(existing)));
            }
        }
        List<Vault> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String code : codes) {
            String spot = spot(VaultPosition.of(code));
            if (!taken.add(code) || (spot != null && !usedSpots.add(spot))) {
                skipped.add(code);
                continue;
            }
            Vault vault = new Vault();
            vault.setCemetery(cemetery);
            vault.setCode(code);
            vault.setStatus(VaultStatus.AVAILABLE); // RF-BOV-01 paso 3
            created.add(vault);
        }
        vaultRepository.saveAll(created);
        return new VaultBatch(created.size(), skipped);
    }

    /** "A|2|15": lugar en el muro, para no poner dos bóvedas en el mismo nivel y columna. */
    private static String spot(VaultPosition position) {
        return position == null ? null
                : position.pavilion() + "|" + position.level() + "|" + position.column();
    }

    private static String pavilionLetter(String pavilion) {
        String clean = pavilion == null ? "" : pavilion.trim().toUpperCase(Locale.ROOT);
        if (!clean.matches("[A-Z]+")) {
            throw new InvalidDataException("El pabellón se escribe con letras, por ejemplo A o B.");
        }
        return clean;
    }

    private void requireRegister() {
        if (!canRegister()) {
            throw new UnauthorizedActionException();
        }
    }

    /**
     * Renueva el arriendo por otros 4 años contados desde el vencimiento actual
     * (si ya venció, el tiempo en mora no se regala). La bóveda vuelve a OCUPADA.
     * Devuelve la nueva fecha de vencimiento.
     */
    @Transactional
    public LocalDate renewLease(Long vaultId) {
        requireAccess();
        Long actorId = currentUserId();
        Vault vault = getVault(vaultId);
        if (vault.getDeceased() == null || VaultState.of(vault, today()) == VaultState.AVAILABLE) {
            throw new InvalidDataException("La bóveda " + vault.getCode() + " está libre: no tiene arriendo que renovar.");
        }
        LocalDate base = vault.getLeaseEndDate() == null ? today() : vault.getLeaseEndDate();
        LocalDate newEnd = leaseEnd(base);
        vault.setLeaseEndDate(newEnd);
        vault.setStatus(VaultStatus.OCCUPIED);
        vaultRepository.save(vault);
        accessLogService.record(actorId, AccessLogService.VAULT_LEASE_RENEWED + vault.getCode());
        return newEnd;
    }

    /**
     * RF-BOV-05: registra la exhumación con el empleado de la sesión, retira al difunto y
     * deja la bóveda DISPONIBLE (RN-30). Solo procede cumplidos 4 años de arriendo (RN-06).
     */
    @Transactional
    public Exhumation exhume(ExhumationData data) {
        requireAccess();
        AppUser actor = sessionContext.getCurrentUser().orElseThrow(UnauthorizedActionException::new);
        if (data == null || data.vaultId() == null) {
            throw new InvalidDataException("Seleccione la bóveda.");
        }
        Vault vault = getVault(data.vaultId());
        if (vault.getDeceased() == null) {
            throw new InvalidDataException("La bóveda " + vault.getCode() + " no tiene un difunto inhumado.");
        }
        if (data.exhumationDate() == null) {
            throw new InvalidDataException("Ingrese la fecha de la exhumación.");
        }
        if (data.exhumationDate().isAfter(today())) {
            throw new InvalidDataException("La fecha de la exhumación no puede ser posterior a hoy.");
        }
        if (data.reason() == null) {
            throw new InvalidDataException("Seleccione el motivo de la exhumación.");
        }
        if (data.destination() == null) {
            throw new InvalidDataException("Seleccione el destino de los restos.");
        }
        if (data.authorizationFile() == null) {
            throw new InvalidDataException("Adjunte la autorización de la exhumación (PDF o imagen).");
        }
        LocalDate allowedFrom = exhumationAllowedFrom(vault);
        if (allowedFrom != null && data.exhumationDate().isBefore(allowedFrom)) {
            throw new InvalidDataException("La exhumación procede desde el " + DATE.format(allowedFrom)
                    + ", cuando se cumplen los " + LEASE_YEARS + " años de arriendo (RN-06).");
        }

        // RN-25: el archivo se copia con FileStorageService y en la BD queda solo su URL.
        // Se copia al final, cuando todo lo demás ya se validó; si la BD no guarda, se borra.
        String url = fileStorageService.store(data.authorizationFile(), AUTHORIZATION_FOLDER);
        deleteFileOnRollback(url);

        Exhumation exhumation = new Exhumation();
        exhumation.setVault(vault);
        exhumation.setDeceased(vault.getDeceased());
        exhumation.setExhumationDate(data.exhumationDate());
        exhumation.setReason(data.reason());
        exhumation.setDestination(data.destination());
        exhumation.setAuthorizationFileUrl(url);
        exhumation.setNotes(data.notes() == null || data.notes().isBlank() ? null : data.notes().trim());
        exhumation.setRegisteredByEmployee(actor.getEmployee());
        Exhumation saved = exhumationRepository.save(exhumation);

        // RN-30: la bóveda queda libre para un nuevo arriendo.
        vault.setDeceased(null);
        vault.setClient(null);
        vault.setLeaseStartDate(null);
        vault.setLeaseEndDate(null);
        vault.setStatus(VaultStatus.AVAILABLE);
        vaultRepository.save(vault);
        accessLogService.record(actor.getId(), AccessLogService.EXHUMATION_REGISTERED + vault.getCode());
        return saved;
    }

    // ---------- reglas ----------

    /** RN-06: fin del arriendo, 4 años después del inicio. */
    public static LocalDate leaseEnd(LocalDate start) {
        return start.plusYears(LEASE_YEARS);
    }

    /** RN-07: un cuerpo no reclamado va a fosa común u osario; otro destino se advierte antes de guardar. */
    public static boolean needsUnclaimedWarning(ExhumationReason reason, ExhumationDestination destination) {
        return reason == ExhumationReason.UNCLAIMED && destination != null
                && !UNCLAIMED_DESTINATIONS.contains(destination);
    }

    /** Meses de arriendo transcurridos, para la barra "Han pasado ... de 4 años". */
    public static long monthsElapsed(LocalDate start, LocalDate today) {
        return start == null || today.isBefore(start) ? 0 : ChronoUnit.MONTHS.between(start, today);
    }

    // ---------- piezas ----------

    private static WallCell cell(VaultRepository.WallRow row, VaultPosition position, LocalDate today) {
        VaultState state = VaultState.of(row.getStatus(), row.getLeaseEndDate(), today);
        boolean hasDeceased = row.getDeceasedName() != null;
        return new WallCell(row.getId(), row.getCode(), position, state,
                hasDeceased ? fullName(row.getDeceasedName(), row.getDeceasedLastname()) : null,
                hasDeceased ? firstWord(row.getDeceasedLastname()) : null,
                row.getClientName() == null ? null : fullName(row.getClientName(), row.getClientLastname()),
                state == VaultState.AVAILABLE ? null : row.getLeaseEndDate());
    }

    /** Historial: la inhumación vigente y las exhumaciones anteriores, de la más reciente a la más antigua. */
    private List<HistoryEvent> history(VaultRepository.DetailRow row, String deceasedName, String clientName) {
        List<HistoryEvent> events = new ArrayList<>();
        if (deceasedName != null && row.getLeaseStartDate() != null) {
            events.add(new HistoryEvent(row.getLeaseStartDate(), "Inhumación de " + deceasedName, true));
            if (clientName != null) {
                events.add(new HistoryEvent(row.getLeaseStartDate(), "Asignada a " + clientName, true));
            }
        }
        for (ExhumationRepository.ExhumationRow e : exhumationRepository.findHistoryRows(row.getId())) {
            events.add(new HistoryEvent(e.getExhumationDate(), "Exhumación de "
                    + fullName(e.getDeceasedName(), e.getDeceasedLastname()) + " · libera la bóveda", false));
        }
        events.sort(Comparator.comparing((HistoryEvent event) -> event.date(),
                Comparator.nullsLast(Comparator.reverseOrder())));
        return events;
    }

    /** RN-06: la exhumación procede 4 años después del inicio del arriendo. */
    private static LocalDate exhumationAllowedFrom(Vault vault) {
        return vault.getDeceased() == null || vault.getLeaseStartDate() == null
                ? null
                : leaseEnd(vault.getLeaseStartDate());
    }

    /** "Martha Cecilia Durán" si hay titular; si no, "Familia Durán" por el apellido del difunto. */
    private static String holder(VaultRepository.WallRow row) {
        if (row.getClientName() != null) {
            return fullName(row.getClientName(), row.getClientLastname());
        }
        return row.getDeceasedName() == null ? "Sin titular" : "Familia " + firstWord(row.getDeceasedLastname());
    }

    /** Si la BD no guarda la exhumación, el archivo copiado no queda huérfano en la carpeta. */
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

    private static String document(String type, String number) {
        if (number == null) {
            return null;
        }
        return type == null || type.isBlank() ? number : type + " " + number;
    }

    /** Nombre y apellido; el NN tiene el apellido vacío (RF-DIF-02). */
    private static String fullName(String name, String lastname) {
        return ((name == null ? "" : name) + " " + (lastname == null ? "" : lastname)).trim();
    }

    private static String firstWord(String text) {
        return text == null || text.isBlank() ? "" : text.trim().split("\\s+")[0];
    }

    /** "A" antes que "B"; el pabellón General (códigos sin formato) al final. */
    private static int comparePavilions(String a, String b) {
        boolean generalA = VaultPosition.GENERAL.equals(a);
        boolean generalB = VaultPosition.GENERAL.equals(b);
        if (generalA != generalB) {
            return generalA ? 1 : -1;
        }
        return a.compareTo(b);
    }

    private static String pattern(String text) {
        String clean = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        return "%" + clean + "%";
    }

    private Vault getVault(Long id) {
        return vaultRepository.findDetailById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bóveda", id));
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private void requireAccess() {
        boolean allowed = sessionContext.getRoleName()
                .map(AppModule.CEMENTERIO::isAllowedFor)
                .orElse(false);
        if (!allowed) {
            throw new UnauthorizedActionException();
        }
    }

    private Long currentUserId() {
        return sessionContext.getCurrentUser()
                .map(user -> user.getId())
                .orElseThrow(UnauthorizedActionException::new);
    }
}
