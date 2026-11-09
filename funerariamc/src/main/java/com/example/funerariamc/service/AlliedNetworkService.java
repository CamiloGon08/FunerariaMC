package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.ResourceNotFoundException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AlliedFuneralHome;
import com.example.funerariamc.model.AlliedPayment;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Deceased;
import com.example.funerariamc.model.FinancialTransaction;
import com.example.funerariamc.model.ServiceContract;
import com.example.funerariamc.model.enums.AlliedPaymentStatus;
import com.example.funerariamc.model.enums.ContractStatus;
import com.example.funerariamc.model.enums.TransactionStatus;
import com.example.funerariamc.model.enums.TransactionType;
import com.example.funerariamc.persistence.AlliedFuneralHomeRepository;
import com.example.funerariamc.persistence.AlliedPaymentRepository;
import com.example.funerariamc.persistence.FinancialTransactionRepository;
import com.example.funerariamc.persistence.ServiceContractRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Red de funerarias aliadas: registro de las funerarias con convenio y de los pagos por los servicios que
 * prestan a nuestros afiliados fuera de la ciudad. El pago se guarda pendiente; al marcarlo pagado se registra
 * el egreso en Finanzas. Nada se borra: una funeraria se desactiva y un pago se anula (y con él su egreso).
 * Solo el Administrador Maestro usa este módulo.
 */
@Service
public class AlliedNetworkService {

    static final int MAX_NAME = 100;
    static final int MAX_NIT = 15;
    static final int MAX_CITY = 50;
    static final int MAX_EMAIL = 50;
    /** Prefijo de la descripción del egreso: "Pago a funeraria aliada · ...". */
    public static final String EXPENSE_PREFIX = "Pago a funeraria aliada · ";

    /** Cómo va la cuenta con una funeraria. */
    public enum HomeState {
        UP_TO_DATE("Al día"),
        WITH_BALANCE("Con saldo"),
        INACTIVE("Inactiva");

        private final String label;

