package com.example.funerariamc.service;

import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.exception.ResourceNotFoundException;
import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Beneficiary;
import com.example.funerariamc.model.Client;
import com.example.funerariamc.model.Manager;
import com.example.funerariamc.model.Role;
import com.example.funerariamc.model.enums.PlanStatus;
import com.example.funerariamc.model.enums.SaleType;
import com.example.funerariamc.model.enums.TransactionStatus;
import com.example.funerariamc.model.enums.TransactionType;
import com.example.funerariamc.persistence.ClientOverviewRepository;
import com.example.funerariamc.persistence.ClientOverviewRepository.ClientCounts;
import com.example.funerariamc.persistence.ClientOverviewRepository.ContractCount;
import com.example.funerariamc.persistence.ClientOverviewRepository.PlanRow;
import com.example.funerariamc.persistence.ClientOverviewRepository.SaleRow;
import com.example.funerariamc.persistence.ClientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Tarjetas y panel de Clientes (propuesta P-11 / P-11.1): plan, contratos, saldo y carencia (RN-08). */
class ClientOverviewServiceTest {

    private final ClientOverviewRepository overview = mock(ClientOverviewRepository.class);
    private final ClientRepository clients = mock(ClientRepository.class);
    private final SessionContext session = new SessionContext();
    private final PlanParameters parameters = mock(PlanParameters.class);
    private final ClientOverviewService service = new ClientOverviewService(overview, clients, session, parameters);

    @BeforeEach
    void loginAsSalesperson() {
        loginAs(RoleNames.SALESPERSON);
        when(parameters.waitingDays()).thenReturn(62);
    }

    @Test
    void summaryAddsBalancesOfEverySaleAndIgnoresOverpayments() {
        when(overview.findSales(List.of(1L), TransactionType.INCOME, TransactionStatus.ACTIVE)).thenReturn(List.of(
                sale(10L, 1L, "3200000", "1350000", "SC-0587"),   // debe 1.850.000
                sale(11L, 1L, "500000", "600000", null),          // pagó de más: no resta
                sale(12L, 1L, "900000", "0", null)));             // debe 900.000
        when(overview.countContracts(List.of(1L))).thenReturn(List.of(contracts(1L, 2)));

        ClientOverviewService.ClientSummary summary = service.summaries(List.of(1L)).get(1L);

        assertEquals(new BigDecimal("2750000"), summary.balance());
        assertTrue(summary.hasBalance());
        assertEquals(2, summary.contracts());
        assertEquals(3, summary.sales());
        assertNull(summary.plan());
    }

    @Test
    void clientWithoutSalesIsUpToDate() {
        Map<Long, ClientOverviewService.ClientSummary> result = service.summaries(List.of(7L));

        assertEquals(BigDecimal.ZERO, result.get(7L).balance());
        assertFalse(result.get(7L).hasBalance());
        assertEquals(0, result.get(7L).contracts());
    }

    @Test
    void activePlanWinsOverCancelledOneAndUsesConfiguredWaitingPeriod() {
        LocalDate start = LocalDate.of(2026, 9, 1);
        when(overview.findPlans(List.of(1L))).thenReturn(List.of(
                plan(1L, 3L, "Plan anterior", LocalDate.of(2026, 10, 1), PlanStatus.CANCELLED, 1),
                plan(1L, 412L, "Plan familiar", start, PlanStatus.ACTIVE, 4)));

        ClientOverviewService.PlanInfo plan = service.summaries(List.of(1L)).get(1L).plan();

        assertEquals("PL-0412", plan.code());
        assertEquals(4, plan.beneficiaries());
        assertEquals(LocalDate.of(2026, 11, 2), plan.waitingEnds());
        assertFalse(plan.isCovered(LocalDate.of(2026, 11, 1)));
        assertTrue(plan.isCovered(LocalDate.of(2026, 11, 2)));
    }

