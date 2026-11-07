package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.ResourceNotFoundException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.CremationProvider;
import com.example.funerariamc.model.CremationPurchase;
import com.example.funerariamc.model.Employee;
import com.example.funerariamc.model.FinancialTransaction;
import com.example.funerariamc.model.enums.CremationPurchaseStatus;
import com.example.funerariamc.model.enums.TransactionStatus;
import com.example.funerariamc.model.enums.TransactionType;
import com.example.funerariamc.persistence.CremationProviderRepository;
import com.example.funerariamc.persistence.CremationProviderRepository.StockRow;
import com.example.funerariamc.persistence.CremationPurchaseRepository;
import com.example.funerariamc.persistence.FinancialTransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Cremaciones prepagadas por proveedor (decisión de Camilo del 06/11). La funeraria compra
 * cremaciones en lote a un proveedor y las va usando: un cupo está disponible cuando la compra
 * llegó, queda separado cuando el difunto entra a un lote y se resta cuando ya se cremó.
 * Flujo de la compra: solicitada (espera confirmación; a futuro la crea n8n al llegar al mínimo)
 * → confirmada (se registra el gasto en Finanzas) → recibida (los cupos quedan disponibles), o
 * anulada (el gasto queda anulado, nunca se borra). Lo usan el Administrador de Cementerio y el
 * Administrador Maestro. La sección de la pantalla sale de dos consultas.
 */
@Service
public class CremationStockService {

    /** Largo de cremation_provider.code (igual a cremation_batch.furnace_code). */
    public static final int MAX_CODE = 15;
    /** Largo de cremation_provider.name. */
    public static final int MAX_NAME = 100;
    /** Tope de NUMERIC(10,2) para el precio unitario. */
    public static final BigDecimal MAX_PRICE = new BigDecimal("99999999.99");

    /** Saldo de un proveedor. Disponibles = recibidos - separados - cremados. */
    public record ProviderStock(Long id, String code, String name, int minimumAvailable, boolean active,
                                long received, long reserved, long used, long incoming, long requests) {
        public long available() {
            return Math.max(0, received - reserved - used);
        }

        /** Llegó al mínimo: hay que reponer cupos. */
        public boolean atMinimum() {
            return active && available() <= minimumAvailable;
        }

        public String label() {
            return code + " · " + name;
        }
    }