        HomeState(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** Una tarjeta del tablero. pending = saldo por pagar (pagos pendientes). */
    public record HomeCard(Long id, String name, String nit, String phone, String city, String email,
                           LocalDate agreementStart, boolean active, long servicesThisMonth, BigDecimal pending,
                           long pendingCount, LocalDate oldestPending) {

        public HomeState state() {
            if (!active) {
                return HomeState.INACTIVE;
            }
            return pending.signum() > 0 ? HomeState.WITH_BALANCE : HomeState.UP_TO_DATE;
        }

        /** "FE" para "Funeraria La Esperanza": primeras letras de las dos primeras palabras que no son conectores. */
        public String initials() {
            return AlliedNetworkService.initials(name);
        }
    }

    /** Tablero completo: tarjetas y los indicadores de arriba. */
    public record Overview(List<HomeCard> homes, long active, long total, long servicesThisMonth,
                           long servicesLastMonth, BigDecimal pending, long homesWithBalance, long pendingPayments,
                           LocalDate oldestPending, YearMonth month) {
    }

    /** Un pago en el panel de la funeraria. paidAt = fecha del egreso en hora de Colombia. */
    public record PaymentRow(Long id, String contractNumber, String deceasedName, LocalDate serviceDate,
                             BigDecimal amount, AlliedPaymentStatus status, String movementCode, LocalDateTime paidAt) {
    }

    /** Panel de una funeraria: datos, pagos y servicios del año. */
    public record HomeDetail(HomeCard home, List<PaymentRow> payments, long servicesThisYear, int year) {
    }

    /** Contrato para el buscador del pago. */
    public record ContractOption(Long id, String number, String label) {
    }

    private final AlliedFuneralHomeRepository homeRepository;
    private final AlliedPaymentRepository paymentRepository;
    private final ServiceContractRepository contractRepository;
    private final FinancialTransactionRepository transactionRepository;
    private final AccessLogService accessLogService;
    private final SessionContext sessionContext;
    private final Clock clock;

    @Autowired
    public AlliedNetworkService(AlliedFuneralHomeRepository homeRepository, AlliedPaymentRepository paymentRepository,
                                ServiceContractRepository contractRepository,
                                FinancialTransactionRepository transactionRepository,
                                AccessLogService accessLogService, SessionContext sessionContext) {
        this(homeRepository, paymentRepository, contractRepository, transactionRepository, accessLogService,
                sessionContext, Clock.system(AccessLogService.APP_ZONE));
    }

    /** Las pruebas fijan la fecha de hoy con un Clock. */
    AlliedNetworkService(AlliedFuneralHomeRepository homeRepository, AlliedPaymentRepository paymentRepository,
                         ServiceContractRepository contractRepository,
                         FinancialTransactionRepository transactionRepository,
                         AccessLogService accessLogService, SessionContext sessionContext, Clock clock) {
        this.homeRepository = homeRepository;
        this.paymentRepository = paymentRepository;
        this.contractRepository = contractRepository;
        this.transactionRepository = transactionRepository;
        this.accessLogService = accessLogService;
        this.sessionContext = sessionContext;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    // ---------- consultas ----------

    /** Funerarias con su saldo; primero las que tienen saldo (de mayor a menor), luego por nombre. */
    @Transactional(readOnly = true)
    public Overview overview() {
        requireMaster();
        YearMonth month = YearMonth.now(clock);
        Map<Long, AlliedPaymentRepository.HomeTotals> totals = new HashMap<>();
        for (AlliedPaymentRepository.HomeTotals t : paymentRepository.totalsByHome(AlliedPaymentStatus.PENDING,
                AlliedPaymentStatus.VOIDED, month.atDay(1), month.plusMonths(1).atDay(1))) {
            totals.put(t.getHomeId(), t);
        }
        List<HomeCard> cards = new ArrayList<>();
        for (AlliedFuneralHome home : homeRepository.findAllByOrderByNameAsc()) {
            cards.add(card(home, totals.get(home.getId())));
        }
        cards.sort(Comparator.comparing((HomeCard card) -> card.pending()).reversed()
                .thenComparing(c -> c.name().toLowerCase(Locale.ROOT)));
        long active = cards.stream().filter(card -> card.active()).count();
        long services = cards.stream().mapToLong(card -> card.servicesThisMonth()).sum();
        long lastMonth = paymentRepository.countServices(AlliedPaymentStatus.VOIDED, month.minusMonths(1).atDay(1),
                month.atDay(1));
        BigDecimal pending = cards.stream().map(card -> card.pending())
                .reduce(BigDecimal.ZERO, (total, amount) -> total.add(amount));
        long withBalance = cards.stream().filter(c -> c.pending().signum() > 0).count();
        long pendingPayments = cards.stream().mapToLong(card -> card.pendingCount()).sum();
        LocalDate oldest = cards.stream().map(card -> card.oldestPending()).filter(d -> d != null)
                .min(Comparator.naturalOrder()).orElse(null);
        return new Overview(cards, active, cards.size(), services, lastMonth, pending, withBalance, pendingPayments,
                oldest, month);
    }

    @Transactional(readOnly = true)
    public HomeDetail detail(Long homeId) {
        requireMaster();
        AlliedFuneralHome home = findHome(homeId);
        YearMonth month = YearMonth.now(clock);
        AlliedPaymentRepository.HomeTotals totals = paymentRepository.totalsByHome(AlliedPaymentStatus.PENDING,
                        AlliedPaymentStatus.VOIDED, month.atDay(1), month.plusMonths(1).atDay(1)).stream()
                .filter(t -> homeId.equals(t.getHomeId())).findFirst().orElse(null);
        List<PaymentRow> payments = paymentRepository.findByHome(homeId).stream().map(AlliedNetworkService::row).toList();
        int year = month.getYear();
        long servicesYear = paymentRepository.countServicesOfHome(homeId, AlliedPaymentStatus.VOIDED,
                LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1));
        return new HomeDetail(card(home, totals), payments, servicesYear, year);
    }

    /** Contratos no cancelados que coinciden con el texto (número, difunto o cliente), máximo 20. */
    @Transactional(readOnly = true)
    public List<ContractOption> searchContracts(String text) {
        requireMaster();
        String pattern = "%" + (text == null ? "" : text.trim().toLowerCase(Locale.ROOT)) + "%";
        return contractRepository.searchNotCancelled(ContractStatus.CANCELLED, pattern, PageRequest.of(0, 20)).stream()
                .map(c -> new ContractOption(c.getId(), c.getContractNumber(),
                        c.getContractNumber() + " · " + fullName(c.getDeceased())))
                .toList();
    }

    static HomeCard card(AlliedFuneralHome home, AlliedPaymentRepository.HomeTotals totals) {
        return new HomeCard(home.getId(), home.getName(), home.getNit(), home.getContactPhone(), home.getCity(),
                home.getEmail(), home.getAgreementStartDate(), !Boolean.FALSE.equals(home.getActive()),
                totals == null ? 0 : totals.getServices(),
                totals == null || totals.getPending() == null ? BigDecimal.ZERO : totals.getPending(),
                totals == null ? 0 : totals.getPendingCount(), totals == null ? null : totals.getOldestPending());
    }

    static PaymentRow row(AlliedPayment p) {
        ServiceContract contract = p.getRelatedServiceContract();
        FinancialTransaction expense = p.getFinancialTransaction();
        return new PaymentRow(p.getId(), contract == null ? null : contract.getContractNumber(),
                contract == null ? null : fullName(contract.getDeceased()), p.getPaymentDate(), p.getAmount(),
                p.getStatus() == null ? AlliedPaymentStatus.PENDING : p.getStatus(),
                expense == null ? null : FinancialTransaction.code(expense.getId()),
                expense == null ? null : AccessLogService.toAppTime(expense.getTransactionDate()));
    }

    // ---------- funerarias ----------

    /** Registra una funeraria aliada (activa). El NIT no se puede repetir. */
    @Transactional
    public Long registerHome(AlliedHomeData data) {
        AppUser actor = requireMaster();
        AlliedFuneralHome home = new AlliedFuneralHome();
        apply(home, data, null);
        home.setActive(true);
        AlliedFuneralHome saved = homeRepository.save(home);
        accessLogService.record(actor.getId(), AccessLogService.ALLIED_HOME_REGISTERED + saved.getNit());
        return saved.getId();
    }

    @Transactional
    public void updateHome(Long homeId, AlliedHomeData data) {
        AppUser actor = requireMaster();
        AlliedFuneralHome home = findHome(homeId);
        apply(home, data, homeId);
        homeRepository.save(home);
        accessLogService.record(actor.getId(), AccessLogService.ALLIED_HOME_UPDATED + home.getNit());
    }

    /** Desactiva o reactiva el convenio. Una funeraria inactiva no recibe pagos nuevos; los que tiene siguen. */
    @Transactional
    public void setActive(Long homeId, boolean active) {
        AppUser actor = requireMaster();
        AlliedFuneralHome home = findHome(homeId);
        if (active == !Boolean.FALSE.equals(home.getActive())) {
            throw new InvalidDataException(active ? "La funeraria ya está activa." : "La funeraria ya está inactiva.");
        }
        home.setActive(active);
        homeRepository.save(home);
        accessLogService.record(actor.getId(), (active ? AccessLogService.ALLIED_HOME_ACTIVATED
                : AccessLogService.ALLIED_HOME_DEACTIVATED) + home.getNit());
    }

    private void apply(AlliedFuneralHome home, AlliedHomeData data, Long exceptId) {
        if (data == null) {
            throw new InvalidDataException("Complete los datos de la funeraria.");
        }
        String name = trim(data.name());
        if (name.isEmpty()) {
            throw new InvalidDataException("Escriba el nombre de la funeraria.");
        }
        if (name.length() > MAX_NAME) {
            throw new InvalidDataException("El nombre admite máximo " + MAX_NAME + " caracteres.");
        }
        String nit = trim(data.nit()).replace(" ", "");
        if (nit.isEmpty()) {
            throw new InvalidDataException("Escriba el NIT de la funeraria.");
        }
        if (!nit.matches("[\\d.]+(-\\d)?") || nitDigits(nit).length() < 6) {
            throw new InvalidDataException("El NIT debe tener solo números y, si lo lleva, el dígito de verificación"
                    + " después de un guion (ej.: 900214556-3).");
        }
        if (nit.length() > MAX_NIT) {
            throw new InvalidDataException("El NIT admite máximo " + MAX_NIT + " caracteres.");
        }
        if (homeRepository.existsNit(nitDigits(nit), exceptId)) {
            throw new InvalidDataException("Ya hay una funeraria aliada registrada con ese NIT.");
        }
        String phone = ClientService.normalizePhone(data.phone());
        if (phone.isEmpty()) {
            throw new InvalidDataException("Escriba el teléfono de contacto.");
        }
        if (!phone.matches("\\d{7,10}")) {
            throw new InvalidDataException("El teléfono debe tener entre 7 y 10 dígitos.");
        }
        String city = trim(data.city());
        if (city.length() > MAX_CITY) {
            throw new InvalidDataException("La ciudad admite máximo " + MAX_CITY + " caracteres.");
        }
        String email = trim(data.email());
        if (!email.isEmpty() && !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new InvalidDataException("El correo no es válido.");
        }
        if (email.length() > MAX_EMAIL) {
            throw new InvalidDataException("El correo admite máximo " + MAX_EMAIL + " caracteres.");
        }
        if (data.agreementStart() == null) {
            throw new InvalidDataException("Elija la fecha de inicio del convenio.");
        }
        home.setName(name);
        home.setNit(nit);
        home.setContactPhone(phone);
        home.setCity(city.isEmpty() ? null : city);
        home.setEmail(email.isEmpty() ? null : email);
        home.setAgreementStartDate(data.agreementStart());
    }

    // ---------- pagos ----------

    /** Registra el pago por un servicio prestado; queda pendiente hasta que se marque como pagado. */
    @Transactional
    public Long registerPayment(AlliedPaymentData data) {
        AppUser actor = requireMaster();
        if (data == null || data.homeId() == null) {
            throw new InvalidDataException("Elija la funeraria aliada.");
        }
        AlliedFuneralHome home = findHome(data.homeId());
        if (Boolean.FALSE.equals(home.getActive())) {
            throw new InvalidDataException(home.getName() + " está inactiva: actívela antes de registrarle pagos.");
        }
        if (data.contractId() == null) {
            throw new InvalidDataException("Elija el contrato del servicio que prestó la funeraria.");
        }
        ServiceContract contract = contractRepository.findById(data.contractId())
                .orElseThrow(() -> new ResourceNotFoundException("Contrato", data.contractId()));
        if (contract.getStatus() == ContractStatus.CANCELLED) {
            throw new InvalidDataException("El contrato " + contract.getContractNumber() + " está cancelado.");
        }
        BigDecimal amount = data.amount() == null ? null : data.amount().setScale(2, RoundingMode.HALF_UP);
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidDataException("El monto debe ser mayor que cero.");
        }
        if (data.serviceDate() == null) {
            throw new InvalidDataException("Elija la fecha del servicio.");
        }
        AlliedPayment payment = new AlliedPayment();
        payment.setAlliedFuneralHome(home);
        payment.setRelatedServiceContract(contract);
        payment.setAmount(amount);
        payment.setPaymentDate(data.serviceDate());
        payment.setStatus(AlliedPaymentStatus.PENDING);
        AlliedPayment saved = paymentRepository.save(payment);
        accessLogService.record(actor.getId(), AccessLogService.ALLIED_PAYMENT_REGISTERED + home.getNit() + " "
                + contract.getContractNumber() + " " + amount.toPlainString());
        return saved.getId();
    }

