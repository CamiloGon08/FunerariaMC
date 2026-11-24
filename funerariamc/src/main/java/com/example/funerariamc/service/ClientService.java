package com.example.funerariamc.service;

import com.example.funerariamc.exception.ExistingClientException;
import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.ResourceNotFoundException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.Client;
import com.example.funerariamc.utils.enums.ClientType;
import com.example.funerariamc.utils.enums.TransactionStatus;
import com.example.funerariamc.utils.enums.TransactionType;
import com.example.funerariamc.persistence.ClientRepository;
import com.example.funerariamc.persistence.PersonRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;

/**
 * Módulo de Clientes (HU-01, RF-PER-01, CU-04). Lo usan Vendedor, Secretaría y
 * Administrador Maestro (RN-21, RN-22). Un cliente no se borra: después tendrá planes,
 * contratos y ventas asociados.
 */
@Service
public class ClientService {

    /** Máximo de filas por página que acepta la búsqueda. */
    public static final int MAX_PAGE_SIZE = 50;

    /** Documentos de una persona natural: cédula, tarjeta de identidad, cédula de extranjería y pasaporte. */
    public static final List<String> NATURAL_DOCUMENT_TYPES = List.of("CC", "TI", "CE", "PA");
    /** Una empresa se identifica con su NIT. */
    public static final String NIT = "NIT";

    /** Pesos del dígito de verificación del NIT (DIAN), del dígito de la derecha hacia la izquierda. */
    private static final int[] NIT_WEIGHTS = {3, 7, 13, 17, 19, 23, 29, 37, 41, 43, 47, 53, 59, 67, 71};
    private static final Set<String> NUMERIC_DOCUMENTS = Set.of("CC", "TI", NIT);

    /** Límites de almacenamiento de person indicados en el contrato del módulo de Clientes. */
    static final int MAX_NAME = 25;
    static final int MAX_DOCUMENT = 10;
    static final int MAX_EMAIL = 30;
    /**
     * Correo bien escrito: usuario, @, dominio y una extensión de al menos dos letras
     * (nombre@gmail.com, nombre@hotmail.com, nombre@unicesar.edu.co). Lo usan clientes y empleados.
     */
    public static final String EMAIL_PATTERN = "[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}";
    static final int MAX_ADDRESS = 40;

    /** Filtro de saldo de la pantalla (propuesta P-11): todos, con saldo pendiente o al día. */
    public enum BalanceFilter {
        ALL("Con saldo y sin saldo", "ALL"),
        WITH_BALANCE("Con saldo pendiente", "WITH"),
        UP_TO_DATE("Al día", "WITHOUT");

        private final String label;
        private final String code;

        BalanceFilter(String label, String code) {
            this.label = label;
            this.code = code;
        }

        public String getLabel() {
            return label;
        }
    }

    /** Filtro de plan exequial de las tarjetas: todos, con plan (no cancelado) o sin plan. */
    public enum PlanFilter {
        ALL("Plan: todos", "ALL"),
        WITH_PLAN("Con plan exequial", "WITH"),
        WITHOUT_PLAN("Sin plan exequial", "WITHOUT");

        private final String label;
        private final String code;

        PlanFilter(String label, String code) {
            this.label = label;
            this.code = code;
        }

        public String getLabel() {
            return label;
        }
    }

    private final ClientRepository clientRepository;
    private final PersonRepository personRepository;
    private final AccessLogService accessLogService;
    private final SessionContext sessionContext;

    public ClientService(ClientRepository clientRepository, PersonRepository personRepository,
                         AccessLogService accessLogService, SessionContext sessionContext) {
        this.clientRepository = clientRepository;
        this.personRepository = personRepository;
        this.accessLogService = accessLogService;
        this.sessionContext = sessionContext;
    }

    /**
     * Página de la lista de Clientes. text busca por nombre, razón social o documento
     * (vacío trae todos); type en null no filtra.
     */
    @Transactional(readOnly = true)
    public Page<Client> search(String text, ClientType type, int page, int size) {
        requireAccess();
        Collection<ClientType> types = type == null ? List.of(ClientType.values()) : List.of(type);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return clientRepository.search(searchPattern(text), types, PageRequest.of(safePage, safeSize));
    }

