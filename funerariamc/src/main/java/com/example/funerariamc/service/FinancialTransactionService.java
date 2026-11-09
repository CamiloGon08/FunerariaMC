package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.ResourceNotFoundException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Employee;
import com.example.funerariamc.model.FinanceAccount;
import com.example.funerariamc.model.FinancialTransaction;
import com.example.funerariamc.model.Sale;
import com.example.funerariamc.model.enums.SaleStatus;
import com.example.funerariamc.model.enums.TransactionStatus;
import com.example.funerariamc.model.enums.TransactionType;
import com.example.funerariamc.persistence.AlliedPaymentRepository;
import com.example.funerariamc.persistence.FinanceAccountRepository;
import com.example.funerariamc.persistence.FinancialTransactionRepository;
import com.example.funerariamc.persistence.SaleRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Movimientos de Finanzas: ingresos y egresos de un período, registro manual y anulación.
 * Un movimiento nunca se borra: se anula y deja de contar en los totales.
 * Las cuotas de planes, las compras de cremaciones y los pagos a funerarias aliadas se anulan
 * desde su propio módulo, porque allí se deshace también lo que esos módulos guardaron.
 */
@Service
public class FinancialTransactionService {

    /** Texto con el que empieza la descripción de una comisión: "Comisión 2026-10 · ...". */
    public static final String COMMISSION_PREFIX = "Comisión ";

    /** De dónde viene un movimiento y dónde se anula. */
    public enum Origin {
        SALE_PAYMENT("Abono a venta", null),
        PLAN_INSTALLMENT("Cuota de plan", "Planes exequiales"),
        CREMATION_PURCHASE("Compra de cremaciones", "Cremaciones"),
        ALLIED_PAYMENT("Pago a funeraria aliada", "Red de funerarias aliadas"),
        COMMISSION("Comisión", null),
        MANUAL("Registro manual", null);

        private final String label;
        private final String ownerModule;

        Origin(String label, String ownerModule) {
            this.label = label;
            this.ownerModule = ownerModule;
        }

        public String getLabel() {
            return label;
        }

        /** Módulo donde se anula, o null si se anula aquí. */
        public String getOwnerModule() {
            return ownerModule;
        }
    }

    /** Una fila de la tabla de movimientos (fecha en hora de Colombia). */
    public record MovementRow(Long id, String code, LocalDateTime date, TransactionType type, Origin origin,
                              String description, String saleNumber, String registeredBy, BigDecimal amount,
                              TransactionStatus status, String account) {

        public boolean voided() {
            return status == TransactionStatus.VOIDED;
        }

        /** Se puede anular aquí: está vigente y no pertenece a otro módulo. */
        public boolean voidableHere() {
            return !voided() && origin.getOwnerModule() == null;
        }
    }

    /** Nombre que se muestra cuando un egreso no tiene cuenta. */
    public static final String NO_ACCOUNT = "Sin cuenta";

    /** Cuenta de egresos para la lista del formulario. */
    public record AccountOption(Long id, String name) {
    }

    /** Venta con saldo, para asociarle un ingreso. */
    public record SaleOption(Long saleId, String number, String label, BigDecimal balance) {
    }

    /** Ids de los movimientos que pertenecen a otros módulos, para clasificar sin consultar uno por uno. */
    record Owners(Set<Long> planPayments, Set<Long> cremationPurchases, Set<Long> alliedPayments) {
    }

    private final FinancialTransactionRepository transactionRepository;
    private final SaleRepository saleRepository;
    private final AlliedPaymentRepository alliedPaymentRepository;
    private final FinanceAccountRepository accountRepository;
    private final ContractService contractService;
    private final AccessLogService accessLogService;
    private final SessionContext sessionContext;

    public FinancialTransactionService(FinancialTransactionRepository transactionRepository,
                                       SaleRepository saleRepository, AlliedPaymentRepository alliedPaymentRepository,
                                       FinanceAccountRepository accountRepository, ContractService contractService, AccessLogService accessLogService,
                                       SessionContext sessionContext) {
        this.transactionRepository = transactionRepository;
        this.saleRepository = saleRepository;
        this.alliedPaymentRepository = alliedPaymentRepository;
        this.accountRepository = accountRepository;
        this.contractService = contractService;
        this.accessLogService = accessLogService;
        this.sessionContext = sessionContext;
    }