    /** Marca el pago como pagado y registra el egreso en Finanzas con la fecha y hora de ahora. */
    @Transactional
    public void markPaid(Long paymentId) {
        AppUser actor = requireMaster();
        AlliedPayment payment = findPayment(paymentId);
        if (payment.getStatus() != AlliedPaymentStatus.PENDING) {
            throw new InvalidDataException(payment.getStatus() == AlliedPaymentStatus.PAID
                    ? "El pago ya está marcado como pagado." : "El pago está anulado.");
        }
        FinancialTransaction expense = new FinancialTransaction();
        expense.setTransactionType(TransactionType.EXPENSE);
        expense.setAmount(payment.getAmount());
        expense.setStatus(TransactionStatus.ACTIVE);
        expense.setEmployee(actor.getEmployee());
        String contract = payment.getRelatedServiceContract() == null ? ""
                : " · contrato " + payment.getRelatedServiceContract().getContractNumber();
        String description = EXPENSE_PREFIX + payment.getAlliedFuneralHome().getName() + contract;
        expense.setDescription(description.length() > 150 ? description.substring(0, 150) : description);
        FinancialTransaction saved = transactionRepository.save(expense);
        payment.setFinancialTransaction(saved);
        payment.setStatus(AlliedPaymentStatus.PAID);
        paymentRepository.save(payment);
        accessLogService.record(actor.getId(), AccessLogService.ALLIED_PAYMENT_PAID + payment.getAlliedFuneralHome().getNit()
                + " " + FinancialTransaction.code(saved.getId()) + " " + payment.getAmount().toPlainString());
    }

