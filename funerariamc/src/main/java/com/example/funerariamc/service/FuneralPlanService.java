package com.example.funerariamc.service;

import com.example.funerariamc.exception.BeneficiaryLimitExceededException;
import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.ResourceNotFoundException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.exception.WaitingPeriodException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Beneficiary;
import com.example.funerariamc.model.Client;
import com.example.funerariamc.model.FinancialTransaction;
import com.example.funerariamc.model.FuneralPlan;
import com.example.funerariamc.model.PlanPayment;
import com.example.funerariamc.model.enums.ClientType;
import com.example.funerariamc.model.enums.PlanPaymentStatus;
import com.example.funerariamc.model.enums.PlanStatus;
import com.example.funerariamc.model.enums.Relationship;
import com.example.funerariamc.model.enums.TransactionStatus;
import com.example.funerariamc.model.enums.TransactionType;
import com.example.funerariamc.persistence.BeneficiaryRepository;
import com.example.funerariamc.persistence.BeneficiaryRepository.BeneficiaryNameRow;
import com.example.funerariamc.persistence.ClientRepository;
import com.example.funerariamc.persistence.FinancialTransactionRepository;
import com.example.funerariamc.persistence.FuneralPlanRepository;
import com.example.funerariamc.persistence.FuneralPlanRepository.PlanCardRow;
import com.example.funerariamc.persistence.FuneralPlanRepository.UsedServiceRow;
import com.example.funerariamc.persistence.PlanPaymentRepository;
import com.example.funerariamc.persistence.PlanPaymentRepository.MonthTotal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Planes exequiales (propuesta P-15 / P-15.1): tarjetas de afiliados con su estado de pago y
 * carencia, indicadores, recaudo de cuotas, detalle del plan con beneficiarios y cuotas,
 * afiliación (RF-PLA-01), beneficiarios con tope y recargo (RF-PLA-02), carencia (RF-PLA-03),
 * baja o cambio de titular (RF-PLA-04) y registro o anulación de cuotas (tabla plan_payment).
 * Lo usan Vendedor, Secretaría y Administrador Maestro (RN-21, RN-22).
 */
@Service
public class FuneralPlanService {

    /** Meses de la gráfica de recaudo y de la cuadrícula de cuotas del panel. */
    public static final int CHART_MONTHS = 6;
    public static final int GRID_MONTHS = 12;
    /** Valor máximo de numeric(10,2), igual que funeral_plan.monthly_fee. */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");

    private static final Locale ES = Locale.forLanguageTag("es-CO");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", ES);

    private final FuneralPlanRepository planRepository;
    private final BeneficiaryRepository beneficiaryRepository;
    private final PlanPaymentRepository paymentRepository;
    private final FinancialTransactionRepository transactionRepository;
    private final ClientRepository clientRepository;
    private final PlanParameters parameters;
    private final AccessLogService accessLogService;
    private final SessionContext sessionContext;
    private final Clock clock;

    // ---------- lo que recibe la pantalla ----------

    /** Filtro de pagos del desplegable "Pagos". */
    public enum PaymentFilter {
        ALL("Pagos: todos"),
        UP_TO_DATE("Al día"),
        OVERDUE("En mora"),
        WAITING("En carencia");

        private final String label;

