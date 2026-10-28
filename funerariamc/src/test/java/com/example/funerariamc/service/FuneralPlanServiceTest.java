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
import com.example.funerariamc.model.Manager;
import com.example.funerariamc.model.PlanPayment;
import com.example.funerariamc.model.Role;
import com.example.funerariamc.model.enums.ClientType;
import com.example.funerariamc.model.enums.PlanPaymentStatus;
import com.example.funerariamc.model.enums.PlanStatus;
import com.example.funerariamc.model.enums.Relationship;
import com.example.funerariamc.model.enums.TransactionStatus;
import com.example.funerariamc.model.enums.TransactionType;
import com.example.funerariamc.persistence.BeneficiaryRepository;
import com.example.funerariamc.persistence.ClientRepository;
import com.example.funerariamc.persistence.FinancialTransactionRepository;
import com.example.funerariamc.persistence.FuneralPlanRepository;
import com.example.funerariamc.persistence.FuneralPlanRepository.PlanCardRow;
import com.example.funerariamc.persistence.FuneralPlanRepository.UsedServiceRow;
import com.example.funerariamc.persistence.PlanPaymentRepository;
import com.example.funerariamc.persistence.PlanPaymentRepository.MonthTotal;
import com.example.funerariamc.service.FuneralPlanService.SurchargePreview;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Example;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Criterios de aceptación de RF-PLA-01 a RF-PLA-04 y del registro de cuotas (plan_payment). */
class FuneralPlanServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);
    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    private final FuneralPlanRepository plans = mock(FuneralPlanRepository.class);
    private final BeneficiaryRepository beneficiaries = mock(BeneficiaryRepository.class);
    private final PlanPaymentRepository payments = mock(PlanPaymentRepository.class);
    private final FinancialTransactionRepository transactions = mock(FinancialTransactionRepository.class);
    private final ClientRepository clients = mock(ClientRepository.class);
    private final PlanParameters parameters = mock(PlanParameters.class);
    private final AccessLogService accessLog = mock(AccessLogService.class);
    private final SessionContext session = new SessionContext();
    private final FuneralPlanService service = new FuneralPlanService(plans, beneficiaries, payments, transactions,
            clients, parameters, accessLog, session,
            Clock.fixed(TODAY.atTime(10, 0).atZone(BOGOTA).toInstant(), BOGOTA));

    @BeforeEach
    void setUp() {
        loginAs(RoleNames.SALESPERSON);
        when(parameters.waitingDays()).thenReturn(62);
        when(parameters.waitingSoonDays()).thenReturn(30);
        when(parameters.maxMonthsAhead()).thenReturn(12);
        when(parameters.maxStartMonthsAhead()).thenReturn(1);
        when(parameters.beneficiaryLimit()).thenReturn(7);
        when(parameters.nonDirectSurcharge()).thenReturn(new BigDecimal("150000"));
        when(parameters.extraBeneficiarySurcharge()).thenReturn(new BigDecimal("200000"));
        when(plans.save(any())).thenAnswer(invocation -> {
            FuneralPlan plan = invocation.getArgument(0);
            if (plan.getId() == null) {
                plan.setId(412L);
            }
            return plan;
        });
        when(beneficiaries.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(payments.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ---------- afiliación (RF-PLA-01) ----------

    @Test
    void registerCreatesAnActivePlanAndLogsItsCode() {
        when(clients.findById(40L)).thenReturn(Optional.of(client(40L, "49786112", ClientType.NATURAL)));
        when(plans.findByClientIdAndStatus(40L, PlanStatus.ACTIVE)).thenReturn(List.of());

        FuneralPlan saved = service.register(new PlanData(40L, " Plan Familiar ", new BigDecimal("52000"), TODAY));

        assertEquals(PlanStatus.ACTIVE, saved.getStatus());
        assertEquals("Plan Familiar", saved.getPlanName());
        verify(accessLog).record(5L, "PLAN_REGISTERED: PL-0412");
    }

    @Test
    void registerRejectsAHolderThatAlreadyHasAnActivePlan() {
        Client holder = client(40L, "49786112", ClientType.NATURAL);
        when(clients.findById(40L)).thenReturn(Optional.of(holder));
        when(plans.findByClientIdAndStatus(40L, PlanStatus.ACTIVE)).thenReturn(List.of(plan(300L, holder, TODAY)));

        assertThrows(InvalidDataException.class,
                () -> service.register(new PlanData(40L, "Plan Familiar", new BigDecimal("52000"), TODAY)));
        verify(plans, never()).save(any());
    }

    @Test
    void registerValidatesFeeAndStartDate() {
        assertThrows(InvalidDataException.class,
                () -> service.register(new PlanData(40L, "Plan", BigDecimal.ZERO, TODAY)));
        assertThrows(InvalidDataException.class,
                () -> service.register(new PlanData(40L, "Plan", new BigDecimal("52000"), TODAY.plusMonths(2))));
    }

    @Test
    void cemeteryAdminCannotUsePlans() {
        loginAs(RoleNames.CEMETERY_ADMIN);
        assertThrows(UnauthorizedActionException.class, service::overview);
    }

    // ---------- beneficiarios (RF-PLA-02, RN-09, RN-10) ----------

    @Test
    void directRelativeWithinTheLimitHasNoSurcharge() {
        SurchargePreview preview = service.surcharge(3, 7, false, Relationship.CHILD);
        assertFalse(preview.applies());
    }

    @Test
    void nonDirectRelativeAndExtraBeneficiaryAddBothSurcharges() {
        SurchargePreview preview = service.surcharge(8, 7, false, Relationship.SIBLING);
        assertEquals(new BigDecimal("350000"), preview.total());
    }

    @Test
    void corporatePlanHasNoBeneficiaryLimit() {
        SurchargePreview preview = service.surcharge(30, 7, true, Relationship.EMPLOYEE);
        assertFalse(preview.applies());
    }

    @Test
    void eighthBeneficiaryNeedsTheSurchargeAccepted() {
        FuneralPlan plan = plan(412L, client(40L, "49786112", ClientType.NATURAL), TODAY.minusYears(1));
        when(plans.findDetailById(412L)).thenReturn(Optional.of(plan));
        when(beneficiaries.countByFuneralPlanId(412L)).thenReturn(7L);

        assertThrows(BeneficiaryLimitExceededException.class, () -> service.addBeneficiary(
                new BeneficiaryData(412L, "Tomás", "Pinto", "1065223901", Relationship.CHILD, false)));

        Beneficiary saved = service.addBeneficiary(
                new BeneficiaryData(412L, "Tomás", "Pinto", "1065223901", Relationship.CHILD, true));
        assertEquals("CHILD", saved.getRelationship());
        verify(accessLog).record(5L, "BENEFICIARY_ADDED: PL-0412 1065223901");
    }

    @Test
    void holderDocumentCannotBeABeneficiary() {
        FuneralPlan plan = plan(412L, client(40L, "49786112", ClientType.NATURAL), TODAY.minusYears(1));
        when(plans.findDetailById(412L)).thenReturn(Optional.of(plan));

        assertThrows(InvalidDataException.class, () -> service.addBeneficiary(
                new BeneficiaryData(412L, "Martha", "Durán", "49786112", Relationship.SPOUSE, false)));
        verify(beneficiaries, never()).save(any());
    }

    // ---------- cuotas (plan_payment) ----------

    @Test
    void registerPaymentSavesTheIncomeAndTheMonthlyPayment() {
        FuneralPlan plan = plan(412L, client(40L, "49786112", ClientType.NATURAL), LocalDate.of(2026, 3, 5));
        when(plans.findDetailById(412L)).thenReturn(Optional.of(plan));

        PlanPayment saved = service.registerPayment(
                new PlanPaymentData(412L, YearMonth.of(2026, 10), new BigDecimal("52000")));

        ArgumentCaptor<FinancialTransaction> income = ArgumentCaptor.forClass(FinancialTransaction.class);
        verify(transactions).save(income.capture());
        assertEquals(TransactionType.INCOME, income.getValue().getTransactionType());
        assertEquals(TransactionStatus.ACTIVE, income.getValue().getStatus());
        assertEquals(LocalDate.of(2026, 10, 1), saved.getPeriodMonth());
        assertEquals(PlanPaymentStatus.PAID, saved.getStatus());
        assertSame(income.getValue(), saved.getFinancialTransaction());
        verify(accessLog).record(5L, "PLAN_PAYMENT_REGISTERED: PL-0412 2026-10");
    }

    @Test
    void aMonthCanOnlyBePaidOnceAndNotBeforeTheStart() {
        FuneralPlan plan = plan(412L, client(40L, "49786112", ClientType.NATURAL), LocalDate.of(2026, 3, 5));
        when(plans.findDetailById(412L)).thenReturn(Optional.of(plan));
        when(payments.existsByFuneralPlanIdAndPeriodMonthAndStatus(412L, LocalDate.of(2026, 9, 1), PlanPaymentStatus.PAID))
                .thenReturn(true);

        assertThrows(InvalidDataException.class, () -> service.registerPayment(
                new PlanPaymentData(412L, YearMonth.of(2026, 9), new BigDecimal("52000"))));
        assertThrows(InvalidDataException.class, () -> service.registerPayment(
                new PlanPaymentData(412L, YearMonth.of(2026, 2), new BigDecimal("52000"))));
        verify(payments, never()).save(any());
    }

    @Test
    void voidPaymentMarksPaymentAndIncomeAsVoidedWithoutDeleting() {
        FuneralPlan plan = plan(412L, client(40L, "49786112", ClientType.NATURAL), LocalDate.of(2026, 3, 5));
        FinancialTransaction income = new FinancialTransaction();
        income.setStatus(TransactionStatus.ACTIVE);
        PlanPayment payment = new PlanPayment();
        payment.setId(9L);
        payment.setFuneralPlan(plan);
        payment.setPeriodMonth(LocalDate.of(2026, 9, 1));
        payment.setStatus(PlanPaymentStatus.PAID);
        payment.setFinancialTransaction(income);
        when(payments.findDetailById(9L)).thenReturn(Optional.of(payment));

        service.voidPayment(9L);

        assertEquals(PlanPaymentStatus.VOIDED, payment.getStatus());
        assertEquals(TransactionStatus.VOIDED, income.getStatus());
        verify(payments, never()).delete(any());
        verify(accessLog).record(5L, "PLAN_PAYMENT_VOIDED: PL-0412 2026-09");
    }

    // ---------- carencia y mora ----------

    @Test
    void waitingPeriodEndsSixtyTwoDaysAfterTheStart() {
        assertEquals(LocalDate.of(2026, 3, 4), FuneralPlanService.waitingEnds(LocalDate.of(2026, 1, 1), 62));
    }

    @Test
    void dueMonthsCountsTheCurrentMonthOnlyAfterItsDueDay() {
        LocalDate start = LocalDate.of(2026, 8, 28);
        assertEquals(2, FuneralPlanService.dueMonths(start, TODAY)); // agosto y septiembre; octubre vence el 28
        assertEquals(3, FuneralPlanService.dueMonths(start, LocalDate.of(2026, 10, 28)));
    }

    @Test
    void waitingPeriodRejectsThirtyElapsedDaysAndReportsThirtyTwoRemaining() {
        FuneralPlan plan = plan(412L, client(40L, "49786112", ClientType.NATURAL), TODAY.minusDays(30));
        when(plans.findDetailById(412L)).thenReturn(Optional.of(plan));

        WaitingPeriodException error = assertThrows(WaitingPeriodException.class,
                () -> service.validateBenefitsAvailable(412L));

        assertEquals(32, error.getDaysRemaining());
        verifyNoInteractions(accessLog, transactions, beneficiaries, payments);
    }

    @ParameterizedTest
    @ValueSource(ints = {62, 90})
    void waitingPeriodAllowsBenefitsOnOrAfterTheConfiguredBoundary(int elapsed) {
        when(plans.findDetailById(412L)).thenReturn(Optional.of(
                plan(412L, client(40L, "49786112", ClientType.NATURAL), TODAY.minusDays(elapsed))));
        assertDoesNotThrow(() -> service.validateBenefitsAvailable(412L));
    }

    @Test
    void changingTheWaitingParameterChangesCoverage() {
        when(parameters.waitingDays()).thenReturn(31);
        when(plans.findDetailById(412L)).thenReturn(Optional.of(
                plan(412L, client(40L, "49786112", ClientType.NATURAL), TODAY.minusDays(30))));
        WaitingPeriodException error = assertThrows(WaitingPeriodException.class,
                () -> service.validateBenefitsAvailable(412L));
        assertEquals(1, error.getDaysRemaining());
    }

    @ParameterizedTest
    @EnumSource(value = PlanStatus.class, names = {"SUSPENDED", "CANCELLED"})
    void inactivePlansCannotUseBenefits(PlanStatus status) {
        FuneralPlan plan = plan(412L, client(40L, "49786112", ClientType.NATURAL), TODAY.minusYears(1));
        plan.setStatus(status);
        when(plans.findDetailById(412L)).thenReturn(Optional.of(plan));
        assertThrows(InvalidDataException.class, () -> service.validateBenefitsAvailable(412L));
    }

    @Test
    void missingStartDateDoesNotEnableBenefits() {
        when(plans.findDetailById(412L)).thenReturn(Optional.of(
                plan(412L, client(40L, "49786112", ClientType.NATURAL), null)));
        assertThrows(InvalidDataException.class, () -> service.validateBenefitsAvailable(412L));
    }

    @Test
    void missingWaitingParameterIsReportedWithoutEnablingCoverage() {
        when(plans.findDetailById(412L)).thenReturn(Optional.of(
                plan(412L, client(40L, "49786112", ClientType.NATURAL), TODAY.minusYears(1))));
        InvalidDataException missing = new InvalidDataException("Falta configuración");
        when(parameters.waitingDays()).thenThrow(missing);
        assertSame(missing, assertThrows(InvalidDataException.class,
                () -> service.validateBenefitsAvailable(412L)));
    }

    @Test
    void missingPlanIsReportedWithItsBusinessException() {
        assertThrows(ResourceNotFoundException.class, () -> service.detail(999L));
    }

    @Test
    void everyOperationRejectsAnUnauthorizedRoleBeforeReadingRepositories() {
        loginAs(RoleNames.CEMETERY_ADMIN);
        List<Executable> operations = List.of(
                () -> service.overview(), () -> service.detail(412L),
                () -> service.searchClients("Martha"),
                () -> service.previewSurcharge(412L, Relationship.CHILD),
                () -> service.register(null), () -> service.addBeneficiary(null),
                () -> service.registerPayment(null), () -> service.voidPayment(9L),
                () -> service.suspend(412L), () -> service.reactivate(412L),
                () -> service.cancel(412L), () -> service.changeHolder(412L, 41L),
                () -> service.validateBenefitsAvailable(412L));
        for (Executable operation : operations) {
            assertThrows(UnauthorizedActionException.class, operation);
        }
        verifyNoInteractions(plans, beneficiaries, payments, transactions, clients, parameters, accessLog);
    }

    @Test
    void aSessionWithoutAPersistedUserCannotReadOrChangePlans() {
        session.getCurrentUser().orElseThrow().setId(null);
        assertThrows(UnauthorizedActionException.class, service::overview);
        assertThrows(UnauthorizedActionException.class, () -> service.registerPayment(null));
        verifyNoInteractions(plans, beneficiaries, payments, transactions, clients, parameters, accessLog);
    }

    @Test
    void anExpiredSessionCannotReactivatePlans() {
        session.clear();
        assertThrows(UnauthorizedActionException.class, () -> service.reactivate(412L));
        verifyNoInteractions(plans, clients, accessLog);
    }

    @Test
    void paymentRequiresAPersistedEmployeeBeforeAnyWrite() {
        session.getCurrentUser().orElseThrow().getEmployee().setId(null);
        assertThrows(UnauthorizedActionException.class, () -> service.registerPayment(
                new PlanPaymentData(412L, YearMonth.from(TODAY), new BigDecimal("52000"))));
        verifyNoInteractions(plans, payments, transactions, accessLog);
    }

    @Test
    void overviewUsesThreeBatchedReadsAndThePaidMonthsAlreadyInTheCard() {
        PlanCardRow row = mock(PlanCardRow.class);
        when(row.getPlanId()).thenReturn(412L);
        when(row.getClientId()).thenReturn(40L);
        when(row.getHolderName()).thenReturn("Martha");
        when(row.getHolderType()).thenReturn(ClientType.NATURAL);
        when(row.getMonthlyFee()).thenReturn(new BigDecimal("52000"));
        when(row.getStartDate()).thenReturn(TODAY.minusMonths(2));
        when(row.getStatus()).thenReturn(PlanStatus.ACTIVE);
        when(row.getPaidMonths()).thenReturn(2L);
        when(plans.findCards(PlanPaymentStatus.PAID, TODAY.withDayOfMonth(1))).thenReturn(List.of(row));
        BeneficiaryRepository.BeneficiaryNameRow name = mock(BeneficiaryRepository.BeneficiaryNameRow.class);
        when(name.getPlanId()).thenReturn(412L);
        when(name.getName()).thenReturn("Tomás");
        when(name.getLastname()).thenReturn("Pinto");
        when(beneficiaries.findNames(List.of(412L))).thenReturn(List.of(name));
        MonthTotal total = mock(MonthTotal.class);
        when(total.getPeriodMonth()).thenReturn(TODAY.withDayOfMonth(1));
        when(total.getTotal()).thenReturn(new BigDecimal("104000"));
        when(total.getPayments()).thenReturn(2L);
        when(payments.totalsByMonth(PlanPaymentStatus.PAID, TODAY.withDayOfMonth(1).minusMonths(5),
                TODAY.withDayOfMonth(1))).thenReturn(List.of(total));

        var overview = service.overview();

        assertEquals(62, overview.waitingDays());
        assertEquals(1, overview.kpis().activePlans());
        assertEquals(1, overview.kpis().overduePlans());
        assertEquals(new BigDecimal("52000"), overview.kpis().overdueAmount());
        assertEquals(new BigDecimal("104000"), overview.kpis().collectedThisMonth());
        assertEquals(2, overview.kpis().paidThisMonth());
        assertEquals(6, overview.collection().size());
        assertEquals(List.of("TP"), overview.cards().getFirst().initials());
        assertEquals(1, overview.waitingSoon().size());
        verify(plans).findCards(PlanPaymentStatus.PAID, TODAY.withDayOfMonth(1));
        verify(beneficiaries).findNames(List.of(412L));
        verify(payments).totalsByMonth(PlanPaymentStatus.PAID, TODAY.withDayOfMonth(1).minusMonths(5),
                TODAY.withDayOfMonth(1));
        verifyNoMoreInteractions(plans, beneficiaries, payments);
        verifyNoInteractions(clients, transactions, accessLog);
    }

    @Test
    void emptyOverviewSkipsTheBeneficiaryQuery() {
        var overview = service.overview();
        assertTrue(overview.cards().isEmpty());
        assertEquals(0, overview.kpis().activePlans());
        assertEquals(BigDecimal.ZERO, overview.kpis().collectedThisMonth());
        verifyNoInteractions(beneficiaries);
    }

    @Test
    void detailUsesFourBatchedReadsAndKeepsVoidedPaymentsOutOfTheGrid() {
        FuneralPlan plan = plan(412L, client(40L, "49786112", ClientType.NATURAL), TODAY.minusMonths(4));
        when(plans.findDetailById(412L)).thenReturn(Optional.of(plan));
        Beneficiary beneficiary = new Beneficiary();
        beneficiary.setId(7L);
        beneficiary.setName("Tomás");
        beneficiary.setLastname("Pinto");
        beneficiary.setDocumentNumber("1065223901");
        beneficiary.setRelationship("CHILD");
        when(beneficiaries.findByFuneralPlanIdOrderByIdAsc(412L)).thenReturn(List.of(beneficiary));
        UsedServiceRow used = mock(UsedServiceRow.class);
        when(used.getDeceasedId()).thenReturn(80L);
        when(used.getDocumentNumber()).thenReturn("1065223901");
        when(used.getName()).thenReturn("Tomás");
        when(used.getLastname()).thenReturn("Pinto");
        when(used.getDeathDate()).thenReturn(TODAY.minusDays(10));
        when(used.getContractNumber()).thenReturn("CT-80");
        when(used.getWakeRoomName()).thenReturn("Sala A");
        when(plans.findUsedServices(412L)).thenReturn(List.of(used));
        PlanPayment paid = new PlanPayment();
        paid.setId(9L);
        paid.setPeriodMonth(TODAY.minusMonths(1).withDayOfMonth(1));
        paid.setPaidAt(TODAY.minusMonths(1).plusDays(1).atTime(10, 0));
        paid.setAmount(new BigDecimal("52000"));
        paid.setStatus(PlanPaymentStatus.PAID);
        Manager employee = new Manager();
        employee.setName("Luisa");
        employee.setLastname("Ríos");
        paid.setRegisteredByEmployee(employee);
        PlanPayment voided = new PlanPayment();
        voided.setId(10L);
        voided.setPeriodMonth(TODAY.minusMonths(2).withDayOfMonth(1));
        voided.setAmount(new BigDecimal("52000"));
        voided.setStatus(PlanPaymentStatus.VOIDED);
        when(payments.findHistory(412L)).thenReturn(List.of(paid, voided));

        var detail = service.detail(412L);

        assertEquals(12, detail.grid().size());
        assertEquals(FuneralPlanService.MonthState.PAID_LATE, detail.grid().stream()
                .filter(cell -> cell.month().equals(YearMonth.from(TODAY.minusMonths(1))))
                .findFirst().orElseThrow().state());
        assertEquals(FuneralPlanService.MonthState.UNPAID, detail.grid().stream()
                .filter(cell -> cell.month().equals(YearMonth.from(TODAY.minusMonths(2))))
                .findFirst().orElseThrow().state());
        assertEquals(2, detail.payments().size());
        assertEquals("Luisa Ríos", detail.payments().getFirst().registeredBy());
        assertEquals(80L, detail.beneficiaries().getFirst().deceasedId());
        assertEquals("CT-80", detail.usedServices().getFirst().contractNumber());
        assertEquals("Sala A", detail.usedServices().getFirst().wakeRoom());
        assertEquals(BigDecimal.ZERO, detail.totalSurcharge());
        verify(plans).findDetailById(412L);
        verify(plans).findUsedServices(412L);
        verify(beneficiaries).findByFuneralPlanIdOrderByIdAsc(412L);
        verify(payments).findHistory(412L);
        verifyNoMoreInteractions(plans, beneficiaries, payments);
        verifyNoInteractions(clients, transactions, accessLog);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CHILD", " child ", "Hijo(a)"})
    void detailRecognizesCodesAndLabelsAlreadyInTheRelationshipCatalog(String relationship) {
        when(plans.findDetailById(412L)).thenReturn(Optional.of(
                plan(412L, client(40L, "49786112", ClientType.NATURAL), TODAY.minusYears(1))));
        Beneficiary beneficiary = new Beneficiary();
        beneficiary.setRelationship(relationship);
        when(beneficiaries.findByFuneralPlanIdOrderByIdAsc(412L)).thenReturn(List.of(beneficiary));
        var detail = service.detail(412L);
        assertEquals(Relationship.CHILD, detail.beneficiaries().getFirst().relationship());
        assertEquals("Hijo(a)", detail.beneficiaries().getFirst().relationshipLabel());
        assertEquals(BigDecimal.ZERO, detail.totalSurcharge());
    }

    @Test
    void unknownRelationshipIsNotSilentlyTreatedAsDirect() {
        when(plans.findDetailById(412L)).thenReturn(Optional.of(
                plan(412L, client(40L, "49786112", ClientType.NATURAL), TODAY.minusYears(1))));
        Beneficiary beneficiary = new Beneficiary();
        beneficiary.setRelationship("LEGACY_UNKNOWN");
        when(beneficiaries.findByFuneralPlanIdOrderByIdAsc(412L)).thenReturn(List.of(beneficiary));
        assertThrows(InvalidDataException.class, () -> service.detail(412L));
    }

    @Test
    void previewRejectsMissingRelationshipBeforeReadingThePlan() {
        assertThrows(InvalidDataException.class, () -> service.previewSurcharge(412L, null));
        verifyNoInteractions(plans, beneficiaries, parameters);
    }

    @ParameterizedTest
    @EnumSource(value = PlanStatus.class, names = {"SUSPENDED", "CANCELLED"})
    void previewDoesNotOfferBeneficiariesForInactivePlans(PlanStatus status) {
        FuneralPlan plan = plan(412L, client(40L, "49786112", ClientType.NATURAL), TODAY);
        plan.setStatus(status);
        when(plans.findDetailById(412L)).thenReturn(Optional.of(plan));
        assertThrows(InvalidDataException.class, () -> service.previewSurcharge(412L, Relationship.CHILD));
        verifyNoInteractions(beneficiaries);
    }

    @Test
    void duplicateBeneficiaryErrorDoesNotExposeTheDocument() {
        when(plans.findDetailById(412L)).thenReturn(Optional.of(
                plan(412L, client(40L, "49786112", ClientType.NATURAL), TODAY)));
        when(beneficiaries.existsByFuneralPlanIdAndDocumentNumber(412L, "1065223901")).thenReturn(true);
        InvalidDataException error = assertThrows(InvalidDataException.class, () -> service.addBeneficiary(
                new BeneficiaryData(412L, "Tomás", "Pinto", "1065223901", Relationship.CHILD, true)));
        assertFalse(error.getMessage().contains("1065223901"));
        verify(beneficiaries, never()).save(any());
    }

    @Test
    void existingActivePlanErrorDoesNotExposeTheHolderName() {
        Client holder = client(40L, "49786112", ClientType.NATURAL);
        when(clients.findById(40L)).thenReturn(Optional.of(holder));
        when(plans.findByClientIdAndStatus(40L, PlanStatus.ACTIVE))
                .thenReturn(List.of(plan(300L, holder, TODAY)));
        InvalidDataException error = assertThrows(InvalidDataException.class, () -> service.register(
                new PlanData(40L, "Plan", new BigDecimal("52000"), TODAY)));
        assertFalse(error.getMessage().contains(holder.getName()));
        assertFalse(error.getMessage().contains(holder.getDocumentNumber()));
    }

    @Test
    void reactivationRejectsAnotherActivePlanWithoutChangingTheSuspendedPlan() {
        Client holder = client(40L, "49786112", ClientType.NATURAL);
        FuneralPlan suspended = plan(412L, holder, TODAY.minusYears(1));
        suspended.setStatus(PlanStatus.SUSPENDED);
        when(plans.findDetailById(412L)).thenReturn(Optional.of(suspended));
        when(plans.findByClientIdAndStatus(40L, PlanStatus.ACTIVE))
                .thenReturn(List.of(plan(300L, holder, TODAY.minusMonths(1))));
        assertThrows(InvalidDataException.class, () -> service.reactivate(412L));
        assertEquals(PlanStatus.SUSPENDED, suspended.getStatus());
        verify(plans, never()).save(any());
        verifyNoInteractions(accessLog);
    }

    @Test
    void suspendedPlanCanBeReactivatedWithoutReplacingItsHistory() {
        FuneralPlan plan = plan(412L, client(40L, "49786112", ClientType.NATURAL), TODAY.minusYears(1));
        plan.setStatus(PlanStatus.SUSPENDED);
        when(plans.findDetailById(412L)).thenReturn(Optional.of(plan));
        service.reactivate(412L);
        assertEquals(PlanStatus.ACTIVE, plan.getStatus());
        verify(plans, times(1)).findDetailById(412L);
        verify(plans).save(plan);
        verify(accessLog).record(5L, "PLAN_REACTIVATED: PL-0412");
        verifyNoInteractions(beneficiaries, payments, transactions);
    }

    @Test
    void cancellationKeepsThePlanAndItsPaymentAndBeneficiaryHistory() {
        FuneralPlan plan = plan(412L, client(40L, "49786112", ClientType.NATURAL), TODAY.minusYears(1));
        when(plans.findDetailById(412L)).thenReturn(Optional.of(plan));
        service.cancel(412L);
        assertEquals(PlanStatus.CANCELLED, plan.getStatus());
        verify(plans).save(plan);
        verify(plans, never()).delete(any());
        verify(accessLog).record(5L, "PLAN_CANCELLED: PL-0412");
        verifyNoInteractions(beneficiaries, payments, transactions);
    }

    @Test
    void changingTheHolderKeepsThePlanIdAndItsBeneficiariesAndPayments() {
        FuneralPlan plan = plan(412L, client(40L, "49786112", ClientType.NATURAL), TODAY.minusYears(1));
        Client newHolder = client(41L, "10987654", ClientType.NATURAL);
        when(plans.findDetailById(412L)).thenReturn(Optional.of(plan));
        when(clients.findById(41L)).thenReturn(Optional.of(newHolder));
        service.changeHolder(412L, 41L);
        assertSame(newHolder, plan.getClient());
        assertEquals(412L, plan.getId());
        verify(plans).save(plan);
        verify(accessLog).record(5L, "PLAN_HOLDER_CHANGED: PL-0412 10987654");
        verifyNoInteractions(beneficiaries, payments, transactions);
    }

    @Test
    void auditFailurePropagatesInsteadOfReportingTheRegistrationAsSuccessful() {
        when(clients.findById(40L)).thenReturn(Optional.of(client(40L, "49786112", ClientType.NATURAL)));
        InvalidDataException failure = new InvalidDataException("No se pudo registrar la acción");
        doThrow(failure).when(accessLog).record(anyLong(), anyString());
        assertSame(failure, assertThrows(InvalidDataException.class, () -> service.register(
                new PlanData(40L, "Plan", new BigDecimal("52000"), TODAY))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "999999999", "52000.001"})
    void invalidPaymentAmountsDoNotWriteEitherTheIncomeOrThePayment(String value) {
        when(plans.findDetailById(412L)).thenReturn(Optional.of(
                plan(412L, client(40L, "49786112", ClientType.NATURAL), TODAY.minusYears(1))));
        assertThrows(InvalidDataException.class, () -> service.registerPayment(
                new PlanPaymentData(412L, YearMonth.from(TODAY), new BigDecimal(value))));
        verifyNoInteractions(transactions, accessLog);
        verify(payments, never()).save(any());
    }

    @Test
    void monthEndDueDatesUseTheLastExistingDay() {
        assertEquals(LocalDate.of(2026, 2, 28),
                FuneralPlanService.dueDate(LocalDate.of(2026, 1, 31), YearMonth.of(2026, 2)));
        assertEquals(LocalDate.of(2028, 2, 29),
                FuneralPlanService.dueDate(LocalDate.of(2028, 1, 31), YearMonth.of(2028, 2)));
    }

    @Test
    void payingTheCurrentMonthEarlyDoesNotHideAnOlderOverdueMonth() {
        FuneralPlan plan = plan(412L, client(40L, "49786112", ClientType.NATURAL),
                LocalDate.of(2026, 8, 28));
        PlanCardRow row = mock(PlanCardRow.class);
        when(row.getPlanId()).thenReturn(412L);
        when(row.getStartDate()).thenReturn(plan.getStartDate());
        when(row.getStatus()).thenReturn(PlanStatus.ACTIVE);
        when(row.getMonthlyFee()).thenReturn(plan.getMonthlyFee());
        when(row.getPaidMonths()).thenReturn(2L); // agosto y octubre; septiembre sigue pendiente
        when(plans.findCards(PlanPaymentStatus.PAID, TODAY.withDayOfMonth(1))).thenReturn(List.of(row));
        PlanPayment october = new PlanPayment();
        october.setFuneralPlan(plan);
        october.setPeriodMonth(TODAY.withDayOfMonth(1));
        october.setStatus(PlanPaymentStatus.PAID);
        when(payments.findAll(org.mockito.ArgumentMatchers.<Example<PlanPayment>>any()))
                .thenReturn(List.of(october));

        var overview = service.overview();

        assertEquals(1, overview.cards().getFirst().overdueMonths());
        assertEquals(new BigDecimal("52000"), overview.kpis().overdueAmount());
        verify(payments).findAll(argThat((Example<PlanPayment> example) ->
                example.getProbe().getStatus() == PlanPaymentStatus.PAID
                        && example.getProbe().getPeriodMonth().equals(TODAY.withDayOfMonth(1))
                        && example.getProbe().getFuneralPlan() == null));
    }

    @Test
    void detailAndOverviewBothExcludeAnEarlyCurrentPaymentFromOlderDebt() {
        FuneralPlan plan = plan(412L, client(40L, "49786112", ClientType.NATURAL),
                LocalDate.of(2026, 8, 28));
        when(plans.findDetailById(412L)).thenReturn(Optional.of(plan));
        PlanPayment august = new PlanPayment();
        august.setPeriodMonth(LocalDate.of(2026, 8, 1));
        august.setPaidAt(LocalDateTime.of(2026, 8, 28, 10, 0));
        august.setStatus(PlanPaymentStatus.PAID);
        PlanPayment october = new PlanPayment();
        october.setPeriodMonth(TODAY.withDayOfMonth(1));
        october.setPaidAt(TODAY.atTime(10, 0));
        october.setStatus(PlanPaymentStatus.PAID);
        when(payments.findHistory(412L)).thenReturn(List.of(october, august));

        var detail = service.detail(412L);

        assertEquals(1, detail.card().overdueMonths());
        assertEquals(new BigDecimal("52000"), detail.card().overdueAmount());
        assertEquals(FuneralPlanService.MonthState.PAID_ON_TIME, detail.grid().stream()
                .filter(cell -> cell.month().equals(YearMonth.from(TODAY)))
                .findFirst().orElseThrow().state());
        assertEquals(YearMonth.of(2026, 9), detail.nextDue());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 12})
    void paymentAdvanceLimitComesFromTheConfiguredParameter(int monthsAhead) {
        when(parameters.maxMonthsAhead()).thenReturn(monthsAhead);
        when(plans.findDetailById(412L)).thenReturn(Optional.of(
                plan(412L, client(40L, "49786112", ClientType.NATURAL), TODAY.minusYears(1))));
        YearMonth lastAllowed = YearMonth.from(TODAY).plusMonths(monthsAhead);

        PlanPayment saved = service.registerPayment(
                new PlanPaymentData(412L, lastAllowed, new BigDecimal("52000")));
        assertEquals(lastAllowed.atDay(1), saved.getPeriodMonth());
        assertThrows(InvalidDataException.class, () -> service.registerPayment(
                new PlanPaymentData(412L, lastAllowed.plusMonths(1), new BigDecimal("52000"))));
        verify(transactions, times(1)).save(any());
        verify(payments, times(1)).save(any());
    }

    @Test
    void missingAdvancePaymentParameterDoesNotCreateAnIncomeOrAPayment() {
        when(plans.findDetailById(412L)).thenReturn(Optional.of(
                plan(412L, client(40L, "49786112", ClientType.NATURAL), TODAY.minusYears(1))));
        InvalidDataException missing = new InvalidDataException("Falta un parámetro");
        when(parameters.maxMonthsAhead()).thenThrow(missing);
        assertSame(missing, assertThrows(InvalidDataException.class, () -> service.registerPayment(
                new PlanPaymentData(412L, YearMonth.from(TODAY), new BigDecimal("52000")))));
        verifyNoInteractions(transactions, payments, accessLog);
    }

    @Test
    void waitingWarningsUseTheConfiguredWindowIncludingItsLastDay() {
        PlanCardRow row = mock(PlanCardRow.class);
        when(row.getPlanId()).thenReturn(412L);
        when(row.getStartDate()).thenReturn(TODAY.minusDays(60));
        when(row.getStatus()).thenReturn(PlanStatus.ACTIVE);
        when(plans.findCards(PlanPaymentStatus.PAID, TODAY.withDayOfMonth(1))).thenReturn(List.of(row));
        when(parameters.waitingSoonDays()).thenReturn(1);
        var outsideWindow = service.overview();
        assertEquals(0, outsideWindow.kpis().waitingEndsSoon());
        assertTrue(outsideWindow.waitingSoon().isEmpty());

        when(parameters.waitingSoonDays()).thenReturn(2);
        var onBoundary = service.overview();
        assertEquals(1, onBoundary.kpis().waitingEndsSoon());
        assertEquals(TODAY.plusDays(2), onBoundary.waitingSoon().getFirst().waitingEnds());
    }

    @Test
    void missingWaitingWarningParameterDoesNotLoadAPartialOverview() {
        InvalidDataException missing = new InvalidDataException("Falta un parámetro");
        when(parameters.waitingSoonDays()).thenThrow(missing);
        assertSame(missing, assertThrows(InvalidDataException.class, service::overview));
        verifyNoInteractions(plans, beneficiaries, payments, transactions, clients, accessLog);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3})
    void affiliationUsesTheConfiguredStartDateLimitAndShowsItInTheError(int monthsAhead) {
        when(parameters.maxStartMonthsAhead()).thenReturn(monthsAhead);
        when(clients.findById(40L)).thenReturn(Optional.of(client(40L, "49786112", ClientType.NATURAL)));
        LocalDate lastAllowed = TODAY.plusMonths(monthsAhead);

        FuneralPlan saved = service.register(
                new PlanData(40L, "Plan Familiar", new BigDecimal("52000"), lastAllowed));
        assertEquals(lastAllowed, saved.getStartDate());
        InvalidDataException error = assertThrows(InvalidDataException.class, () -> service.register(
                new PlanData(40L, "Plan Familiar", new BigDecimal("52000"), lastAllowed.plusDays(1))));
        assertEquals("La fecha de inicio no puede ser más de " + monthsAhead + " mes(es) posterior a hoy.",
                error.getMessage());
        verify(plans, times(1)).save(any());
        verify(accessLog, times(1)).record(anyLong(), anyString());
    }

    @Test
    void missingStartDateParameterDoesNotAffiliateThePlan() {
        InvalidDataException missing = new InvalidDataException("Falta un parámetro");
        when(parameters.maxStartMonthsAhead()).thenThrow(missing);
        assertSame(missing, assertThrows(InvalidDataException.class, () -> service.register(
                new PlanData(40L, "Plan Familiar", new BigDecimal("52000"), TODAY))));
        verifyNoInteractions(clients, plans, beneficiaries, payments, transactions, accessLog);
    }

    // ---------- utilidades ----------

    private void loginAs(String roleName) {
        Manager employee = new Manager();
        employee.setId(99L);
        employee.setRole(new Role(1L, roleName, null));
        AppUser user = new AppUser();
        user.setId(5L);
        user.setEmployee(employee);
        session.start(user);
    }

    private static Client client(Long id, String document, ClientType type) {
        Client client = new Client();
        client.setId(id);
        client.setName("Martha Cecilia");
        client.setLastname("Durán");
        client.setDocumentNumber(document);
        client.setClientType(type);
        return client;
    }

    private static FuneralPlan plan(Long id, Client holder, LocalDate start) {
        FuneralPlan plan = new FuneralPlan();
        plan.setId(id);
        plan.setClient(holder);
        plan.setPlanName("Plan Familiar");
        plan.setMonthlyFee(new BigDecimal("52000"));
        plan.setStartDate(start);
        plan.setStatus(PlanStatus.ACTIVE);
        return plan;
    }
}