    /** Anula el pago. Si ya estaba pagado, también se anula su egreso en Finanzas. */
    @Transactional
    public void voidPayment(Long paymentId) {
        AppUser actor = requireMaster();
        AlliedPayment payment = findPayment(paymentId);
        if (payment.getStatus() == AlliedPaymentStatus.VOIDED) {
            throw new InvalidDataException("El pago ya está anulado.");
        }
        FinancialTransaction expense = payment.getFinancialTransaction();
        if (expense != null && expense.getStatus() != TransactionStatus.VOIDED) {
            expense.setStatus(TransactionStatus.VOIDED);
            transactionRepository.save(expense);
        }
        payment.setStatus(AlliedPaymentStatus.VOIDED);
        paymentRepository.save(payment);
        accessLogService.record(actor.getId(), AccessLogService.ALLIED_PAYMENT_VOIDED + payment.getAlliedFuneralHome().getNit()
                + " " + payment.getAmount().toPlainString());
    }

    // ---------- apoyo ----------

    private AlliedFuneralHome findHome(Long id) {
        if (id == null) {
            throw new InvalidDataException("Elija la funeraria aliada.");
        }
        return homeRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Funeraria aliada", id));
    }

    private AlliedPayment findPayment(Long id) {
        if (id == null) {
            throw new InvalidDataException("Elija el pago.");
        }
        return paymentRepository.findDetailById(id).orElseThrow(() -> new ResourceNotFoundException("Pago", id));
    }