    /** Movimientos del período, vigentes y anulados, del más reciente al más antiguo. */
    @Transactional(readOnly = true)
    public List<MovementRow> movements(FinancePeriod period) {
        requireFinance();
        List<FinancialTransaction> found = transactionRepository.findInRange(period.dbFrom(), period.dbTo());
        Owners owners = owners(period);
        return found.stream().map(t -> row(t, owners)).toList();
    }

    Owners owners(FinancePeriod period) {
        return new Owners(
                new HashSet<>(transactionRepository.findPlanPaymentIds(period.dbFrom(), period.dbTo())),
                new HashSet<>(transactionRepository.findCremationPurchaseIds(period.dbFrom(), period.dbTo())),
                new HashSet<>(alliedPaymentIds(period)));
    }

    /** Egresos que son pagos a funerarias aliadas (se registran al marcar el pago como pagado). */
    List<Long> alliedPaymentIds(FinancePeriod period) {
        return alliedPaymentRepository.findTransactionIds(period.dbFrom(), period.dbTo());
    }

    static MovementRow row(FinancialTransaction t, Owners owners) {
        Origin origin = classify(t, owners);
        Sale sale = t.getRelatedSale();
        Employee employee = t.getEmployee();
        return new MovementRow(t.getId(), code(t.getId()), AccessLogService.toAppTime(t.getTransactionDate()),
                t.getTransactionType(), origin, t.getDescription(),
                sale == null ? null : "%04d".formatted(sale.getId()),
                employee == null ? null : employee.getName() + " " + employee.getLastname(),
                t.getAmount(), t.getStatus() == null ? TransactionStatus.ACTIVE : t.getStatus(),
                t.getFinanceAccount() == null ? null : t.getFinanceAccount().getName());
    }

    static Origin classify(FinancialTransaction t, Owners owners) {
        if (owners.planPayments().contains(t.getId())) {
            return Origin.PLAN_INSTALLMENT;
        }
        if (owners.cremationPurchases().contains(t.getId())) {
            return Origin.CREMATION_PURCHASE;
        }
        if (owners.alliedPayments().contains(t.getId())) {
            return Origin.ALLIED_PAYMENT;
        }
        if (t.getRelatedSale() != null) {
            return Origin.SALE_PAYMENT;
        }
        if (t.getTransactionType() == TransactionType.EXPENSE && t.getDescription() != null
                && t.getDescription().startsWith(COMMISSION_PREFIX)) {
            return Origin.COMMISSION;
        }
        return Origin.MANUAL;
    }

    /** "MV-0012": código corto del movimiento. */
    public static String code(Long id) {
        return FinancialTransaction.code(id);
    }

    /** Cuentas activas a las que se puede cargar un egreso, ordenadas por nombre. */
    @Transactional(readOnly = true)
    public List<AccountOption> accounts() {
        requireFinance();
        return accountRepository.findByActiveTrueOrderByName().stream()
                .map(a -> new AccountOption(a.getId(), a.getName()))
                .toList();
    }

    /** Ventas vigentes con saldo pendiente, de la más reciente a la más antigua. */
    @Transactional(readOnly = true)
    public List<SaleOption> salesWithBalance() {
        requireFinance();
        return saleRepository.findRows(TransactionType.INCOME, TransactionStatus.ACTIVE, PageRequest.of(0, 500))
                .stream()
                .map(ContractService::row)
                .filter(r -> r.balance() != null && r.balance().signum() > 0
                        && r.state() != ContractService.PaymentState.VOIDED)
                .map(r -> new SaleOption(r.saleId(), r.number(), r.number() + " · " + r.title(), r.balance()))
                .toList();
    }