        PaymentFilter(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** Estado de un mes en la cuadrícula de cuotas del panel. */
    public enum MonthState {
        PAID_ON_TIME, PAID_LATE, UNPAID, UPCOMING, BEFORE_START
    }

    /** Opción de una lista: resultado de búsqueda del diálogo. */
    public record Option(Long id, String label, String detail) {
    }

    /**
     * Tarjeta de un plan. waitingDaysLeft es 0 si ya cumplió la carencia (RN-08);
     * overdueMonths son las cuotas vencidas sin pagar.
     */
    public record PlanCard(Long id, String code, Long clientId, String holderName, String holderDocument,
                           boolean corporate, String planName, BigDecimal monthlyFee, LocalDate startDate,
                           PlanStatus status, long beneficiaries, List<String> initials, LocalDate waitingEnds,
                           long waitingDaysLeft, int waitingDays, long overdueMonths, BigDecimal overdueAmount,
                           LocalDateTime lastPaidAt, long servicesUsed) {

        public boolean isActive() {
            return status == PlanStatus.ACTIVE;
        }

        public boolean inWaiting() {
            return waitingDaysLeft > 0;
        }

        public boolean isOverdue() {
            return isActive() && overdueMonths > 0;
        }
    }

    public record Kpis(BigDecimal collectedThisMonth, BigDecimal collectedLastMonth, long paidThisMonth,
                       long activePlans, long newThisMonth, long overduePlans, BigDecimal overdueAmount,
                       long inWaiting, long waitingEndsSoon) {

        /** Variación del recaudo frente al mes anterior, en %; null si el mes anterior fue 0. */
        public Double changePercent() {
            if (collectedLastMonth.signum() == 0) {
                return null;
            }
            return collectedThisMonth.subtract(collectedLastMonth).doubleValue() * 100
                    / collectedLastMonth.doubleValue();
        }
    }

    /** Un mes de la gráfica: lo esperado (cuotas de los planes activos) y lo recaudado. */
    public record MonthPoint(YearMonth month, BigDecimal expected, BigDecimal collected) {
    }

    /** Porción de la dona "Planes por tipo" (por nombre del plan). */
    public record TypeSlice(String name, long plans) {
    }

    public record WaitingSoon(Long planId, String code, String holderName, LocalDate waitingEnds) {
    }

    /** Todo lo que pinta la pantalla. cards trae todos los planes; el filtro se aplica con {@link #filter}. */
    public record Overview(Kpis kpis, List<MonthPoint> collection, List<TypeSlice> types,
                           List<WaitingSoon> waitingSoon, List<String> planNames, List<PlanCard> cards,
                           int waitingDays) {
    }

    /** Filtro de la barra superior: estado del plan, pagos, tipo de plan (null = todos) y texto. */
    public record PlanFilter(PlanStatus status, PaymentFilter payments, String planName, String text) {
    }

    /** Beneficiario del panel. surcharge es el recargo que se cobra en la venta del servicio (RF-CIV-06). */
    public record BeneficiaryInfo(Long id, String fullName, String documentNumber, Relationship relationship,
                                  String relationshipLabel, BigDecimal surcharge, String surchargeReason,
                                  Long deceasedId, LocalDate deathDate) {

        public boolean isDeceased() {
            return deceasedId != null;
        }
    }

    public record MonthCell(YearMonth month, MonthState state) {
    }

    public record PaymentInfo(Long id, YearMonth period, BigDecimal amount, LocalDateTime paidAt,
                              PlanPaymentStatus status, String registeredBy) {
    }

    /** Difunto atendido con el plan; contractNumber y wakeRoom van en null si aún no hay contrato. */
    public record UsedService(Long deceasedId, String deceasedName, LocalDate deathDate, String contractNumber,
                              String wakeRoom) {
    }

    /** Panel del plan (P-15.1). nextDue es el primer mes sin pagar desde el inicio. */
    public record PlanDetail(PlanCard card, String holderPhone, String holderEmail, int beneficiaryLimit,
                             List<BeneficiaryInfo> beneficiaries, BigDecimal totalSurcharge,
                             List<MonthCell> grid, List<PaymentInfo> payments, YearMonth nextDue,
                             List<UsedService> usedServices, boolean holderDeceased) {
    }

    /** Lo que se le muestra al vendedor antes de guardar un beneficiario (RF-PLA-02, 3a). */
    public record SurchargePreview(long position, int limit, boolean corporate, boolean direct,
                                   BigDecimal nonDirect, BigDecimal extra) {

        public BigDecimal total() {
            return nonDirect.add(extra);
        }

        public boolean applies() {
            return total().signum() > 0;
        }
    }

    @Autowired
    public FuneralPlanService(FuneralPlanRepository planRepository, BeneficiaryRepository beneficiaryRepository,
                              PlanPaymentRepository paymentRepository,
                              FinancialTransactionRepository transactionRepository,
                              ClientRepository clientRepository,
                              PlanParameters parameters, AccessLogService accessLogService,
                              SessionContext sessionContext) {
        this(planRepository, beneficiaryRepository, paymentRepository, transactionRepository,
                clientRepository, parameters, accessLogService, sessionContext,
                Clock.system(AccessLogService.APP_ZONE));
    }

    /** Las pruebas fijan la fecha de hoy con un Clock. */
    FuneralPlanService(FuneralPlanRepository planRepository, BeneficiaryRepository beneficiaryRepository,
                       PlanPaymentRepository paymentRepository, FinancialTransactionRepository transactionRepository,
                       ClientRepository clientRepository,
                       PlanParameters parameters, AccessLogService accessLogService, SessionContext sessionContext,
                       Clock clock) {
        this.planRepository = planRepository;
        this.beneficiaryRepository = beneficiaryRepository;
        this.paymentRepository = paymentRepository;
        this.transactionRepository = transactionRepository;
        this.clientRepository = clientRepository;
        this.parameters = parameters;
        this.accessLogService = accessLogService;
        this.sessionContext = sessionContext;
        this.clock = clock;
    }

    // ---------- consultas ----------

    /** Indicadores, gráfica, dona, carencias próximas y todas las tarjetas. */
    @Transactional(readOnly = true)
    public Overview overview() {
        requireAccess();
        LocalDate today = today();
        YearMonth current = YearMonth.from(today);
        int waitingDays = parameters.waitingDays();
        int waitingSoonDays = parameters.waitingSoonDays();

        // Las cuotas pagadas de cada plan vienen en la misma fila de la tarjeta (una consulta menos).
        List<PlanCardRow> rows = planRepository.findCards(PlanPaymentStatus.PAID, current.atDay(1));
        Map<Long, Long> earlyCurrentPayments = new HashMap<>();
        boolean needsCurrentPayments = rows.stream().anyMatch(row -> row.getStatus() == PlanStatus.ACTIVE
                && row.getPaidMonths() > 0 && dueMonths(row.getStartDate(), today) > 0
                && today.isBefore(dueDate(row.getStartDate(), current)));
        if (needsCurrentPayments) {
            // Un pago de este mes aún no vencido no salda una cuota anterior pendiente.
            // Una sola consulta agrupada en memoria evita consultar cada plan por separado.
            PlanPayment probe = new PlanPayment();
            probe.setStatus(PlanPaymentStatus.PAID);
            probe.setPeriodMonth(current.atDay(1));
            for (PlanPayment payment : paymentRepository.findAll(Example.of(probe))) {
                earlyCurrentPayments.merge(payment.getFuneralPlan().getId(), 1L,
                        (total, increment) -> total + increment);
            }
        }
        Map<Long, List<String>> initials = new HashMap<>();
        if (!rows.isEmpty()) {
            for (BeneficiaryNameRow row : beneficiaryRepository.findNames(rows.stream().map(row -> row.getPlanId()).toList())) {
                initials.computeIfAbsent(row.getPlanId(), key -> new ArrayList<>()).add(initials(row.getName(), row.getLastname()));
            }
        }
        List<PlanCard> cards = rows.stream()
                .map(row -> card(row, initials.getOrDefault(row.getPlanId(), List.of()), waitingDays, today,
                        earlyCurrentPayments.getOrDefault(row.getPlanId(), 0L)))
                .toList();

        // Recaudo por mes de los últimos 6 meses.
        YearMonth first = current.minusMonths(CHART_MONTHS - 1L);
        Map<YearMonth, MonthTotal> totals = new HashMap<>();
        for (MonthTotal total : paymentRepository.totalsByMonth(PlanPaymentStatus.PAID, first.atDay(1), current.atDay(1))) {
            totals.put(YearMonth.from(total.getPeriodMonth()), total);
        }
        List<MonthPoint> collection = new ArrayList<>();
        for (YearMonth month = first; !month.isAfter(current); month = month.plusMonths(1)) {
            collection.add(new MonthPoint(month, expected(cards, month), totalOf(totals.get(month))));
        }

        List<PlanCard> active = cards.stream().filter(card -> card.isActive()).toList();
        LocalDate firstOfMonth = current.atDay(1);
        Kpis kpis = new Kpis(
                totalOf(totals.get(current)), totalOf(totals.get(current.minusMonths(1))),
                totals.get(current) == null ? 0 : totals.get(current).getPayments(),
                active.size(),
                active.stream().filter(c -> c.startDate() != null && !c.startDate().isBefore(firstOfMonth)).count(),
                active.stream().filter(card -> card.isOverdue()).count(),
                active.stream().map(card -> card.overdueAmount()).reduce(BigDecimal.ZERO, (total, amount) -> total.add(amount)),
                active.stream().filter(card -> card.inWaiting()).count(),
                active.stream().filter(c -> c.inWaiting() && c.waitingDaysLeft() <= waitingSoonDays).count());

        List<WaitingSoon> waitingSoon = active.stream()
                .filter(c -> c.inWaiting() && c.waitingDaysLeft() <= waitingSoonDays)
                .sorted(Comparator.comparing(card -> card.waitingEnds()))
                .map(c -> new WaitingSoon(c.id(), c.code(), c.holderName(), c.waitingEnds()))
                .toList();

        return new Overview(kpis, collection, types(active), waitingSoon, planNames(cards), cards, waitingDays);
    }

    /** Aplica el filtro de la barra superior sin volver a consultar la base. */
    public static List<PlanCard> filter(List<PlanCard> cards, PlanFilter filter) {
        String text = normalize(filter.text());
        return cards.stream()
                .filter(c -> filter.status() == null || c.status() == filter.status())
                .filter(c -> filter.planName() == null || normalize(c.planName()).equals(normalize(filter.planName())))
                .filter(c -> switch (filter.payments() == null ? PaymentFilter.ALL : filter.payments()) {
                    case ALL -> true;
                    case UP_TO_DATE -> !c.isOverdue();
                    case OVERDUE -> c.isOverdue();
                    case WAITING -> c.inWaiting();
                })
                .filter(c -> text.isEmpty() || normalize(c.holderName()).contains(text)
                        || normalize(c.holderDocument()).contains(text) || normalize(c.code()).contains(text))
                .toList();
    }

    /** Panel del plan: beneficiarios con su recargo, cuadrícula de 12 meses, cuotas y servicios usados. */
    @Transactional(readOnly = true)
    public PlanDetail detail(Long planId) {
        requireAccess();
        LocalDate today = today();
        FuneralPlan plan = getPlan(planId);
        Client holder = plan.getClient();
        int limit = parameters.beneficiaryLimit();
        int waitingDays = parameters.waitingDays();
        boolean corporate = holder.getClientType() == ClientType.CORPORATE;

        List<Beneficiary> beneficiaries = beneficiaryRepository.findByFuneralPlanIdOrderByIdAsc(planId);
        // Difuntos del plan con su contrato y sala en una sola consulta.
        List<UsedServiceRow> deceased = planRepository.findUsedServices(planId);
        Map<String, UsedServiceRow> deceasedByDocument = new HashMap<>();
        for (UsedServiceRow d : deceased) {
            if (d.getDocumentNumber() != null) {
                deceasedByDocument.put(d.getDocumentNumber(), d);
            }
        }

        List<BeneficiaryInfo> infos = new ArrayList<>();
        BigDecimal totalSurcharge = BigDecimal.ZERO;
        long position = 0;
        for (Beneficiary b : beneficiaries) {
            position++;
            Relationship relationship = relationshipOf(b.getRelationship());
            SurchargePreview surcharge = surcharge(position, limit, corporate, relationship);
            totalSurcharge = totalSurcharge.add(surcharge.total());
            UsedServiceRow match = b.getDocumentNumber() == null ? null : deceasedByDocument.get(b.getDocumentNumber());
            infos.add(new BeneficiaryInfo(b.getId(), fullName(b.getName(), b.getLastname()), b.getDocumentNumber(),
                    relationship, relationship == null ? orDash(b.getRelationship()) : relationship.getDisplayName(),
                    surcharge.total(), surchargeReason(surcharge), match == null ? null : match.getDeceasedId(),
                    match == null ? null : match.getDeathDate()));
        }

        List<PlanPayment> payments = paymentRepository.findHistory(planId);
        Map<YearMonth, PlanPayment> paidByMonth = new HashMap<>();
        long paidUntilNow = 0;
        YearMonth current = YearMonth.from(today);
        for (PlanPayment p : payments) {
            if (p.getStatus() == PlanPaymentStatus.PAID) {
                paidByMonth.put(YearMonth.from(p.getPeriodMonth()), p);
                if (!YearMonth.from(p.getPeriodMonth()).isAfter(current)
                        && (plan.getStartDate() == null
                        || !today.isBefore(dueDate(plan.getStartDate(), YearMonth.from(p.getPeriodMonth()))))) {
                    paidUntilNow++;
                }
            }
        }
        List<MonthCell> grid = new ArrayList<>();
        for (YearMonth month = current.minusMonths(GRID_MONTHS - 1L); !month.isAfter(current); month = month.plusMonths(1)) {
            grid.add(new MonthCell(month, monthState(plan.getStartDate(), month, paidByMonth.get(month), today)));
        }
        YearMonth nextDue = null;
        if (plan.getStartDate() != null) {
            nextDue = YearMonth.from(plan.getStartDate());
            while (paidByMonth.containsKey(nextDue)) {
                nextDue = nextDue.plusMonths(1);
            }
        }

        List<UsedService> used = deceased.stream()
                .map(d -> new UsedService(d.getDeceasedId(), fullName(d.getName(), d.getLastname()), d.getDeathDate(),
                        d.getContractNumber(), d.getWakeRoomName()))
                .toList();
        boolean holderDeceased = holder.getDocumentNumber() != null
                && deceasedByDocument.containsKey(holder.getDocumentNumber());

        PlanCardRow row = new SimpleCardRow(plan, beneficiaries.size(), deceased.size(), paidUntilNow,
                payments.stream().filter(p -> p.getStatus() == PlanPaymentStatus.PAID)
                        .map(payment -> payment.getPaidAt()).filter(Objects::nonNull)
                        .max(Comparator.naturalOrder()).orElse(null));
        PlanCard card = card(row, beneficiaries.stream().map(b -> initials(b.getName(), b.getLastname())).toList(),
                waitingDays, today, 0);

        List<PaymentInfo> paymentInfos = payments.stream()
                .map(p -> new PaymentInfo(p.getId(), YearMonth.from(p.getPeriodMonth()), p.getAmount(), p.getPaidAt(),
                        p.getStatus(), p.getRegisteredByEmployee() == null ? null
                        : fullName(p.getRegisteredByEmployee().getName(), p.getRegisteredByEmployee().getLastname())))
                .toList();

        return new PlanDetail(card, holder.getPhone(), holder.getEmail(), limit, infos, totalSurcharge, grid,
                paymentInfos, nextDue, used, holderDeceased);
    }

    /** Clientes para los diálogos de afiliación y cambio de titular, por nombre o documento. */
    @Transactional(readOnly = true)
    public List<Option> searchClients(String text) {
        requireAccess();
        return clientRepository.search(pattern(text), List.of(ClientType.values()), PageRequest.of(0, 20))
                .getContent().stream()
                .map(c -> new Option(c.getId(), ClientService.displayName(c), document(c)))
                .toList();
    }

    /** Recargo que tendría el siguiente beneficiario del plan con ese parentesco (RF-PLA-02, 3a). */
    @Transactional(readOnly = true)
    public SurchargePreview previewSurcharge(Long planId, Relationship relationship) {
        requireAccess();
        if (relationship == null) {
            throw new InvalidDataException("Seleccione el parentesco con el titular.");
        }
        FuneralPlan plan = getPlan(planId);
        if (plan.getStatus() != PlanStatus.ACTIVE) {
            throw new InvalidDataException("Solo se agregan beneficiarios a un plan activo.");
        }
        long position = beneficiaryRepository.countByFuneralPlanId(planId) + 1;
        return surcharge(position, parameters.beneficiaryLimit(),
                plan.getClient().getClientType() == ClientType.CORPORATE, relationship);
    }

    // ---------- operaciones ----------

    /** RF-PLA-01: afilia un plan ACTIVO; la fecha de inicio es la de afiliación para la carencia (RN-08). */
    @Transactional
    public FuneralPlan register(PlanData data) {
        requireAccess();
        Long actorId = currentUserId();
        if (data == null || data.clientId() == null) {
            throw new InvalidDataException("Seleccione el cliente titular del plan.");
        }
        String name = data.planName() == null ? "" : data.planName().trim();
        if (name.isEmpty()) {
            throw new InvalidDataException("Ingrese el nombre del plan.");
        }
        if (name.length() > 80) {
            throw new InvalidDataException("El nombre del plan admite máximo 80 caracteres.");
        }
        validateAmount(data.monthlyFee(), "La cuota mensual");
        if (data.startDate() == null) {
            throw new InvalidDataException("Ingrese la fecha de inicio del plan.");
        }
        int maxStartMonthsAhead = parameters.maxStartMonthsAhead();
        if (data.startDate().isAfter(today().plusMonths(maxStartMonthsAhead))) {
            throw new InvalidDataException("La fecha de inicio no puede ser más de "
                    + maxStartMonthsAhead + " mes(es) posterior a hoy.");
        }
        Client client = clientRepository.findById(data.clientId())
                .orElseThrow(() -> new ResourceNotFoundException("Cliente", data.clientId()));
        requireNoOtherActivePlan(client, null);

        FuneralPlan plan = new FuneralPlan();
        plan.setClient(client);
        plan.setPlanName(name);
        plan.setMonthlyFee(data.monthlyFee());
        plan.setStartDate(data.startDate());
        plan.setStatus(PlanStatus.ACTIVE);
        FuneralPlan saved = planRepository.save(plan);
        accessLogService.record(actorId, AccessLogService.PLAN_REGISTERED + code(saved.getId()));
        return saved;
    }

    /**
     * RF-PLA-02: agrega un beneficiario a un plan ACTIVO. Si corresponde recargo (parentesco no
     * directo, RN-09, o por encima del tope, RN-10) el vendedor debe haberlo aceptado antes.
     */
    @Transactional
    public Beneficiary addBeneficiary(BeneficiaryData data) {
        requireAccess();
        Long actorId = currentUserId();
        if (data == null || data.planId() == null) {
            throw new InvalidDataException("Seleccione el plan.");
        }
        FuneralPlan plan = getPlan(data.planId());
        if (plan.getStatus() != PlanStatus.ACTIVE) {
            throw new InvalidDataException("Solo se agregan beneficiarios a un plan activo.");
        }
        String name = required(data.name(), "el nombre", 25);
        String lastname = required(data.lastname(), "el apellido", 25);
        String document = data.documentNumber() == null ? "" : data.documentNumber().trim();
        if (!document.matches("\\d{5,11}")) {
            throw new InvalidDataException("El documento del beneficiario debe tener entre 5 y 11 dígitos.");
        }
        if (data.relationship() == null) {
            throw new InvalidDataException("Seleccione el parentesco con el titular.");
        }
        if (document.equals(plan.getClient().getDocumentNumber())) {
            throw new InvalidDataException("Ese documento es del titular: el titular ya está cubierto por su plan.");
        }
        if (beneficiaryRepository.existsByFuneralPlanIdAndDocumentNumber(plan.getId(), document)) {
            throw new InvalidDataException("Ya hay un beneficiario con ese documento en este plan.");
        }
        long position = beneficiaryRepository.countByFuneralPlanId(plan.getId()) + 1;
        SurchargePreview surcharge = surcharge(position, parameters.beneficiaryLimit(),
                plan.getClient().getClientType() == ClientType.CORPORATE, data.relationship());
        if (surcharge.applies() && !data.acceptSurcharge()) {
            if (surcharge.extra().signum() > 0) {
                throw new BeneficiaryLimitExceededException(surcharge.limit());
            }
            throw new InvalidDataException("Este beneficiario tiene recargo por parentesco no directo (RN-09). "
                    + "Confírmelo antes de guardar.");
        }

        Beneficiary beneficiary = new Beneficiary();
        beneficiary.setFuneralPlan(plan);
        beneficiary.setName(name);
        beneficiary.setLastname(lastname);
        beneficiary.setDocumentNumber(document);
        beneficiary.setRelationship(data.relationship().name());
        Beneficiary saved = beneficiaryRepository.save(beneficiary);
        accessLogService.record(actorId, AccessLogService.BENEFICIARY_ADDED + code(plan.getId()) + " " + document);
        return saved;
    }

    /**
     * Registra la cuota de un mes: guarda el ingreso en financial_transaction (lo ve Finanzas)
     * y la cuota en plan_payment. Solo una cuota pagada por mes.
     */
    @Transactional
    public PlanPayment registerPayment(PlanPaymentData data) {
        requireAccess();
        AppUser actor = sessionContext.getCurrentUser().orElseThrow(UnauthorizedActionException::new);
        if (actor.getEmployee() == null || actor.getEmployee().getId() == null) {
            throw new UnauthorizedActionException();
        }
        if (data == null || data.planId() == null) {
            throw new InvalidDataException("Seleccione el plan.");
        }
        FuneralPlan plan = getPlan(data.planId());
        if (plan.getStatus() == PlanStatus.CANCELLED) {
            throw new InvalidDataException("El plan " + code(plan.getId()) + " está cancelado: no recibe cuotas.");
        }
        if (data.period() == null) {
            throw new InvalidDataException("Seleccione el mes que paga.");
        }
        validateAmount(data.amount(), "El valor de la cuota");
        YearMonth start = plan.getStartDate() == null ? null : YearMonth.from(plan.getStartDate());
        if (start != null && data.period().isBefore(start)) {
            throw new InvalidDataException("El plan empezó en " + monthName(start) + ": no se cobran meses anteriores.");
        }
        int maxMonthsAhead = parameters.maxMonthsAhead();
        if (data.period().isAfter(YearMonth.from(today()).plusMonths(maxMonthsAhead))) {
            throw new InvalidDataException("Solo se pueden adelantar hasta " + maxMonthsAhead + " cuotas.");
        }
        LocalDate periodMonth = data.period().atDay(1);
        if (paymentRepository.existsByFuneralPlanIdAndPeriodMonthAndStatus(plan.getId(), periodMonth, PlanPaymentStatus.PAID)) {
            throw new InvalidDataException("La cuota de " + monthName(data.period()) + " ya está pagada.");
        }
        LocalDateTime now = LocalDateTime.now(clock);

        FinancialTransaction income = new FinancialTransaction();
        income.setTransactionType(TransactionType.INCOME);
        income.setAmount(data.amount());
        income.setTransactionDate(now);
        income.setEmployee(actor.getEmployee());
        income.setStatus(TransactionStatus.ACTIVE);
        income.setDescription(truncate("Cuota " + code(plan.getId()) + " · " + monthName(data.period()) + " · "
                + ClientService.displayName(plan.getClient()), 150));
        FinancialTransaction savedIncome = transactionRepository.save(income);

        PlanPayment payment = new PlanPayment();
        payment.setFuneralPlan(plan);
        payment.setPeriodMonth(periodMonth);
        payment.setAmount(data.amount());
        payment.setPaidAt(now);
        payment.setStatus(PlanPaymentStatus.PAID);
        payment.setFinancialTransaction(savedIncome);
        payment.setRegisteredByEmployee(actor.getEmployee());
        PlanPayment saved = paymentRepository.save(payment);
        accessLogService.record(actor.getId(), AccessLogService.PLAN_PAYMENT_REGISTERED + code(plan.getId()) + " " + data.period());
        return saved;
    }

    /** Anula una cuota y su ingreso: no se borran, quedan VOIDED. */
    @Transactional
    public void voidPayment(Long paymentId) {
        requireAccess();
        Long actorId = currentUserId();
        PlanPayment payment = paymentRepository.findDetailById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Cuota", paymentId));
        if (payment.getStatus() == PlanPaymentStatus.VOIDED) {
            throw new InvalidDataException("Esa cuota ya estaba anulada.");
        }
        payment.setStatus(PlanPaymentStatus.VOIDED);
        if (payment.getFinancialTransaction() != null) {
            payment.getFinancialTransaction().setStatus(TransactionStatus.VOIDED);
            transactionRepository.save(payment.getFinancialTransaction());
        }
        paymentRepository.save(payment);
        accessLogService.record(actorId, AccessLogService.PLAN_PAYMENT_VOIDED + code(payment.getFuneralPlan().getId())
                + " " + YearMonth.from(payment.getPeriodMonth()));
    }

    /** Suspende un plan activo (por ejemplo, por mora). Conserva beneficiarios y cuotas. */
    @Transactional
    public void suspend(Long planId) {
        changeStatus(planId, PlanStatus.ACTIVE, PlanStatus.SUSPENDED, AccessLogService.PLAN_SUSPENDED,
                "Solo se suspende un plan activo.");
    }

    /** Reactiva un plan suspendido. */
    @Transactional
    public void reactivate(Long planId) {
        requireAccess();
        Long actorId = currentUserId();
        FuneralPlan plan = getPlan(planId);
        if (plan.getStatus() != PlanStatus.SUSPENDED) {
            throw new InvalidDataException("Solo se reactiva un plan suspendido.");
        }
        requireNoOtherActivePlan(plan.getClient(), plan.getId());
        plan.setStatus(PlanStatus.ACTIVE);
        planRepository.save(plan);
        accessLogService.record(actorId, AccessLogService.PLAN_REACTIVATED + code(planId));
    }

    /** RF-PLA-04: da de baja el plan (CANCELLED) conservando su historial. */
    @Transactional
    public void cancel(Long planId) {
        requireAccess();
        Long actorId = currentUserId();
        FuneralPlan plan = getPlan(planId);
        if (plan.getStatus() == PlanStatus.CANCELLED) {
            throw new InvalidDataException("El plan " + code(planId) + " ya estaba cancelado.");
        }
        plan.setStatus(PlanStatus.CANCELLED);
        planRepository.save(plan);
        accessLogService.record(actorId, AccessLogService.PLAN_CANCELLED + code(planId));
    }

    /** RF-PLA-04: pasa el plan a otro titular registrado, con sus beneficiarios y sus cuotas. */
    @Transactional
    public void changeHolder(Long planId, Long newClientId) {
        requireAccess();
        Long actorId = currentUserId();
        FuneralPlan plan = getPlan(planId);
        if (plan.getStatus() == PlanStatus.CANCELLED) {
            throw new InvalidDataException("El plan " + code(planId) + " está cancelado: no se puede reasignar.");
        }
        if (newClientId == null) {
            throw new InvalidDataException("Seleccione el nuevo titular. Si no está registrado, regístrelo primero en Clientes.");
        }
        if (newClientId.equals(plan.getClient().getId())) {
            throw new InvalidDataException("Ese cliente ya es el titular del plan.");
        }
        Client client = clientRepository.findById(newClientId)
                .orElseThrow(() -> new ResourceNotFoundException("Cliente", newClientId));
        if (plan.getStatus() == PlanStatus.ACTIVE) {
            requireNoOtherActivePlan(client, plan.getId());
        }
        plan.setClient(client);
        planRepository.save(plan);
        accessLogService.record(actorId, AccessLogService.PLAN_HOLDER_CHANGED + code(planId) + " "
                + orDash(client.getDocumentNumber()));
    }

    /**
     * RN-08: impide usar beneficios de un plan inactivo o que no ha cumplido la carencia.
     * Debe llamarse desde el servicio que registre el contrato antes de guardar sus cambios.
     */
    @Transactional(readOnly = true)
    public void validateBenefitsAvailable(Long planId) {
        requireAccess();
        FuneralPlan plan = getPlan(planId);
        if (plan.getStatus() != PlanStatus.ACTIVE) {
            throw new InvalidDataException("Solo un plan activo puede utilizar sus beneficios.");
        }
        if (plan.getStartDate() == null) {
            throw new InvalidDataException("El plan necesita una fecha de inicio para validar su carencia.");
        }
        LocalDate ends = waitingEnds(plan.getStartDate(), parameters.waitingDays());
        long remaining = ChronoUnit.DAYS.between(today(), ends);
        if (remaining > 0) {
            throw new WaitingPeriodException(remaining);
        }
    }

    // ---------- reglas ----------

    /** "PL-0012": mismo formato que la pantalla de Clientes. */
    public static String code(Long planId) {
        return "PL-%04d".formatted(planId);
    }

    /** RN-08: día en que termina la carencia. */
    public static LocalDate waitingEnds(LocalDate startDate, int waitingDays) {
        return startDate == null ? null : startDate.plusDays(waitingDays);
    }

    /** Día de vencimiento de la cuota de un mes: el mismo día del inicio del plan (o el último del mes). */
    public static LocalDate dueDate(LocalDate startDate, YearMonth month) {
        return month.atDay(Math.min(startDate.getDayOfMonth(), month.lengthOfMonth()));
    }

    /** Cuotas vencidas a hoy desde el mes de inicio (incluye la del mes si ya pasó su día). */
    public static long dueMonths(LocalDate startDate, LocalDate today) {
        if (startDate == null || today.isBefore(startDate)) {
            return 0;
        }
        YearMonth start = YearMonth.from(startDate);
        YearMonth current = YearMonth.from(today);
        long months = ChronoUnit.MONTHS.between(start, current);
        return months + (today.isBefore(dueDate(startDate, current)) ? 0 : 1);
    }

    /**
     * RN-09 y RN-10: el beneficiario número {@code position} paga recargo si su parentesco no es
     * directo, y otro si supera el tope. Un plan empresarial no tiene tope (afilia a sus empleados).
     */
    SurchargePreview surcharge(long position, int limit, boolean corporate, Relationship relationship) {
        if (relationship == null) {
            throw new InvalidDataException("Seleccione el parentesco con el titular.");
        }
        boolean direct = relationship.isDirect();
        BigDecimal nonDirect = direct ? BigDecimal.ZERO : parameters.nonDirectSurcharge();
        BigDecimal extra = !corporate && position > limit ? parameters.extraBeneficiarySurcharge() : BigDecimal.ZERO;
        return new SurchargePreview(position, limit, corporate, direct, nonDirect, extra);
    }

    // ---------- piezas ----------

    private PlanCard card(PlanCardRow row, List<String> initials, int waitingDays, LocalDate today,
                          long currentMonthPayments) {
        LocalDate ends = waitingEnds(row.getStartDate(), waitingDays);
        long daysLeft = ends == null || !today.isBefore(ends) ? 0 : ChronoUnit.DAYS.between(today, ends);
        long due = dueMonths(row.getStartDate(), today);
        boolean beforeCurrentDue = row.getStartDate() != null
                && today.isBefore(dueDate(row.getStartDate(), YearMonth.from(today)));
        long paidDueMonths = row.getPaidMonths() - (beforeCurrentDue ? currentMonthPayments : 0);
        long overdue = row.getStatus() == PlanStatus.ACTIVE
                ? Math.max(0, due - paidDueMonths) : 0;
        BigDecimal fee = row.getMonthlyFee() == null ? BigDecimal.ZERO : row.getMonthlyFee();
        String holder = row.getHolderLastname() == null || row.getHolderLastname().isBlank()
                ? row.getHolderName() : row.getHolderName() + " " + row.getHolderLastname();
        String document = row.getHolderDocument() == null ? null
                : (row.getHolderDocumentType() == null ? "" : row.getHolderDocumentType() + " ") + row.getHolderDocument();
        String name = row.getPlanName() == null || row.getPlanName().isBlank() ? "Plan exequial" : row.getPlanName();
        return new PlanCard(row.getPlanId(), code(row.getPlanId()), row.getClientId(), holder, document,
                row.getHolderType() == ClientType.CORPORATE, name, fee, row.getStartDate(), row.getStatus(),
                row.getBeneficiaries(), initials.size() > 4 ? initials.subList(0, 4) : initials, ends, daysLeft,
                waitingDays, overdue, fee.multiply(BigDecimal.valueOf(overdue)),
                row.getLastPaidAt(), row.getServicesUsed());
    }

    private static MonthState monthState(LocalDate startDate, YearMonth month, PlanPayment payment, LocalDate today) {
        if (startDate == null || month.isBefore(YearMonth.from(startDate))) {
            return MonthState.BEFORE_START;
        }
        LocalDate due = dueDate(startDate, month);
        if (payment != null) {
            boolean late = payment.getPaidAt() != null && payment.getPaidAt().toLocalDate().isAfter(due);
            return late ? MonthState.PAID_LATE : MonthState.PAID_ON_TIME;
        }
        return today.isBefore(due) ? MonthState.UPCOMING : MonthState.UNPAID;
    }

    /** Lo esperado de un mes: las cuotas de los planes activos que ya habían empezado. */
    private static BigDecimal expected(List<PlanCard> cards, YearMonth month) {
        return cards.stream()
                .filter(c -> c.isActive() && c.startDate() != null && !YearMonth.from(c.startDate()).isAfter(month))
                .map(card -> card.monthlyFee())
                .reduce(BigDecimal.ZERO, (total, amount) -> total.add(amount));
    }

    /** Las 3 clases de plan más comunes y el resto en "Otros". */
    private static List<TypeSlice> types(List<PlanCard> active) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (PlanCard card : active) {
            counts.merge(card.planName(), 1L, (total, increment) -> total + increment);
        }
        List<Map.Entry<String, Long>> sorted = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .toList();
        List<TypeSlice> slices = new ArrayList<>();
        long others = 0;
        for (int i = 0; i < sorted.size(); i++) {
            if (i < 3) {
                slices.add(new TypeSlice(sorted.get(i).getKey(), sorted.get(i).getValue()));
            } else {
                others += sorted.get(i).getValue();
            }
        }
        if (others > 0) {
            slices.add(new TypeSlice("Otros", others));
        }
        return slices;
    }