    @Test
    void detailListsBeneficiariesAndSalesWithTheirCode() {
        Client client = new Client();
        client.setId(1L);
        when(clients.findById(1L)).thenReturn(Optional.of(client));
        when(overview.findPlans(List.of(1L))).thenReturn(List.of(
                plan(1L, 412L, null, LocalDate.of(2026, 1, 10), PlanStatus.ACTIVE, 1)));
        Beneficiary son = new Beneficiary();
        son.setName("Mateo");
        son.setLastname("Durán Ríos");
        son.setRelationship("Hijo");
        when(overview.findBeneficiaries(412L)).thenReturn(List.of(son));
        when(overview.findSales(List.of(1L), TransactionType.INCOME, TransactionStatus.ACTIVE)).thenReturn(List.of(
                sale(10L, 1L, "3200000", "1350000", "SC-0587"), sale(211L, 1L, "480000", "480000", null)));

        ClientOverviewService.ClientDetail detail = service.detail(1L);

        assertEquals("Plan exequial", detail.summary().plan().name());
        assertEquals("Mateo Durán Ríos", detail.beneficiaries().get(0).fullName());
        assertEquals("SC-0587", detail.sales().get(0).code());
        assertEquals("VT-0211", detail.sales().get(1).code());
        assertEquals("Hijo", detail.beneficiaries().get(0).relationship());
        assertEquals(BigDecimal.ZERO, detail.sales().get(1).balance());
    }

    @Test
    void kpisCountPlansReceivableAndNewClientsOfTheMonth() {
        LocalDate firstOfMonth = LocalDate.now(AccessLogService.APP_ZONE).withDayOfMonth(1);
        when(overview.countClients(firstOfMonth))
                .thenReturn(counts(248, 212, 132, new BigDecimal("41900000"), 27, 9));

        ClientOverviewService.ClientKpis kpis = service.kpis();

        assertEquals(new ClientOverviewService.ClientKpis(248, 212, 36, 132,
                new BigDecimal("41900000"), 27, 9), kpis);
        assertEquals(53, kpis.planPercent());
        verify(overview).countClients(firstOfMonth);
        verifyNoMoreInteractions(overview);
        verifyNoInteractions(clients, parameters);
    }

    @Test
    void emptyKpisHaveNoReceivableAndDoNotDivideByZero() {
        LocalDate firstOfMonth = LocalDate.now(AccessLogService.APP_ZONE).withDayOfMonth(1);
        when(overview.countClients(firstOfMonth)).thenReturn(counts(0, 0, 0, null, 0, 0));

        ClientOverviewService.ClientKpis kpis = service.kpis();

        assertEquals(new ClientOverviewService.ClientKpis(0, 0, 0, 0, BigDecimal.ZERO, 0, 0), kpis);
        assertEquals(0, kpis.planPercent());
        verify(overview).countClients(firstOfMonth);
        verifyNoMoreInteractions(overview);
        verifyNoInteractions(clients, parameters);
    }