    /** Solo los dígitos del NIT, para comparar sin importar puntos ni guion. */
    static String nitDigits(String nit) {
        return nit == null ? "" : nit.replaceAll("[^0-9]", "");
    }

    /** Palabras que no cuentan para las iniciales ("Exequiales del Caribe" da "EC"). */
    private static final java.util.Set<String> CONNECTORS = java.util.Set.of("la", "las", "el", "los", "de", "del", "y", "e");

    static String initials(String name) {
        StringBuilder out = new StringBuilder();
        for (String word : trim(name).split("\\s+")) {
            if (!CONNECTORS.contains(word.toLowerCase(Locale.ROOT)) && !word.isEmpty() && Character.isLetter(word.charAt(0))) {
                out.append(Character.toUpperCase(word.charAt(0)));
            }
            if (out.length() == 2) {
                break;
            }
        }
        return out.isEmpty() ? "?" : out.toString();
    }

    private static String fullName(Deceased d) {
        if (d == null) {
            return "";
        }
        return (trim(d.getName()) + " " + trim(d.getLastname())).trim();
    }

    private static String trim(String text) {
        return text == null ? "" : text.trim();
    }

    /** Red de funerarias aliadas: solo el Administrador Maestro. */
    AppUser requireMaster() {
        boolean allowed = sessionContext.getRoleName().map(AppModule.RED_ALIADAS::isAllowedFor).orElse(false);
        AppUser actor = sessionContext.getCurrentUser().orElseThrow(UnauthorizedActionException::new);
        if (!allowed || actor.getId() == null) {
            throw new UnauthorizedActionException();
        }
        return actor;
    }
}