    private static List<String> planNames(List<PlanCard> cards) {
        return cards.stream().map(card -> card.planName()).distinct().sorted().toList();
    }

    private void changeStatus(Long planId, PlanStatus from, PlanStatus to, String action, String error) {
        requireAccess();
        Long actorId = currentUserId();
        FuneralPlan plan = getPlan(planId);
        if (plan.getStatus() != from) {
            throw new InvalidDataException(error);
        }
        plan.setStatus(to);
        planRepository.save(plan);
        accessLogService.record(actorId, action + code(planId));
    }

    private void requireNoOtherActivePlan(Client client, Long exceptPlanId) {
        planRepository.findByClientIdAndStatus(client.getId(), PlanStatus.ACTIVE).stream()
                .filter(p -> !p.getId().equals(exceptPlanId))
                .findFirst()
                .ifPresent(p -> {
                    throw new InvalidDataException("El cliente ya tiene un plan activo. Un titular tiene un solo plan activo.");
                });
    }

    private static void validateAmount(BigDecimal amount, String what) {
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidDataException(what + " debe ser mayor que cero.");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new InvalidDataException(what + " admite máximo dos decimales.");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new InvalidDataException(what + " supera el máximo permitido.");
        }
    }

    private static String surchargeReason(SurchargePreview surcharge) {
        List<String> reasons = new ArrayList<>();
        if (surcharge.nonDirect().signum() > 0) {
            reasons.add("parentesco no directo");
        }
        if (surcharge.extra().signum() > 0) {
            reasons.add("supera el tope de " + surcharge.limit());
        }
        return reasons.isEmpty() ? null : String.join(" y ", reasons);
    }