    /**
     * Igual que la búsqueda anterior, más el filtro de saldo de las tarjetas (P-11).
     * balance en null equivale a ALL.
     */
    @Transactional(readOnly = true)
    public Page<Client> search(String text, ClientType type, BalanceFilter balance, int page, int size) {
        if (balance == null || balance == BalanceFilter.ALL) {
            return search(text, type, page, size);
        }
        requireAccess();
        Collection<ClientType> types = type == null ? List.of(ClientType.values()) : List.of(type);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return clientRepository.searchByBalance(searchPattern(text), types, balance.code,
                TransactionType.INCOME, TransactionStatus.ACTIVE, PageRequest.of(safePage, safeSize));
    }

    /**
     * Igual que la búsqueda anterior, más el filtro de plan exequial. plan en null equivale a ALL.
     */
    @Transactional(readOnly = true)
    public Page<Client> search(String text, ClientType type, BalanceFilter balance, PlanFilter plan, int page, int size) {
        if (plan == null || plan == PlanFilter.ALL) {
            return search(text, type, balance, page, size);
        }
        requireAccess();
        Collection<ClientType> types = type == null ? List.of(ClientType.values()) : List.of(type);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        String balanceCode = balance == null ? BalanceFilter.ALL.code : balance.code;
        return clientRepository.searchByPlanAndBalance(searchPattern(text), types, plan.code, balanceCode,
                TransactionType.INCOME, TransactionStatus.ACTIVE, PageRequest.of(safePage, safeSize));
    }

    /** Cuántos clientes hay de ese tipo; type en null cuenta a todos. */
    @Transactional(readOnly = true)
    public long count(ClientType type) {
        requireAccess();
        return type == null ? clientRepository.count() : clientRepository.countByClientType(type);
    }

    @Transactional(readOnly = true)
    public Client findById(Long id) {
        requireAccess();
        return getClient(id);
    }

    /**
     * El formulario lo consulta al salir del campo documento para avisar antes de
     * llenar todo lo demás (RF-PER-01 2a).
     */
    @Transactional(readOnly = true)
    public Optional<Client> findByDocument(String documentNumber) {
        requireAccess();
        if (documentNumber == null || documentNumber.isBlank()) {
            return Optional.empty();
        }
        return clientRepository.findByDocumentNumber(normalizeDocument(documentNumber));
    }

    /** HU-01: crea la persona y el cliente en una transacción, con la fecha de registro de hoy (Colombia). */
    @Transactional
    public Client register(ClientData data) {
        requireAccess();
        Long actorId = currentUserId();
        validate(data);
        String document = normalizeDocument(data.documentNumber());
        checkDocumentIsFree(document, null);

        Client client = new Client();
        copyData(client, data, document);
        client.setRegistrationDate(LocalDate.now(AccessLogService.APP_ZONE));
        Client saved = clientRepository.save(client);

        log(actorId, AccessLogService.CLIENT_CREATED, saved);
        return saved;
    }

    /** Corrige los datos de contacto o del documento de un cliente ya registrado. */
    @Transactional
    public Client update(Long id, ClientData data) {
        requireAccess();
        Long actorId = currentUserId();
        validate(data);
        Client client = getClient(id);
        String document = normalizeDocument(data.documentNumber());
        checkDocumentIsFree(document, id);

        copyData(client, data, document);
        log(actorId, AccessLogService.CLIENT_UPDATED, client);
        return client;
    }

    /** Nombre para mostrar: "Nombres Apellidos" o la razón social de una empresa. */
    public static String displayName(Client client) {
        String lastname = client.getLastname();
        return lastname == null || lastname.isBlank()
                ? client.getName()
                : client.getName() + " " + lastname;
    }

    private void checkDocumentIsFree(String document, Long ownId) {
        Optional<Client> existing = clientRepository.findByDocumentNumber(document)
                .filter(client -> ownId == null || !Objects.equals(client.getId(), ownId));
        if (existing.isPresent()) {
            throw new ExistingClientException(existing.get());
        }
        // person.document_number es UNIQUE: tampoco puede ser el documento de un empleado.
        boolean usedByOtherPerson = ownId == null
                ? personRepository.existsByDocumentNumber(document)
                : personRepository.existsByDocumentNumberAndIdNot(document, ownId);
        if (usedByOtherPerson) {
            throw new InvalidDataException("Ese documento ya pertenece a un empleado registrado en Personal.");
        }
    }

    private Client getClient(Long id) {
        return clientRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Cliente", id));
    }