    /** Una compra para la lista de la pantalla. */
    public record Purchase(Long id, String code, Long providerId, String providerCode, String providerName,
                           int quantity, BigDecimal unitPrice, CremationPurchaseStatus status,
                           LocalDateTime requestedAt, LocalDateTime orderedAt, LocalDateTime receivedAt,
                           String registeredBy) {
        public BigDecimal total() {
            return unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }

    /** Todo lo que muestra la sección de cupos prepagados. */
    public record Stock(List<ProviderStock> providers, List<Purchase> purchases) {
        public long requests() {
            return purchases.stream().filter(p -> p.status() == CremationPurchaseStatus.REQUESTED).count();
        }
    }

    private final CremationProviderRepository providerRepository;
    private final CremationPurchaseRepository purchaseRepository;
    private final FinancialTransactionRepository transactionRepository;
    private final AccessLogService accessLogService;
    private final SessionContext sessionContext;
    private final Clock clock;

    @Autowired
    public CremationStockService(CremationProviderRepository providerRepository,
                                 CremationPurchaseRepository purchaseRepository,
                                 FinancialTransactionRepository transactionRepository,
                                 AccessLogService accessLogService, SessionContext sessionContext) {
        this(providerRepository, purchaseRepository, transactionRepository, accessLogService, sessionContext,
                Clock.system(AccessLogService.APP_ZONE));
    }

    /** Las pruebas fijan la fecha y la hora con un Clock. */
    CremationStockService(CremationProviderRepository providerRepository,
                          CremationPurchaseRepository purchaseRepository,
                          FinancialTransactionRepository transactionRepository,
                          AccessLogService accessLogService, SessionContext sessionContext, Clock clock) {
        this.providerRepository = providerRepository;
        this.purchaseRepository = purchaseRepository;
        this.transactionRepository = transactionRepository;
        this.accessLogService = accessLogService;
        this.sessionContext = sessionContext;
        this.clock = clock;
    }

    // ---------- consulta ----------

    /** Saldo de cada proveedor y la lista de compras (dos consultas). */
    @Transactional(readOnly = true)
    public Stock stock() {
        requireAccess();
        List<ProviderStock> providers = providerRepository.findStock(CremationPurchaseStatus.RECEIVED,
                CremationPurchaseStatus.ORDERED, CremationPurchaseStatus.REQUESTED).stream()
                .map(CremationStockService::toStock)
                .toList();
        List<Purchase> purchases = purchaseRepository.findAllWithProvider().stream()
                .map(CremationStockService::toPurchase)
                .toList();
        return new Stock(providers, purchases);
    }

    static ProviderStock toStock(StockRow r) {
        return new ProviderStock(r.getId(), r.getCode(), r.getName(),
                r.getMinimumAvailable() == null ? 0 : r.getMinimumAvailable(), Boolean.TRUE.equals(r.getActive()),
                n(r.getReceived()), n(r.getReserved()), n(r.getUsed()), n(r.getIncoming()), n(r.getRequests()));
    }

    static Purchase toPurchase(CremationPurchase p) {
        Employee e = p.getEmployee();
        return new Purchase(p.getId(), purchaseCode(p.getId()), p.getProvider().getId(), p.getProvider().getCode(),
                p.getProvider().getName(), p.getQuantity(), p.getUnitPrice(), p.getStatus(), p.getRequestedAt(),
                p.getOrderedAt(), p.getReceivedAt(),
                e == null ? null : (e.getName() + " " + e.getLastname()).trim());
    }

    /** Código visible de la compra: CP-0003. */
    public static String purchaseCode(Long id) {
        return id == null ? "CP-????" : String.format("CP-%04d", id);
    }

    // ---------- proveedores ----------

    /** Crea o edita un proveedor. El código se guarda en mayúsculas y no cambia después de creado. */
    @Transactional
    public CremationProvider saveProvider(CremationProviderData data) {
        Long actorId = requireAccess();
        if (data == null) {
            throw new InvalidDataException("Ingrese los datos del proveedor.");
        }
        String name = clean(data.name());
        if (name == null) {
            throw new InvalidDataException("Ingrese el nombre del proveedor.");
        }
        if (name.length() > MAX_NAME) {
            throw new InvalidDataException("El nombre admite máximo " + MAX_NAME + " caracteres.");
        }
        if (data.minimumAvailable() == null || data.minimumAvailable() < 0) {
            throw new InvalidDataException("El mínimo de cupos debe ser 0 o más.");
        }
        CremationProvider provider;
        if (data.id() == null) {
            String code = clean(data.code());
            if (code == null) {
                throw new InvalidDataException("Ingrese el código del proveedor.");
            }
            code = code.toUpperCase(Locale.ROOT);
            if (code.length() > MAX_CODE) {
                throw new InvalidDataException("El código admite máximo " + MAX_CODE + " caracteres.");
            }
            if (providerRepository.existsByCode(code)) {
                throw new InvalidDataException("Ya existe un proveedor con el código " + code + ".");
            }
            provider = new CremationProvider();
            provider.setCode(code);
        } else {
            provider = providerRepository.findById(data.id())
                    .orElseThrow(() -> new ResourceNotFoundException("Proveedor de cremación", data.id()));
        }
        provider.setName(name);
        provider.setMinimumAvailable(data.minimumAvailable());
        provider.setActive(data.active());
        CremationProvider saved = providerRepository.save(provider);
        accessLogService.record(actorId, AccessLogService.CREMATION_PROVIDER_SAVED + saved.getCode());
        return saved;
    }

    // ---------- compras ----------

    /** Registra la solicitud de compra; queda esperando confirmación. */
    @Transactional
    public Purchase requestPurchase(CremationPurchaseData data) {
        Long actorId = requireAccess();
        if (data == null || data.providerId() == null) {
            throw new InvalidDataException("Seleccione el proveedor.");
        }
        if (data.quantity() == null || data.quantity() <= 0) {
            throw new InvalidDataException("La cantidad de cremaciones debe ser mayor que cero.");
        }
        if (data.unitPrice() == null || data.unitPrice().signum() <= 0) {
            throw new InvalidDataException("El precio por cremación debe ser mayor que cero.");
        }
        if (data.unitPrice().scale() > 2 || data.unitPrice().compareTo(MAX_PRICE) > 0) {
            throw new InvalidDataException("Revise el precio por cremación.");
        }
        CremationProvider provider = providerRepository.findById(data.providerId())
                .orElseThrow(() -> new ResourceNotFoundException("Proveedor de cremación", data.providerId()));
        if (!Boolean.TRUE.equals(provider.getActive())) {
            throw new InvalidDataException("El proveedor " + provider.getCode() + " está inactivo.");
        }
        CremationPurchase purchase = new CremationPurchase();
        purchase.setProvider(provider);
        purchase.setQuantity(data.quantity());
        purchase.setUnitPrice(data.unitPrice());
        purchase.setStatus(CremationPurchaseStatus.REQUESTED);
        purchase.setRequestedAt(now());
        purchase.setEmployee(currentEmployee());
        CremationPurchase saved = purchaseRepository.save(purchase);
        accessLogService.record(actorId, AccessLogService.CREMATION_PURCHASE_REQUESTED + purchaseCode(saved.getId())
                + " " + provider.getCode() + " x" + saved.getQuantity());
        return toPurchase(saved);
    }

    /** Confirma la compra (ya se pagó): registra el gasto en Finanzas y queda esperando los cupos. */
    @Transactional
    public void confirm(Long purchaseId) {
        Long actorId = requireAccess();
        CremationPurchase purchase = purchase(purchaseId);
        if (purchase.getStatus() != CremationPurchaseStatus.REQUESTED) {
            throw new InvalidDataException("Solo se confirma una compra solicitada.");
        }
        String code = purchaseCode(purchase.getId());
        FinancialTransaction expense = new FinancialTransaction();
        expense.setTransactionType(TransactionType.EXPENSE);
        expense.setAmount(purchase.getUnitPrice().multiply(BigDecimal.valueOf(purchase.getQuantity())));
        expense.setDescription(limit("Compra de " + purchase.getQuantity() + " cremaciones prepagadas " + code
                + " · " + purchase.getProvider().getCode() + " · " + purchase.getProvider().getName(), 150));
        expense.setStatus(TransactionStatus.ACTIVE);
        expense.setEmployee(currentEmployee());
        purchase.setFinancialTransaction(transactionRepository.save(expense));
        purchase.setStatus(CremationPurchaseStatus.ORDERED);
        purchase.setOrderedAt(now());
        purchaseRepository.save(purchase);
        accessLogService.record(actorId, AccessLogService.CREMATION_PURCHASE_CONFIRMED + code);
    }

    /** Llegaron los cupos de la compra: quedan disponibles para separar. */
    @Transactional
    public void receive(Long purchaseId) {
        Long actorId = requireAccess();
        CremationPurchase purchase = purchase(purchaseId);
        if (purchase.getStatus() != CremationPurchaseStatus.ORDERED) {
            throw new InvalidDataException("Solo se recibe una compra confirmada.");
        }
        purchase.setStatus(CremationPurchaseStatus.RECEIVED);
        purchase.setReceivedAt(now());
        purchaseRepository.save(purchase);
        accessLogService.record(actorId, AccessLogService.CREMATION_PURCHASE_RECEIVED + purchaseCode(purchaseId));
    }

    /** Anula una compra solicitada o confirmada; si tenía gasto, el gasto queda anulado (no se borra). */
    @Transactional
    public void cancel(Long purchaseId) {
        Long actorId = requireAccess();
        CremationPurchase purchase = purchase(purchaseId);
        if (purchase.getStatus() == CremationPurchaseStatus.RECEIVED) {
            throw new InvalidDataException("Una compra recibida ya tiene sus cupos disponibles y no se puede anular.");
        }
        if (purchase.getStatus() == CremationPurchaseStatus.CANCELLED) {
            throw new InvalidDataException("La compra ya está anulada.");
        }
        FinancialTransaction expense = purchase.getFinancialTransaction();
        if (expense != null) {
            expense.setStatus(TransactionStatus.VOIDED);
            transactionRepository.save(expense);
        }
        purchase.setStatus(CremationPurchaseStatus.CANCELLED);
        purchaseRepository.save(purchase);
        accessLogService.record(actorId, AccessLogService.CREMATION_PURCHASE_CANCELLED + purchaseCode(purchaseId));
    }

    // ---------- apoyo ----------

    private CremationPurchase purchase(Long id) {
        if (id == null) {
            throw new InvalidDataException("Seleccione la compra.");
        }
        return purchaseRepository.findDetailById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Compra de cremaciones", id));
    }

    private Employee currentEmployee() {
        return sessionContext.getCurrentUser().map(AppUser::getEmployee).orElse(null);
    }

    private static long n(Long value) {
        return value == null ? 0 : value;
    }

    private static String clean(String text) {
        if (text == null) {
            return null;
        }
        String result = text.trim().replaceAll("\\s+", " ");
        return result.isEmpty() ? null : result;
    }

    private static String limit(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private Long requireAccess() {
        boolean allowed = sessionContext.getRoleName().map(AppModule.CREMACIONES::isAllowedFor).orElse(false);
        if (!allowed) {
            throw new UnauthorizedActionException();
        }
        return sessionContext.getCurrentUser().map(AppUser::getId).orElseThrow(UnauthorizedActionException::new);
    }
}