    @ParameterizedTest
    @CsvSource({"CHILD, Hijo(a)", "SPOUSE, Cónyuge o compañero(a)", "EMPLOYEE, Empleado (plan empresarial)"})
    void beneficiaryCodesAreDisplayedInSpanish(String code, String label) {
        assertEquals(label, relationshipShownInDetail(code));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"Hijo", "CUSTOM_RELATION", ""})
    void historicalOrMissingRelationshipsArePreserved(String code) {
        assertEquals(code, relationshipShownInDetail(code));
    }

    @Test
    void changedWaitingParameterChangesTheCoverageDate() {
        when(parameters.waitingDays()).thenReturn(10);
        when(overview.findPlans(List.of(1L))).thenReturn(List.of(
                plan(1L, 412L, "Plan familiar", LocalDate.of(2026, 9, 1), PlanStatus.ACTIVE, 2)));

        ClientOverviewService.PlanInfo plan = service.summaries(List.of(1L)).get(1L).plan();

        assertEquals(LocalDate.of(2026, 9, 11), plan.waitingEnds());
        verify(parameters).waitingDays();
    }

    @Test
    void missingWaitingParameterIsReportedInsteadOfAssumingCoverage() {
        when(overview.findPlans(List.of(1L))).thenReturn(List.of(
                plan(1L, 412L, "Plan familiar", LocalDate.of(2026, 9, 1), PlanStatus.ACTIVE, 2)));
        when(parameters.waitingDays()).thenThrow(new InvalidDataException("Falta configuración"));

        assertThrows(InvalidDataException.class, () -> service.summaries(List.of(1L)));
    }

    @Test
    void emptySelectionDoesNotReadRepositoriesOrConfiguration() {
        assertTrue(service.summaries(List.of()).isEmpty());
        assertTrue(service.summaries(null).isEmpty());
        verifyNoInteractions(overview, clients, parameters);
    }

    @Test
    void missingClientRaisesBusinessErrorBeforeLoadingItsDetail() {
        assertThrows(ResourceNotFoundException.class, () -> service.detail(404L));
        verifyNoInteractions(overview, parameters);
    }

    @Test
    void anonymousSessionCannotReadAnyClientOverview() {
        session.clear();
        assertThrows(UnauthorizedActionException.class, () -> service.summaries(List.of(1L)));
        assertThrows(UnauthorizedActionException.class, () -> service.detail(1L));
        assertThrows(UnauthorizedActionException.class, service::kpis);
        verifyNoInteractions(overview, clients, parameters);
    }

    @Test
    void cemeteryAdminCannotSeeClients() {
        loginAs(RoleNames.CEMETERY_ADMIN);

        assertThrows(UnauthorizedActionException.class, () -> service.summaries(List.of(1L)));
        assertThrows(UnauthorizedActionException.class, service::kpis);
        assertThrows(UnauthorizedActionException.class, () -> service.detail(1L));
        verifyNoInteractions(overview);
        verify(clients, never()).findById(any());
    }

    // ---------- datos ----------

    private String relationshipShownInDetail(String code) {
        Client client = new Client();
        client.setId(1L);
        when(clients.findById(1L)).thenReturn(Optional.of(client));
        when(overview.findPlans(List.of(1L))).thenReturn(List.of(
                plan(1L, 412L, "Plan familiar", LocalDate.of(2026, 1, 10), PlanStatus.ACTIVE, 1)));
        Beneficiary beneficiary = new Beneficiary();
        beneficiary.setName("Cliente");
        beneficiary.setLastname("De prueba");
        beneficiary.setRelationship(code);
        when(overview.findBeneficiaries(412L)).thenReturn(List.of(beneficiary));
        return service.detail(1L).beneficiaries().get(0).relationship();
    }

    private void loginAs(String roleName) {
        Manager employee = new Manager();
        employee.setId(99L);
        employee.setRole(new Role(1L, roleName, null));
        AppUser user = new AppUser();
        user.setId(5L);
        user.setEmployee(employee);
        session.start(user);
    }

    private static SaleRow sale(Long id, Long clientId, String total, String paid, String contract) {
        return new SaleRow() {
            public Long getSaleId() { return id; }
            public Long getClientId() { return clientId; }
            public SaleType getSaleType() { return contract == null ? SaleType.PRODUCT : SaleType.CONTRACT; }
            public LocalDateTime getSaleDate() { return LocalDateTime.of(2026, 10, 2, 10, 0); }
            public BigDecimal getTotal() { return new BigDecimal(total); }
            public BigDecimal getPaid() { return new BigDecimal(paid); }
            public String getContractNumber() { return contract; }
        };
    }

    private static PlanRow plan(Long clientId, Long planId, String name, LocalDate start, PlanStatus status,
                                long beneficiaries) {
        return new PlanRow() {
            public Long getClientId() { return clientId; }
            public Long getPlanId() { return planId; }
            public String getPlanName() { return name; }
            public LocalDate getStartDate() { return start; }
            public PlanStatus getStatus() { return status; }
            public long getBeneficiaries() { return beneficiaries; }
        };
    }

    private static ContractCount contracts(Long clientId, long count) {
        return new ContractCount() {
            public Long getClientId() { return clientId; }
            public long getContracts() { return count; }
        };
    }

    private static ClientCounts counts(long total, long natural, long withPlan, BigDecimal amount,
                                       long sales, long newThisMonth) {
        return new ClientCounts() {
            public long getTotal() { return total; }
            public long getNaturalClients() { return natural; }
            public long getWithPlan() { return withPlan; }
            public BigDecimal getReceivable() { return amount; }
            public long getSalesWithBalance() { return sales; }
            public long getNewThisMonth() { return newThisMonth; }
        };
    }
}
