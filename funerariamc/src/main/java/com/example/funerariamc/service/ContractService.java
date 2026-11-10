package com.example.funerariamc.service;

import com.example.funerariamc.exception.InstallmentExceedsBalanceException;
import com.example.funerariamc.exception.InactiveEmployeeException;
import com.example.funerariamc.exception.InsufficientStockException;
import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.ResourceNotFoundException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.Client;
import com.example.funerariamc.model.Deceased;
import com.example.funerariamc.model.Employee;
import com.example.funerariamc.model.FinancialTransaction;
import com.example.funerariamc.model.FuneralPlan;
import com.example.funerariamc.model.FuneralSalesperson;
import com.example.funerariamc.model.InventoryItem;
import com.example.funerariamc.model.Sale;
import com.example.funerariamc.model.SaleDetail;
import com.example.funerariamc.model.ServiceContract;
import com.example.funerariamc.model.Thanatopractor;
import com.example.funerariamc.model.WakeRoom;
import com.example.funerariamc.model.enums.ClientType;
import com.example.funerariamc.model.enums.ContractStatus;
import com.example.funerariamc.model.enums.EmployeeStatus;
import com.example.funerariamc.model.enums.PlanStatus;
import com.example.funerariamc.model.enums.SaleStatus;
import com.example.funerariamc.model.enums.SaleType;
import com.example.funerariamc.model.enums.TransactionStatus;
import com.example.funerariamc.model.enums.TransactionType;
import com.example.funerariamc.persistence.FinancialTransactionRepository;
import com.example.funerariamc.persistence.InventoryItemRepository;
import com.example.funerariamc.persistence.SaleDetailRepository;
import com.example.funerariamc.persistence.SaleRepository;
import com.example.funerariamc.persistence.ServiceContractRepository;
import com.example.funerariamc.persistence.StaffOptionRepository;
import com.example.funerariamc.persistence.WakeRoomRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Módulo Contratos y ventas: tabla filtrable por tipo y estado de pago,
 * indicadores, registro del contrato y su venta con detalle por concepto,
 * abonos con saldo, anulación (se anula, no se borra) y registro del fin de la
 * preparación del cuerpo.
 * Lo usan el Vendedor, la Secretaría y el Administrador Maestro.
 * La pantalla recibe records ya armados: nunca toca relaciones LAZY.
 */
@Service
public class ContractService {

    /** Filas que lee la tabla (las más recientes); los indicadores cuentan todas en la base. */
    public static final int MAX_ROWS = 1000;
    /** Resultados de los buscadores de los diálogos. */
    public static final int SHORT_LIST = 20;
    /** Tope de las columnas numeric(12,2) de sale y financial_transaction. */
    public static final BigDecimal MAX_TOTAL = new BigDecimal("9999999999.99");
    /** Tope de sale_detail.unit_price, numeric(10,2). */
    public static final BigDecimal MAX_UNIT = new BigDecimal("99999999.99");

    static final String BASE_LINE = "Cuota base del servicio";
    static final String PENSION_LINE = "Servicio cubierto por pensión";
    static final String COFFIN_EXCESS_LINE = "Excedente por gama de ataúd";
    static final String COFFIN_LINE = "Ataúd";
    static final String BUS_LINE = "Buses adicionales";
    static final String BENEFICIARY_LINE = "Recargo por beneficiarios";

    /** Tipo de venta del formulario: el contrato puede ser con plan o por pensión. */
    public enum SaleKind {
        PLAN("Contrato · plan", SaleType.CONTRACT),
        PENSION("Contrato · pensión", SaleType.CONTRACT),
        PRIVATE("Particular", SaleType.PRIVATE),
        PRODUCT("Venta", SaleType.PRODUCT);

        private final String label;
        private final SaleType saleType;

        SaleKind(String label, SaleType saleType) {
            this.label = label;
            this.saleType = saleType;
        }

        public String getLabel() {
            return label;
        }

        public SaleType getSaleType() {
            return saleType;
        }

        /** Los servicios funerarios llevan difunto, contrato y vendedor; la venta de productos no. */
        public boolean isService() {
            return this != PRODUCT;
        }
    }

    /** Estado de pago de una fila (filtro "Pago"). */
    public enum PaymentState {
        WITH_BALANCE("Con saldo"),
        PAID("Pagado"),
        VOIDED("Anulada");

        private final String label;