    /** Acepta códigos y etiquetas del catálogo; no asigna un recargo a un parentesco desconocido. */
    private static Relationship relationshipOf(String value) {
        Relationship code = Relationship.fromCode(value == null ? null : value.trim().toUpperCase(Locale.ROOT));
        if (code != null) {
            return code;
        }
        for (Relationship relationship : Relationship.values()) {
            if (normalize(relationship.getDisplayName()).equals(normalize(value))) {
                return relationship;
            }
        }
        throw new InvalidDataException("Hay un beneficiario con parentesco sin reconocer. Revise sus datos antes de calcular el recargo.");
    }

    private static BigDecimal totalOf(MonthTotal total) {
        return total == null || total.getTotal() == null ? BigDecimal.ZERO : total.getTotal();
    }

    /** "octubre 2026". */
    public static String monthName(YearMonth month) {
        return MONTH.format(month);
    }

    private static String required(String value, String what, int max) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) {
            throw new InvalidDataException("Ingrese " + what + " del beneficiario.");
        }
        if (clean.length() > max) {
            throw new InvalidDataException("El campo " + what + " admite máximo " + max + " caracteres.");
        }
        return clean;
    }

    private static String initials(String name, String lastname) {
        String first = name == null || name.isBlank() ? "" : name.trim().substring(0, 1);
        String second = lastname == null || lastname.isBlank() ? "" : lastname.trim().substring(0, 1);
        return (first + second).toUpperCase(ES);
    }

    private static String fullName(String name, String lastname) {
        return lastname == null || lastname.isBlank() ? name : name + " " + lastname;
    }

    private static String document(Client client) {
        String type = client.getDocumentType() == null ? "" : client.getDocumentType() + " ";
        return client.getDocumentNumber() == null ? null : type + client.getDocumentNumber();
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    /** Minúsculas y sin tildes, para que "duran" encuentre "Durán". */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return java.text.Normalizer.normalize(text.trim().toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    private static String pattern(String text) {
        return "%" + (text == null ? "" : text.trim().toLowerCase(Locale.ROOT)) + "%";
    }

    private FuneralPlan getPlan(Long id) {
        return planRepository.findDetailById(id).orElseThrow(() -> new ResourceNotFoundException("Plan", id));
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private void requireAccess() {
        currentUserId();
        boolean allowed = sessionContext.getRoleName()
                .map(AppModule.PLANES::isAllowedFor)
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

    /** Fila de tarjeta armada desde la entidad, para el panel de detalle. */
    private record SimpleCardRow(FuneralPlan plan, long beneficiaryCount, long usedCount, long paidMonths,
                                 LocalDateTime lastPaidAt) implements PlanCardRow {
        public Long getPlanId() {
            return plan.getId();
        }

        public Long getClientId() {
            return plan.getClient().getId();
        }

        public String getHolderName() {
            return plan.getClient().getName();
        }

        public String getHolderLastname() {
            return plan.getClient().getLastname();
        }

        public String getHolderDocumentType() {
            return plan.getClient().getDocumentType();
        }

        public String getHolderDocument() {
            return plan.getClient().getDocumentNumber();
        }

        public ClientType getHolderType() {
            return plan.getClient().getClientType();
        }

        public String getPlanName() {
            return plan.getPlanName();
        }

        public BigDecimal getMonthlyFee() {
            return plan.getMonthlyFee();
        }

        public LocalDate getStartDate() {
            return plan.getStartDate();
        }

        public PlanStatus getStatus() {
            return plan.getStatus();
        }

        public long getBeneficiaries() {
            return beneficiaryCount;
        }

        public long getServicesUsed() {
            return usedCount;
        }

        public long getPaidMonths() {
            return paidMonths;
        }

        public LocalDateTime getLastPaidAt() {
            return lastPaidAt;
        }
    }
}