    /** RN-21 y RN-22: Vendedor, Secretaría y Administrador Maestro, igual que el menú. */
    private void requireAccess() {
        boolean allowed = sessionContext.getRoleName()
                .map(AppModule.CLIENTES::isAllowedFor)
                .orElse(false);
        if (!allowed) {
            throw new UnauthorizedActionException();
        }
    }

    private void copyData(Client client, ClientData data, String document) {
        boolean corporate = data.clientType().isCorporate();
        client.setClientType(data.clientType());
        client.setDocumentType(data.documentType());
        client.setDocumentNumber(document);
        // Una persona queda con mayúscula inicial en cada nombre y apellido; la razón social, tal como se escribió
        client.setName(corporate ? data.name().trim() : PersonNames.titleCase(data.name()));
        // person.lastname es NOT NULL: una empresa no tiene apellidos, se guarda vacío.
        client.setLastname(corporate ? "" : PersonNames.titleCase(data.lastname()));
        client.setPhone(normalizePhone(data.phone()));
        client.setEmail(blankToNull(data.email()));
        client.setAddress(data.address().trim());
        // Una empresa no tiene fecha de nacimiento
        client.setBirthDate(corporate ? null : data.birthDate());
    }

    /** Edad desde la que se tiene cédula de ciudadanía (mayoría de edad en Colombia). */
    public static final int ADULT_AGE = 18;
    /** Edad desde la que la Registraduría expide la tarjeta de identidad (antes es el registro civil). */
    public static final int IDENTITY_CARD_MIN_AGE = 7;

    /**
     * La edad debe corresponder al documento: con cédula de ciudadanía, 18 años o más; con tarjeta de
     * identidad, de 7 a 17 años. Devuelve el aviso, o null si está bien (o si no hay fecha de nacimiento).
     */
    public static String ageProblem(String documentType, LocalDate birthDate) {
        if (birthDate == null) {
            return null;
        }
        int age = java.time.Period.between(birthDate, LocalDate.now(AccessLogService.APP_ZONE)).getYears();
        if ("CC".equals(documentType) && age < ADULT_AGE) {
            return "Con cédula de ciudadanía la persona debe tener " + ADULT_AGE + " años o más. "
                    + "Si es menor de edad, regístrela con tarjeta de identidad.";
        }
        if ("TI".equals(documentType) && (age < IDENTITY_CARD_MIN_AGE || age >= ADULT_AGE)) {
            return "La tarjeta de identidad es para personas de " + IDENTITY_CARD_MIN_AGE + " a " + (ADULT_AGE - 1)
                    + " años. " + (age >= ADULT_AGE ? "Mayor de edad: regístrela con cédula." : "Revise la fecha de nacimiento.");
        }
        return null;
    }

    private void validate(ClientData data) {
        if (data == null) {
            throw new InvalidDataException("Los datos del cliente son obligatorios.");
        }
        if (data.clientType() == null) {
            throw new InvalidDataException("Seleccione el tipo de cliente.");
        }
        boolean corporate = data.clientType().isCorporate();
        require(data.documentType(), "El tipo de documento es obligatorio.");
        if (!corporate && data.birthDate() != null && data.birthDate().isAfter(LocalDate.now(AccessLogService.APP_ZONE))) {
            throw new InvalidDataException("La fecha de nacimiento no puede ser futura.");
        }
        if (!corporate && "TI".equals(data.documentType()) && data.birthDate() == null) {
            throw new InvalidDataException("Con tarjeta de identidad escriba la fecha de nacimiento.");
        }
        String age = ageProblem(data.documentType(), corporate ? null : data.birthDate());
        if (age != null) {
            throw new InvalidDataException(age);
        }
        if (corporate && !NIT.equals(data.documentType())) {
            throw new InvalidDataException("Una empresa se registra con su NIT.");
        }
        if (!corporate && !NATURAL_DOCUMENT_TYPES.contains(data.documentType())) {
            throw new InvalidDataException("Una persona natural se registra con cédula, tarjeta de identidad, "
                    + "cédula de extranjería o pasaporte.");
        }
        require(data.documentNumber(), "El número de documento es obligatorio.");
        validateDocument(data.documentType(), normalizeDocument(data.documentNumber()));

        require(data.name(), corporate ? "La razón social es obligatoria." : "Los nombres son obligatorios.");
        maxLength(data.name(), MAX_NAME, corporate ? "La razón social" : "Los nombres");
        // La razón social puede llevar números; los nombres de una persona, no
        if (!corporate) {
            PersonNames.validate(data.name(), "Los nombres");
        }
        if (!corporate) {
            require(data.lastname(), "Los apellidos son obligatorios.");
            maxLength(data.lastname(), MAX_NAME, "Los apellidos");
            PersonNames.validate(data.lastname(), "Los apellidos");
        }

        require(data.phone(), "El teléfono es obligatorio.");
        // person.phone es varchar(10): se guardan solo los dígitos (celular 10, fijo 7).
        if (!normalizePhone(data.phone()).matches("\\d{7,10}")) {
            throw new InvalidDataException("El teléfono debe tener entre 7 y 10 dígitos.");
        }
        // El correo es obligatorio para registrar al cliente y debe estar bien escrito.
        require(data.email(), "El correo es obligatorio.");
        if (!data.email().trim().matches(EMAIL_PATTERN)) {
            throw new InvalidDataException("El correo no es válido. Escríbalo como nombre@gmail.com.");
        }
        maxLength(data.email(), MAX_EMAIL, "El correo");
        require(data.address(), "La dirección es obligatoria.");
        maxLength(data.address(), MAX_ADDRESS, "La dirección");
    }