    /**
     * Registra un ingreso o un egreso con la fecha y hora actuales. Si es un ingreso de una venta,
     * se registra como abono de esa venta (no puede superar su saldo).
     */
    @Transactional
    public Long register(MovementData data) {
        AppUser actor = requireFinance();
        if (data == null || data.type() == null) {
            throw new InvalidDataException("Elija si es un ingreso o un egreso.");
        }
        BigDecimal amount = checkedAmount(data.amount());
        String description = data.description() == null ? "" : data.description().trim();
        if (description.isEmpty()) {
            throw new InvalidDataException("Escriba la descripción del movimiento.");
        }
        if (description.length() > 150) {
            throw new InvalidDataException("La descripción admite máximo 150 caracteres.");
        }
        FinanceAccount account = null;
        if (data.accountId() != null) {
            if (data.type() != TransactionType.EXPENSE) {
                throw new InvalidDataException("Solo un egreso se carga a una cuenta.");
            }
            account = accountRepository.findById(data.accountId())
                    .filter(a -> Boolean.TRUE.equals(a.getActive()))
                    .orElseThrow(() -> new InvalidDataException("La cuenta elegida ya no está disponible."));
        }
        if (data.saleId() != null) {
            if (data.type() != TransactionType.INCOME) {
                throw new InvalidDataException("Solo un ingreso se puede asociar a una venta.");
            }
            contractService.registerPayment(new SalePaymentData(data.saleId(), amount, description));
            return null;
        }
        FinancialTransaction movement = new FinancialTransaction();
        movement.setTransactionType(data.type());
        movement.setAmount(amount);
        movement.setDescription(description);
        movement.setFinanceAccount(account);
        movement.setStatus(TransactionStatus.ACTIVE);
        movement.setEmployee(actor.getEmployee());
        FinancialTransaction saved = transactionRepository.save(movement);
        accessLogService.record(actor.getId(), AccessLogService.FINANCIAL_TRANSACTION_REGISTERED
                + code(saved.getId()) + " " + data.type().name() + " " + amount.toPlainString());
        return saved.getId();
    }

    /** Anula un movimiento propio de Finanzas (manual, abono a venta o comisión). */
    @Transactional
    public void voidMovement(Long id) {
        AppUser actor = requireFinance();
        FinancialTransaction movement = transactionRepository.findDetailById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Movimiento", id));
        if (movement.getStatus() == TransactionStatus.VOIDED) {
            throw new InvalidDataException("El movimiento ya está anulado.");
        }
        String owner = ownerModule(movement);
        if (owner != null) {
            throw new InvalidDataException("Este movimiento lo registró el módulo " + owner
                    + ". Anúlelo desde allí para que también se deshaga lo que ese módulo guardó.");
        }
        if (movement.getRelatedSale() != null && movement.getRelatedSale().getStatus() == SaleStatus.VOIDED) {
            throw new InvalidDataException("La venta de este abono está anulada.");
        }
        movement.setStatus(TransactionStatus.VOIDED);
        transactionRepository.save(movement);
        accessLogService.record(actor.getId(), AccessLogService.FINANCIAL_TRANSACTION_VOIDED + code(id));
    }

    /** Módulo que registró el movimiento y debe anularlo, o null si se anula en Finanzas. */
    String ownerModule(FinancialTransaction movement) {
        if (transactionRepository.isPlanPayment(movement.getId())) {
            return Origin.PLAN_INSTALLMENT.getOwnerModule();
        }
        if (transactionRepository.isCremationPurchase(movement.getId())) {
            return Origin.CREMATION_PURCHASE.getOwnerModule();
        }
        if (alliedPaymentRepository.existsByFinancialTransactionId(movement.getId())) {
            return Origin.ALLIED_PAYMENT.getOwnerModule();
        }
        return null;
    }

    static BigDecimal checkedAmount(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new InvalidDataException("El monto debe ser mayor que cero.");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** Finanzas: Administrador Maestro y Secretaría. */
    AppUser requireFinance() {
        boolean allowed = sessionContext.getRoleName().map(AppModule.FINANZAS::isAllowedFor).orElse(false);
        AppUser actor = sessionContext.getCurrentUser().orElseThrow(UnauthorizedActionException::new);
        if (!allowed || actor.getId() == null) {
            throw new UnauthorizedActionException();
        }
        return actor;
    }
}