        PaymentState(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** Opción de un buscador o lista del diálogo. */
    public record Option(Long id, String label, String detail) {
    }

    /** Artículo del inventario para el diálogo, con su existencia y precio. */
    public record ItemOption(Long id, String name, String category, int stock, BigDecimal price) {
    }

    /** Una fila de "Contratos registrados". number es el N.° (0148); title y subtitle son la columna DIFUNTO / CLIENTE. */
    public record SaleRow(Long saleId, String number, SaleKind kind, PaymentState state, LocalDateTime saleDate,
                          BigDecimal total, BigDecimal paid, BigDecimal balance, Long contractId,
                          String contractNumber, String title, String subtitle, String searchText) {
    }

    /** Indicadores de la banda verde. */
    public record Kpis(long records, long contracts, long privates, long products, BigDecimal soldThisMonth,
                       BigDecimal soldLastMonth, String monthName, String lastMonthName, BigDecimal receivable,
                       long onCredit, long voided) {

        /** Variación del mes frente al anterior en porcentaje, o null si el anterior no tuvo ventas. */
        public Double changePercent() {
            if (soldLastMonth == null || soldLastMonth.signum() == 0) {
                return null;
            }
            return soldThisMonth.subtract(soldLastMonth).multiply(BigDecimal.valueOf(100))
                    .divide(soldLastMonth, 1, RoundingMode.HALF_UP).doubleValue();
        }
    }

    /** Tabla e indicadores de la pantalla. */
    public record Overview(Kpis kpis, List<SaleRow> rows) {
    }

    /** Línea (concepto) de una venta. */
    public record Line(String description, int quantity, BigDecimal unitPrice, BigDecimal subtotal,
                       Long itemId) {
    }

    /** Lo que se cobraría con los datos del formulario, antes de guardar. */
    public record Quote(List<Line> lines, BigDecimal total) {
    }

    /** Abono de la venta; voided marca los anulados. */
    public record Payment(Long id, LocalDateTime date, BigDecimal amount, String description, String employee,
                          boolean voided) {
    }

    /** Contrato de servicio de la venta (null en una venta de productos). */
    public record ContractInfo(Long id, String number, LocalDate contractDate, ContractStatus status,
                               String deceasedName, String planLabel, String roomName, LocalDateTime wakeStart,
                               LocalDateTime wakeEnd, String thanatopractor, LocalDateTime preparationCompletedAt) {
    }

    /** Panel "Ver" de una venta. */
    public record SaleView(SaleRow row, String clientName, String clientDocument, String clientPhone,
                           String salesperson, ContractInfo contract, List<Line> lines, List<Payment> payments,
                           boolean canRegisterPreparation) {
    }

    private final SaleRepository saleRepository;
    private final SaleDetailRepository detailRepository;
    private final FinancialTransactionRepository transactionRepository;
    private final ServiceContractRepository contractRepository;
    private final InventoryItemRepository itemRepository;
    private final StaffOptionRepository staffRepository;
    private final WakeRoomRepository roomRepository;
    private final FuneralPlanService planService;
    private final SaleParameters parameters;
    private final AccessLogService accessLogService;
    private final SessionContext sessionContext;
    private final Clock clock;

    @Autowired
    public ContractService(SaleRepository saleRepository, SaleDetailRepository detailRepository,
                           FinancialTransactionRepository transactionRepository,
                           ServiceContractRepository contractRepository, InventoryItemRepository itemRepository,
                           StaffOptionRepository staffRepository, WakeRoomRepository roomRepository,
                           WakeRoomService wakeRoomService, FuneralPlanService planService,
                           SaleParameters parameters, AccessLogService accessLogService,
                           SessionContext sessionContext) {
        this(saleRepository, detailRepository, transactionRepository, contractRepository, itemRepository,
                staffRepository, roomRepository, wakeRoomService, planService, parameters, accessLogService,
                sessionContext, Clock.system(AccessLogService.APP_ZONE));
    }

    /** Las pruebas fijan la fecha con un Clock. */
    ContractService(SaleRepository saleRepository, SaleDetailRepository detailRepository,
                    FinancialTransactionRepository transactionRepository,
                    ServiceContractRepository contractRepository, InventoryItemRepository itemRepository,
                    StaffOptionRepository staffRepository, WakeRoomRepository roomRepository,
                    WakeRoomService wakeRoomService, FuneralPlanService planService, SaleParameters parameters,
                    AccessLogService accessLogService, SessionContext sessionContext, Clock clock) {
        this.saleRepository = saleRepository;
        this.detailRepository = detailRepository;
        this.transactionRepository = transactionRepository;
        this.contractRepository = contractRepository;
        this.itemRepository = itemRepository;
        this.staffRepository = staffRepository;
        this.roomRepository = roomRepository;
        this.planService = planService;
        this.parameters = parameters;
        this.accessLogService = accessLogService;
        this.sessionContext = sessionContext;
        this.clock = clock;
    }

    // ---------- pantalla ----------

    /** Indicadores y filas en una transacción de lectura: dos consultas. */
    @Transactional(readOnly = true)
    public Overview overview() {
        requireAccess();
        YearMonth month = YearMonth.now(clock);
        SaleRepository.SaleKpis k = saleRepository.findKpis(toDb(month.minusMonths(1).atDay(1).atStartOfDay()),
                toDb(month.atDay(1).atStartOfDay()), toDb(month.plusMonths(1).atDay(1).atStartOfDay()));
        Kpis kpis = new Kpis(k.getRecords(), k.getContracts(), k.getPrivates(), k.getProducts(),
                orZero(k.getSoldThisMonth()), orZero(k.getSoldLastMonth()), monthName(month),
                monthName(month.minusMonths(1)), orZero(k.getReceivable()), k.getOnCredit(), k.getVoided());
        List<SaleRow> rows = saleRepository.findRows(TransactionType.INCOME, TransactionStatus.ACTIVE,
                        PageRequest.of(0, MAX_ROWS)).stream()
                .map(ContractService::row)
                .toList();
        return new Overview(kpis, rows);
    }

    /** Tipo de venta, estado de pago y texto se combinan. type null = todos. */
    public static List<SaleRow> filter(List<SaleRow> rows, SaleType type, PaymentState payment, String text) {
        String needle = normalize(text);
        return rows.stream()
                .filter(r -> type == null || r.kind().getSaleType() == type)
                .filter(r -> payment == null || r.state() == payment)
                .filter(r -> needle.isEmpty() || r.searchText().contains(needle))
                .toList();
    }

    static SaleRow row(SaleRepository.SaleRow r) {
        BigDecimal total = orZero(r.getTotal());
        BigDecimal paid = orZero(r.getPaid());
        BigDecimal balance = total.subtract(paid).max(BigDecimal.ZERO);
        SaleKind kind = kindOf(r.getSaleType(), r.getPlanId());
        PaymentState state = r.getStatus() == SaleStatus.VOIDED ? PaymentState.VOIDED
                : balance.signum() > 0 ? PaymentState.WITH_BALANCE : PaymentState.PAID;
        String client = fullName(r.getClientName(), r.getClientLastname());
        String deceased = fullName(r.getDeceasedName(), r.getDeceasedLastname());
        String title = kind == SaleKind.PRODUCT || deceased.isEmpty() ? "Venta sin servicio" : deceased;
        String subtitle = switch (kind) {
            case PLAN -> "Plan " + FuneralPlanService.code(r.getPlanId()) + " · " + client;
            case PENSION -> "Pensión · " + client;
            default -> "Cliente: " + client;
        };
        String number = "%04d".formatted(r.getSaleId());
        String search = normalize(String.join(" ", number, title, subtitle,
                r.getContractNumber() == null ? "" : r.getContractNumber()));
        return new SaleRow(r.getSaleId(), number, kind, state, r.getSaleDate() == null ? null
                : AccessLogService.toAppTime(r.getSaleDate()), total, paid, balance, r.getContractId(),
                r.getContractNumber(), title, subtitle, search);
    }

    /** Un contrato sin plan es por pensión; con plan, contrato con plan. */
    static SaleKind kindOf(SaleType type, Long planId) {
        if (type == SaleType.PRODUCT) {
            return SaleKind.PRODUCT;
        }
        if (type == SaleType.PRIVATE) {
            return SaleKind.PRIVATE;
        }
        return planId == null ? SaleKind.PENSION : SaleKind.PLAN;
    }

    /** Panel "Ver": la venta, su contrato, sus conceptos y sus abonos. */
    @Transactional(readOnly = true)
    public SaleView detail(Long saleId) {
        requireAccess();
        Sale sale = saleRepository.findDetailById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("Venta", saleId));
        SaleRow row = saleRepository.findRow(saleId, TransactionType.INCOME, TransactionStatus.ACTIVE)
                .map(ContractService::row)
                .orElseThrow(() -> new ResourceNotFoundException("Venta", saleId));
        Client client = sale.getClient();
        ContractInfo contract = null;
        if (sale.getServiceContract() != null) {
            ServiceContract c = contractRepository.findDetailById(sale.getServiceContract().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Contrato", sale.getServiceContract().getId()));
            contract = new ContractInfo(c.getId(), c.getContractNumber(), c.getContractDate(), c.getStatus(),
                    fullName(c.getDeceased().getName(), c.getDeceased().getLastname()),
                    c.getFuneralPlan() == null ? null : c.getFuneralPlan().getPlanName() + " · "
                            + FuneralPlanService.code(c.getFuneralPlan().getId()),
                    c.getWakeRoom() == null ? null : c.getWakeRoom().getName(), c.getWakeStartAt(), c.getWakeEndAt(),
                    employeeName(c.getThanatopractor()), c.getPreparationCompletedAt());
        }
        List<Line> lines = detailRepository.findBySale(saleId).stream()
                .map(d -> new Line(d.getDescription(), d.getQuantity(), d.getUnitPrice(), d.getSubtotal(),
                        d.getInventoryItem() == null ? null : d.getInventoryItem().getId()))
                .toList();
        List<Payment> payments = transactionRepository.findIncomeBySale(saleId, TransactionType.INCOME).stream()
                .map(t -> new Payment(t.getId(), t.getTransactionDate() == null ? null
                        : AccessLogService.toAppTime(t.getTransactionDate()), t.getAmount(), t.getDescription(),
                        employeeName(t.getEmployee()), t.getStatus() == TransactionStatus.VOIDED))
                .toList();
        boolean canPrepare = contract != null && contract.status() == ContractStatus.ACTIVE
                && contract.preparationCompletedAt() == null && canRegisterPreparation();
        return new SaleView(row, ClientService.displayName(client),
                document(client.getDocumentType(), client.getDocumentNumber()), client.getPhone(),
                employeeName(sale.getEmployee()), contract, lines, payments, canPrepare);
    }

    /**
     * Venta vigente de un contrato (o la última anulada), para el botón "Ver contrato" de Salas.
     * Devuelve null si el contrato todavía no tiene venta.
     */
    @Transactional(readOnly = true)
    public SaleView detailOfContract(Long contractId) {
        requireAccess();
        List<Sale> active = saleRepository.findByContract(contractId, SaleStatus.ACTIVE);
        List<Sale> found = active.isEmpty() ? saleRepository.findByContract(contractId, SaleStatus.VOIDED) : active;
        return found.isEmpty() ? null : detail(found.get(found.size() - 1).getId());
    }

    // ---------- listas y buscadores del formulario ----------

    @Transactional(readOnly = true)
    public List<Option> searchClients(String text) {
        requireAccess();
        return saleRepository.searchClients(pattern(text), PageRequest.of(0, SHORT_LIST)).stream()
                .map(c -> new Option(c.getId(), ClientService.displayName(c),
                        (c.getClientType() == ClientType.CORPORATE ? "Empresa · " : "")
                                + Objects.toString(document(c.getDocumentType(), c.getDocumentNumber()), "")))
                .toList();
    }

    /** Difuntos sin contrato vigente. */
    @Transactional(readOnly = true)
    public List<Option> searchDeceased(String text) {
        requireAccess();
        return contractRepository.searchDeceasedWithoutContract(ContractStatus.CANCELLED, pattern(text),
                        PageRequest.of(0, SHORT_LIST)).stream()
                .map(d -> new Option(d.getId(), fullName(d.getName(), d.getLastname()),
                        d.getDocumentNumber() == null ? "Sin documento" : "Doc. " + d.getDocumentNumber()))
                .toList();
    }

    /** Planes activos del cliente; el detalle dice si todavía está en carencia. */
    @Transactional(readOnly = true)
    public List<Option> plansOf(Long clientId) {
        requireAccess();
        if (clientId == null) {
            return List.of();
        }
        return contractRepository.findPlansOfClient(clientId).stream()
                .filter(p -> p.getStatus() == PlanStatus.ACTIVE)
                .map(p -> new Option(p.getId(), FuneralPlanService.code(p.getId()) + " · " + p.getPlanName(),
                        p.getStartDate() == null ? null : "desde " + p.getStartDate()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Option> salespeople() {
        requireAccess();
        return staffRepository.findActiveSalespeople(EmployeeStatus.ACTIVE).stream()
                .map(e -> new Option(e.getId(), fullName(e.getName(), e.getLastname()), null))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Option> thanatopractors() {
        requireAccess();
        return staffRepository.findActiveThanatopractors(EmployeeStatus.ACTIVE).stream()
                .map(e -> new Option(e.getId(), fullName(e.getName(), e.getLastname()), null))
                .toList();
    }

    /** Salas para el formulario; el detalle dice si es especial. */
    @Transactional(readOnly = true)
    public List<Option> rooms() {
        requireAccess();
        return roomRepository.findAllByOrderByIdAsc().stream()
                .map(r -> new Option(r.getId(), r.getName(), Boolean.TRUE.equals(r.getSurchargeApplies())
                        ? "Especial · con recargo" : "Sin recargo"))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ItemOption> searchItems(String text) {
        requireAccess();
        // Solo el inventario para la venta: los insumos de tanatopraxia no se venden.
        return itemRepository.searchSaleOptions(pattern(text), PageRequest.of(0, SHORT_LIST)).stream()
                .map(i -> new ItemOption(i.getId(), i.getName(), i.getCategory(),
                        i.getStockQuantity() == null ? 0 : i.getStockQuantity(), orZero(i.getUnitPrice())))
                .toList();
    }

    // ---------- cálculo de la venta ----------

    /** Calcula los conceptos sin guardar nada, para mostrar el detalle en el formulario. */
    @Transactional(readOnly = true)
    public Quote quote(ContractData data) {
        requireAccess();
        validateShape(data);
        List<Line> lines = lines(data);
        return new Quote(lines, sum(lines));
    }

    /**
     * Arma las líneas independientes de la venta: cuota base o pensión, excedente de
     * ataúd o ataúd, recargo de sala especial, buses adicionales, recargo por
     * beneficiarios del plan y productos. Revisa existencias.
     */
    private List<Line> lines(ContractData data) {
        List<Line> lines = new ArrayList<>();
        SaleKind kind = data.kind();
        if (kind == SaleKind.PENSION) {
            BigDecimal wages = data.pensionWages();
            BigDecimal value = wages.multiply(parameters.minimumWage()).setScale(2, RoundingMode.HALF_UP);
            lines.add(new Line(PENSION_LINE + " (" + wages.stripTrailingZeros().toPlainString()
                    + " salarios mínimos)", 1, checkUnit(value), value, null));
        } else if (kind.isService()) {
            BigDecimal base = data.baseAmount().setScale(2, RoundingMode.HALF_UP);
            lines.add(new Line(BASE_LINE, 1, checkUnit(base), base, null));
        }

        Map<Long, Integer> wanted = new LinkedHashMap<>();
        if (kind.isService() && data.coffinItemId() != null) {
            InventoryItem coffin = itemRepository.findById(data.coffinItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Artículo", data.coffinItemId()));
            BigDecimal price = orZero(coffin.getUnitPrice());
            if (kind == SaleKind.PLAN) {
                // Con plan solo se cobra la diferencia con el ataúd básico; si es el básico, no hay excedente.
                BigDecimal excess = price.subtract(parameters.basicCoffinPrice()).max(BigDecimal.ZERO);
                lines.add(new Line(cut(COFFIN_EXCESS_LINE + " · " + coffin.getName()), 1, excess, excess,
                        coffin.getId()));
            } else {
                lines.add(new Line(cut(COFFIN_LINE + " · " + coffin.getName()), 1, price, price, coffin.getId()));
            }
            wanted.merge(coffin.getId(), 1, (left, right) -> left + right);
        }

        if (kind.isService() && data.roomId() != null) {
            WakeRoom room = roomRepository.findById(data.roomId())
                    .orElseThrow(() -> new ResourceNotFoundException("Sala", data.roomId()));
            if (Boolean.TRUE.equals(room.getSurchargeApplies())) {
                BigDecimal surcharge = parameters.wakeRoomSurcharge();
                lines.add(new Line(WakeRoomService.surchargeText(room), 1, surcharge, surcharge, null));
            }
        }

        if (kind.isService() && data.extraBuses() > 0) {
            BigDecimal fee = parameters.extraBusFee();
            BigDecimal value = fee.multiply(BigDecimal.valueOf(data.extraBuses()));
            lines.add(new Line(BUS_LINE, data.extraBuses(), fee, value, null));
        }

        if (kind == SaleKind.PLAN) {
            BigDecimal surcharge = planService.detail(data.planId()).totalSurcharge();
            if (surcharge != null && surcharge.signum() > 0) {
                lines.add(new Line(BENEFICIARY_LINE, 1, checkUnit(surcharge), surcharge, null));
            }
        }

        for (ContractData.ItemLine product : data.products() == null ? List.<ContractData.ItemLine>of() : data.products()) {
            InventoryItem item = itemRepository.findById(product.itemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Artículo", product.itemId()));
            BigDecimal price = orZero(item.getUnitPrice());
            lines.add(new Line(cut(item.getName()), product.quantity(), price,
                    price.multiply(BigDecimal.valueOf(product.quantity())), item.getId()));
            wanted.merge(item.getId(), product.quantity(), (left, right) -> left + right);
        }

        // No se agrega una línea sin existencias; se informa lo disponible.
        for (Map.Entry<Long, Integer> entry : wanted.entrySet()) {
            InventoryItem item = itemRepository.findById(entry.getKey()).orElseThrow();
            int stock = item.getStockQuantity() == null ? 0 : item.getStockQuantity();
            if (entry.getValue() > stock) {
                throw new InsufficientStockException(stock);
            }
        }
        if (lines.isEmpty()) {
            throw new InvalidDataException("Agregue al menos un producto a la venta.");
        }
        if (sum(lines).compareTo(MAX_TOTAL) > 0) {
            throw new InvalidDataException("El total de la venta es demasiado alto.");
        }
        return lines;
    }

    // ---------- operaciones ----------

    /**
     * Registra el contrato (si es un servicio) y su venta con el detalle por concepto, descuenta el
     * inventario y, si se recibe dinero, registra el abono inicial. Devuelve el id de la venta.
     */
    @Transactional
    public Long register(ContractData data) {
        requireAccess();
        Long actorId = currentUserId();
        validateShape(data);
        SaleKind kind = data.kind();
        Client client = clientById(data.clientId());
        if (kind == SaleKind.PENSION && client.getClientType() != ClientType.CORPORATE) {
            // La entidad pagadora debe estar registrada como cliente corporativo.
            throw new InvalidDataException("El servicio por pensión se registra a nombre de la entidad pagadora, "
                    + "que debe ser un cliente empresa.");
        }

        ServiceContract contract = null;
        if (kind.isService()) {
            contract = prepareContract(data, client);
        }

        List<Line> lines = lines(data);
        BigDecimal total = sum(lines);
        BigDecimal initial = data.initialPayment() == null ? BigDecimal.ZERO
                : data.initialPayment().setScale(2, RoundingMode.HALF_UP);
        if (initial.signum() < 0) {
            throw new InvalidDataException("El abono inicial no puede ser negativo.");
        }
        if (initial.compareTo(total) > 0) {
            throw new InstallmentExceedsBalanceException(total);
        }

        Employee salesperson = data.salespersonId() == null ? null : findSalesperson(data.salespersonId());
        if (contract != null) {
            contract.setFuneralSalesperson((FuneralSalesperson) salesperson);
            contract.setTotalAmount(total);
            contract = contractRepository.save(contract);
        }

        Sale sale = new Sale();
        sale.setClient(client);
        sale.setSaleType(kind.getSaleType());
        sale.setEmployee(salesperson);
        sale.setServiceContract(contract);
        sale.setTotalAmount(total);
        sale.setStatus(SaleStatus.ACTIVE);
        Sale saved = saleRepository.save(sale);

        for (Line line : lines) {
            SaleDetail detail = new SaleDetail();
            detail.setSale(saved);
            detail.setDescription(line.description());
            detail.setQuantity(line.quantity());
            detail.setUnitPrice(line.unitPrice());
            detail.setSubtotal(line.subtotal());
            if (line.itemId() != null) {
                InventoryItem item = itemRepository.findForUpdate(line.itemId())
                        .orElseThrow(() -> new ResourceNotFoundException("Artículo", line.itemId()));
                int stock = item.getStockQuantity() == null ? 0 : item.getStockQuantity();
                if (line.quantity() > stock) {
                    throw new InsufficientStockException(stock);
                }
                item.setStockQuantity(stock - line.quantity());
                itemRepository.save(item);
                detail.setInventoryItem(item);
            }
            detailRepository.save(detail);
        }

        if (initial.signum() > 0) {
            saveIncome(saved, initial, "Abono inicial venta " + "%04d".formatted(saved.getId()), actorId);
        }
        String code = "%04d".formatted(saved.getId());
        accessLogService.record(actorId, contract != null
                ? AccessLogService.CONTRACT_REGISTERED + contract.getContractNumber() + " " + kind.name()
                : AccessLogService.SALE_REGISTERED + code + " " + kind.name());
        return saved.getId();
    }

    /**
     * Contrato del servicio: número consecutivo único, difunto sin otro contrato vigente,
     * plan con la carencia cumplida y sala sin cruce de horario.
     * Si el difunto tiene un contrato cancelado (venta anulada) se reutiliza ese registro, porque la
     * tabla no admite dos contratos por difunto.
     */
    private ServiceContract prepareContract(ContractData data, Client client) {
        ServiceContract contract = contractRepository.findByDeceasedId(data.deceasedId()).orElse(null);
        if (contract != null && contract.getStatus() != ContractStatus.CANCELLED) {
            throw new InvalidDataException("El difunto ya tiene el contrato " + contract.getContractNumber()
                    + ". No se puede crear otro.");
        }
        if (contract == null) {
            contract = new ServiceContract();
            contract.setContractNumber(nextContractNumber());
        }
        Deceased deceased = findDeceased(data.deceasedId());
        contract.setDeceased(deceased);
        contract.setClient(client);
        contract.setStatus(ContractStatus.ACTIVE);
        contract.setThanatopractor(null);
        contract.setPreparationCompletedAt(null);

        FuneralPlan plan = null;
        if (data.kind() == SaleKind.PLAN) {
            planService.validateBenefitsAvailable(data.planId());
            plan = contractRepository.findPlansOfClient(client.getId()).stream()
                    .filter(p -> p.getId().equals(data.planId()))
                    .findFirst()
                    .orElseThrow(() -> new InvalidDataException("El plan elegido no es del cliente seleccionado."));
        }
        contract.setFuneralPlan(plan);

        if (data.roomId() != null) {
            WakeRoomService.validateRange(data.wakeStart(), data.wakeEnd());
            WakeRoom room = roomRepository.findById(data.roomId())
                    .orElseThrow(() -> new ResourceNotFoundException("Sala", data.roomId()));
            Long selfId = contract.getId() == null ? -1L : contract.getId();
            if (contractRepository.existsWakeOverlap(room.getId(), selfId, data.wakeStart(), data.wakeEnd(),
                    ContractStatus.CANCELLED)) {
                throw new InvalidDataException("La " + room.getName() + " ya está ocupada en ese horario.");
            }
            contract.setWakeRoom(room);
            contract.setWakeStartAt(data.wakeStart());
            contract.setWakeEndAt(data.wakeEnd());
        } else {
            // Sin sala el contrato se registra sin sala y sin recargo.
            contract.setWakeRoom(null);
            contract.setWakeStartAt(null);
            contract.setWakeEndAt(null);
        }
        return contract;
    }

    /** "CT-2026-0001": consecutivo del año; la columna unique impide repetidos. */
    private String nextContractNumber() {
        int year = LocalDate.now(clock).getYear();
        String prefix = "CT-" + year + "-";
        int last = contractRepository.findLastSequence(prefix + "%");
        return prefix + "%04d".formatted(last + 1);
    }

    /** Abono que no supera el saldo; cuando el saldo llega a cero la venta queda saldada. */
    @Transactional
    public void registerPayment(SalePaymentData data) {
        requireAccess();
        Long actorId = currentUserId();
        if (data == null || data.saleId() == null) {
            throw new InvalidDataException("Seleccione la venta.");
        }
        if (data.amount() == null || data.amount().signum() <= 0) {
            throw new InvalidDataException("Ingrese un abono mayor que cero.");
        }
        Sale sale = saleRepository.findDetailById(data.saleId())
                .orElseThrow(() -> new ResourceNotFoundException("Venta", data.saleId()));
        if (sale.getStatus() == SaleStatus.VOIDED) {
            throw new InvalidDataException("La venta está anulada: no recibe abonos.");
        }
        BigDecimal amount = data.amount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal balance = balanceOf(sale);
        if (balance.signum() <= 0) {
            throw new InvalidDataException("La venta ya está saldada.");
        }
        if (amount.compareTo(balance) > 0) {
            throw new InstallmentExceedsBalanceException(balance);
        }
        String code = "%04d".formatted(sale.getId());
        String description = data.description() == null || data.description().isBlank()
                ? "Abono venta " + code : data.description().trim();
        if (description.length() > 150) {
            throw new InvalidDataException("La descripción del abono admite hasta 150 caracteres.");
        }
        saveIncome(sale, amount, description, actorId);
        accessLogService.record(actorId, AccessLogService.SALE_PAYMENT_REGISTERED + code + " "
                + amount.toPlainString());
    }

    /**
     * Anula una venta: no se borra. Sus abonos vigentes quedan anulados, los artículos vuelven al
     * inventario y su contrato pasa a CANCELADO (libera la sala).
     */
    @Transactional
    public void voidSale(Long saleId) {
        requireAccess();
        Long actorId = currentUserId();
        Sale sale = saleRepository.findDetailById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("Venta", saleId));
        if (sale.getStatus() == SaleStatus.VOIDED) {
            throw new InvalidDataException("La venta ya está anulada.");
        }
        sale.setStatus(SaleStatus.VOIDED);
        saleRepository.save(sale);
        for (FinancialTransaction t : transactionRepository.findIncomeBySale(saleId, TransactionType.INCOME)) {
            if (t.getStatus() == TransactionStatus.ACTIVE) {
                t.setStatus(TransactionStatus.VOIDED);
                transactionRepository.save(t);
            }
        }
        for (SaleDetail line : detailRepository.findBySale(saleId)) {
            if (line.getInventoryItem() != null) {
                InventoryItem item = itemRepository.findForUpdate(line.getInventoryItem().getId()).orElseThrow();
                int stock = item.getStockQuantity() == null ? 0 : item.getStockQuantity();
                item.setStockQuantity(stock + line.getQuantity());
                itemRepository.save(item);
            }
        }
        if (sale.getServiceContract() != null) {
            ServiceContract contract = contractRepository.findById(sale.getServiceContract().getId()).orElseThrow();
            contract.setStatus(ContractStatus.CANCELLED);
            contractRepository.save(contract);
        }
        accessLogService.record(actorId, AccessLogService.SALE_VOIDED + "%04d".formatted(saleId));
    }

    /**
     * La secretaría o el administrador (nunca el tanatólogo) registra quién terminó la
     * preparación del cuerpo y cuándo (paso 5).
     */
    @Transactional
    public void registerPreparation(Long contractId, Long thanatopractorId, LocalDateTime completedAt) {
        requireAccess();
        if (!canRegisterPreparation()) {
            throw new UnauthorizedActionException();
        }
        Long actorId = currentUserId();
        if (thanatopractorId == null) {
            throw new InvalidDataException("Seleccione el tanatólogo que hizo la preparación.");
        }
        if (completedAt == null) {
            throw new InvalidDataException("Ingrese la fecha y hora en que terminó la preparación.");
        }
        if (completedAt.isAfter(LocalDateTime.now(clock))) {
            throw new InvalidDataException("La preparación no puede terminar en el futuro.");
        }
        ServiceContract contract = contractRepository.findDetailById(contractId)
                .orElseThrow(() -> new ResourceNotFoundException("Contrato", contractId));
        if (contract.getStatus() != ContractStatus.ACTIVE) {
            throw new InvalidDataException("Solo se registra la preparación de un contrato activo.");
        }
        Employee employee = staffRepository.findById(thanatopractorId)
                .orElseThrow(() -> new ResourceNotFoundException("Tanatólogo", thanatopractorId));
        if (!(employee instanceof Thanatopractor thanatopractor)) {
            throw new InvalidDataException("El empleado elegido no es tanatólogo.");
        }
        if (!employee.isActive()) {
            throw new InactiveEmployeeException();
        }
        contract.setThanatopractor(thanatopractor);
        contract.setPreparationCompletedAt(completedAt);
        contractRepository.save(contract);
        accessLogService.record(actorId, AccessLogService.PREPARATION_REGISTERED + contract.getContractNumber());
    }

    /** El Administrador Maestro o la Secretaría registran el fin de la preparación. */
    public boolean canRegisterPreparation() {
        return sessionContext.getRoleName()
                .map(role -> RoleNames.MASTER_ADMIN.equals(role) || RoleNames.SECRETARY.equals(role))
                .orElse(false);
    }

    // ---------- validaciones ----------

    /** Revisa que el formulario traiga lo que pide cada tipo de venta. */
    static void validateShape(ContractData data) {
        if (data == null || data.kind() == null) {
            throw new InvalidDataException("Seleccione el tipo de venta.");
        }
        if (data.clientId() == null) {
            throw new InvalidDataException(data.kind() == SaleKind.PENSION
                    ? "Seleccione la entidad pagadora." : "Seleccione el cliente.");
        }
        SaleKind kind = data.kind();
        if (kind.isService()) {
            if (data.deceasedId() == null) {
                throw new InvalidDataException("Seleccione el difunto del servicio.");
            }
            if (data.salespersonId() == null) {
                throw new InvalidDataException("Seleccione el vendedor que gestiona el contrato.");
            }
        }
        if (kind == SaleKind.PLAN && data.planId() == null) {
            throw new InvalidDataException("Seleccione el plan exequial del cliente.");
        }
        if (kind == SaleKind.PENSION) {
            if (data.pensionWages() == null || data.pensionWages().signum() <= 0) {
                throw new InvalidDataException("Ingrese cuántos salarios mínimos cubre la entidad pensional.");
            }
        } else if (kind.isService()) {
            if (data.baseAmount() == null || data.baseAmount().signum() < 0) {
                throw new InvalidDataException("Ingrese la cuota base del servicio (puede ser 0).");
            }
        }
        if (data.extraBuses() < 0) {
            throw new InvalidDataException("La cantidad de buses adicionales no puede ser negativa.");
        }
        if (kind.isService() && data.roomId() != null) {
            WakeRoomService.validateRange(data.wakeStart(), data.wakeEnd());
        }
        if (data.products() != null) {
            for (ContractData.ItemLine product : data.products()) {
                if (product == null || product.itemId() == null) {
                    throw new InvalidDataException("Seleccione el artículo de cada producto.");
                }
                if (product.quantity() <= 0) {
                    throw new InvalidDataException("La cantidad de cada producto debe ser mayor que cero.");
                }
            }
        }
        if (kind == SaleKind.PRODUCT && (data.products() == null || data.products().isEmpty())) {
            throw new InvalidDataException("Agregue al menos un producto a la venta.");
        }
    }

    private static BigDecimal checkUnit(BigDecimal value) {
        if (value.compareTo(MAX_UNIT) > 0) {
            throw new InvalidDataException("El valor de un concepto es demasiado alto.");
        }
        return value;
    }

    // ---------- utilidades ----------

    private void saveIncome(Sale sale, BigDecimal amount, String description, Long actorId) {
        FinancialTransaction income = new FinancialTransaction();
        income.setTransactionType(TransactionType.INCOME);
        income.setRelatedSale(sale);
        income.setAmount(amount);
        income.setDescription(description.length() > 150 ? description.substring(0, 150) : description);
        income.setStatus(TransactionStatus.ACTIVE);
        income.setEmployee(sessionContext.getCurrentUser().map(user -> user.getEmployee()).orElse(null));
        transactionRepository.save(income);
    }

    private BigDecimal balanceOf(Sale sale) {
        BigDecimal paid = transactionRepository.sumIncomeBySale(sale.getId(), TransactionType.INCOME,
                TransactionStatus.ACTIVE);
        return orZero(sale.getTotalAmount()).subtract(orZero(paid));
    }

    private Client clientById(Long id) {
        return saleRepository.findClientById(id).orElseThrow(() -> new ResourceNotFoundException("Cliente", id));
    }

    private Deceased findDeceased(Long id) {
        return saleRepository.findDeceasedById(id).orElseThrow(() -> new ResourceNotFoundException("Difunto", id));
    }

    private Employee findSalesperson(Long id) {
        Employee employee = staffRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vendedor", id));
        if (!(employee instanceof FuneralSalesperson)) {
            throw new InvalidDataException("El empleado elegido no es vendedor funerario.");
        }
        if (employee.getStatus() != EmployeeStatus.ACTIVE) {
            throw new InvalidDataException("El vendedor elegido está inactivo.");
        }
        return employee;
    }

    private static BigDecimal sum(List<Line> lines) {
        return lines.stream().map(line -> line.subtotal()).reduce(BigDecimal.ZERO, (left, right) -> left.add(right));
    }

    private static String cut(String text) {
        return text.length() > 100 ? text.substring(0, 100) : text;
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** Hora de la app (Bogotá) pasada a UTC, la zona de sale.sale_date. */
    private static LocalDateTime toDb(LocalDateTime appTime) {
        return ZonedDateTime.of(appTime, AccessLogService.APP_ZONE)
                .withZoneSameInstant(AccessLogService.DB_ZONE).toLocalDateTime();
    }

    static String monthName(YearMonth month) {
        return month.getMonth().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("es-CO")).toLowerCase(Locale.ROOT);
    }

    private static String employeeName(Employee employee) {
        return employee == null ? null : fullName(employee.getName(), employee.getLastname());
    }

    static String fullName(String name, String lastname) {
        return ((name == null ? "" : name) + " " + (lastname == null ? "" : lastname)).trim();
    }

    private static String document(String type, String number) {
        if (number == null) {
            return null;
        }
        return type == null || type.isBlank() ? number : type + " " + number;
    }

    private static String pattern(String text) {
        String clean = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        return "%" + clean + "%";
    }

    /** Minúsculas y sin tildes, para buscar "duran" y encontrar "Durán". */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).trim();
    }

    private void requireAccess() {
        boolean allowed = sessionContext.getRoleName().map(AppModule.CONTRATOS::isAllowedFor).orElse(false);
        if (!allowed) {
            throw new UnauthorizedActionException();
        }
    }

    private Long currentUserId() {
        return sessionContext.getCurrentUser().map(user -> user.getId()).orElseThrow(UnauthorizedActionException::new);
    }
}