    private static void validateDocument(String type, String document) {
        if (document.length() > MAX_DOCUMENT) {
            throw new InvalidDataException("El número de documento es demasiado largo.");
        }
        if (NUMERIC_DOCUMENTS.contains(type) && !document.matches("\\d+")) {
            throw new InvalidDataException("El número de documento solo puede tener dígitos.");
        }
        if (!document.matches("[A-Za-z0-9]+")) {
            throw new InvalidDataException("El número de documento solo puede tener letras y dígitos.");
        }
        if ("CC".equals(type) && (document.length() < 6 || document.length() > 10)) {
            throw new InvalidDataException("La cédula debe tener entre 6 y 10 dígitos.");
        }
        if ("TI".equals(type) && (document.length() < 6 || document.length() > 10)) {
            throw new InvalidDataException("La tarjeta de identidad debe tener entre 6 y 10 dígitos.");
        }
        if (NIT.equals(type)) {
            // Se escribe con el dígito de verificación: 800.197.268-4 → 8001972684.
            if (document.length() != 10) {
                throw new InvalidDataException("Escriba el NIT con su dígito de verificación, por ejemplo 800.197.268-4.");
            }
            if (nitCheckDigit(document.substring(0, 9)) != document.charAt(9) - '0') {
                throw new InvalidDataException("El dígito de verificación del NIT no coincide.");
            }
        }
    }

    /** Dígito de verificación del NIT según la DIAN (módulo 11). */
    static int nitCheckDigit(String nit) {
        int sum = 0;
        for (int i = 0; i < nit.length(); i++) {
            int digit = nit.charAt(nit.length() - 1 - i) - '0';
            sum += digit * NIT_WEIGHTS[i];
        }
        int remainder = sum % 11;
        return remainder > 1 ? 11 - remainder : remainder;
    }

    private Long currentUserId() {
        return sessionContext.getCurrentUser()
                .map(user -> user.getId())
                .orElseThrow(UnauthorizedActionException::new);
    }

    private void log(Long actorId, String action, Client client) {
        accessLogService.record(actorId, action + client.getDocumentNumber());
    }

    private static void require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new InvalidDataException(message);
        }
    }

    private static void maxLength(String value, int max, String what) {
        if (value != null && value.trim().length() > max) {
            throw new InvalidDataException(what + " no puede superar " + max + " caracteres.");
        }
    }

    /** "1.065.832.417", "800 197 268-4" y "8001972684" se guardan sin puntos, espacios ni guiones. */
    static String normalizeDocument(String document) {
        return document.replaceAll("[.\\s-]", "").trim().toUpperCase(Locale.ROOT);
    }

    /** "301 556 2087" y "(605) 580-4411" se guardan como "3015562087" y "6055804411". */
    static String normalizePhone(String phone) {
        return phone == null ? "" : phone.replaceAll("[\\s().-]", "");
    }

    /** "%texto%" en minúsculas; "1.065.832" se busca como "1065832". */
    private static String searchPattern(String text) {
        if (text == null || text.isBlank()) {
            return "%";
        }
        String trimmed = text.trim();
        String value = trimmed.matches("[\\d.\\s-]+") ? normalizeDocument(trimmed) : trimmed.toLowerCase(Locale.ROOT);
        return "%" + value + "%";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
