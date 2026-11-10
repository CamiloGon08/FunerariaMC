package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.FinancialTransaction;
import com.example.funerariamc.model.FuneralSalesperson;
import com.example.funerariamc.model.Receptionist;
import com.example.funerariamc.model.Role;
import com.example.funerariamc.model.Thanatopractor;
import com.example.funerariamc.model.enums.ContractStatus;
import com.example.funerariamc.model.enums.EmployeeStatus;
import com.example.funerariamc.model.enums.SaleStatus;
import com.example.funerariamc.model.enums.TransactionStatus;
import com.example.funerariamc.model.enums.TransactionType;
import com.example.funerariamc.persistence.AlliedPaymentRepository;
import com.example.funerariamc.persistence.FinancialTransactionRepository;
import com.example.funerariamc.persistence.SaleRepository;
import com.example.funerariamc.persistence.ServiceContractRepository;
import com.example.funerariamc.persistence.StaffOptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CommissionServiceTest {

    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

    private final StaffOptionRepository staff = mock(StaffOptionRepository.class);
    private final SaleRepository sales = mock(SaleRepository.class);
    private final ServiceContractRepository contracts = mock(ServiceContractRepository.class);
    private final FinancialTransactionRepository transactions = mock(FinancialTransactionRepository.class);
    private final AccessLogService audit = mock(AccessLogService.class);
    private final SessionContext session = new SessionContext();
    private final FinancialTransactionService finance =
            new FinancialTransactionService(transactions, sales, mock(AlliedPaymentRepository.class),
                    mock(com.example.funerariamc.persistence.FinanceAccountRepository.class), mock(ContractService.class), audit, session);
    // Hoy es 8 de noviembre de 2026 en Colombia.
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-08T15:00:00Z"), AccessLogService.APP_ZONE);
    private final CommissionService service =
            new CommissionService(staff, sales, contracts, transactions, finance, audit, clock);
    private FuneralSalesperson seller;
    private Thanatopractor thanatologist;

    @BeforeEach
    void setUp() {
        Receptionist secretary = new Receptionist();
        secretary.setRole(new Role(4L, RoleNames.SECRETARY, null));
        AppUser user = new AppUser();
        user.setId(9L);
        user.setEmployee(secretary);
        session.start(user);

        seller = new FuneralSalesperson();
        seller.setId(20L);
        seller.setName("Ana");
        seller.setLastname("Ruiz");
        seller.setDocumentNumber("1065000111");
        seller.setStatus(EmployeeStatus.ACTIVE);
        seller.setCommissionRate(new BigDecimal("5.00"));
        thanatologist = new Thanatopractor();
        thanatologist.setId(30L);
        thanatologist.setName("Luis");
        thanatologist.setLastname("Mora");
        thanatologist.setDocumentNumber("1065000222");
        thanatologist.setStatus(EmployeeStatus.ACTIVE);
        thanatologist.setCommissionPerCase(new BigDecimal("120000"));
        when(staff.findById(20L)).thenReturn(Optional.of(seller));
        when(staff.findById(30L)).thenReturn(Optional.of(thanatologist));
        when(staff.findAllSalespeople()).thenReturn(List.of(seller));
        when(staff.findAllThanatopractors()).thenReturn(List.of(thanatologist));
        when(sales.sumBySeller(any(), any(), eq(SaleStatus.ACTIVE))).thenReturn(List.of(sellerTotal(20L, 3, "4000000")));
        when(contracts.countPreparations(any(), any(), eq(ContractStatus.CANCELLED))).thenReturn(List.of(preparations(30L, 2)));
    }

    @Test
    void calculatesTheSellerCommissionOverWhatWasSold() {
        assertEquals(new BigDecimal("200000.00"), service.liquidate(20L, OCTOBER));
        ArgumentCaptor<FinancialTransaction> saved = ArgumentCaptor.forClass(FinancialTransaction.class);
        verify(transactions).save(saved.capture());
        FinancialTransaction expense = saved.getValue();
        assertEquals(TransactionType.EXPENSE, expense.getTransactionType());
        assertEquals(TransactionStatus.ACTIVE, expense.getStatus());
        assertSame(seller, expense.getEmployee());
        assertEquals("Comisión 2026-10 · Ana Ruiz · 3 ventas al 5 %", expense.getDescription());
        verify(audit).record(9L, "COMMISSION_REGISTERED: 2026-10 1065000111 200000.00");
    }

    @Test
    void calculatesTheThanatologistCommissionPerCase() {
        assertEquals(new BigDecimal("240000.00"), service.liquidate(30L, OCTOBER));
        ArgumentCaptor<FinancialTransaction> saved = ArgumentCaptor.forClass(FinancialTransaction.class);
        verify(transactions).save(saved.capture());
        assertEquals("Comisión 2026-10 · Luis Mora · 2 preparaciones", saved.getValue().getDescription());
    }

    @Test
    void theSameMonthIsNotCalculatedTwiceWhileTheExpenseIsActive() {
        when(transactions.existsByEmployeeIdAndTransactionTypeAndStatusAndDescriptionStartingWith(
                20L, TransactionType.EXPENSE, TransactionStatus.ACTIVE, "Comisión 2026-10 ·")).thenReturn(true);
        InvalidDataException error = assertThrows(InvalidDataException.class, () -> service.liquidate(20L, OCTOBER));
        assertTrue(error.getMessage().contains("ya se calculó"));
        verify(transactions, never()).save(any());
    }

    @Test
    void rejectsFutureMonthsAndPeopleWithoutCasesOrRate() {
        assertThrows(InvalidDataException.class, () -> service.liquidate(20L, YearMonth.of(2026, 12)));
        when(sales.sumBySeller(any(), any(), eq(SaleStatus.ACTIVE))).thenReturn(List.of());
        assertTrue(assertThrows(InvalidDataException.class, () -> service.liquidate(20L, OCTOBER))
                .getMessage().contains("no tiene ventas"));
        thanatologist.setCommissionPerCase(null);
        assertTrue(assertThrows(InvalidDataException.class, () -> service.liquidate(30L, OCTOBER))
                .getMessage().contains("tarifa por caso"));
        verify(transactions, never()).save(any());
        verify(audit, never()).record(anyLong(), anyString());
    }

    @Test
    void overviewShowsPendingAndCalculatedCommissions() {
        FinancialTransaction done = new FinancialTransaction();
        done.setId(77L);
        done.setEmployee(thanatologist);
        done.setAmount(new BigDecimal("240000.00"));
        done.setTransactionDate(LocalDateTime.of(2026, 11, 1, 14, 0));
        when(transactions.findCommissions("Comisión 2026-10 ·%", TransactionType.EXPENSE, TransactionStatus.ACTIVE))
                .thenReturn(List.of(done));
        CommissionService.Overview overview = service.overview(OCTOBER);
        assertEquals(2, overview.rows().size());
        assertEquals(new BigDecimal("200000.00"), overview.pending());
        assertEquals(new BigDecimal("240000.00"), overview.liquidated());
        assertFalse(overview.monthOpen());
        CommissionService.CommissionRow luis = overview.rows().get(1);
        assertTrue(luis.liquidated());
        assertEquals(LocalDateTime.of(2026, 11, 1, 9, 0), luis.liquidatedAt());
        assertTrue(service.overview(YearMonth.of(2026, 11)).monthOpen());
    }

    @Test
    void inactivePeopleWithoutCasesAreLeftOut() {
        seller.setStatus(EmployeeStatus.INACTIVE);
        when(sales.sumBySeller(any(), any(), eq(SaleStatus.ACTIVE))).thenReturn(List.of());
        assertEquals(1, service.overview(OCTOBER).rows().size());
    }

    @Test
    void amountsAreRoundedToCents() {
        assertEquals(new BigDecimal("12345.68"), CommissionService.sellerAmount(new BigDecimal("246913.5"), new BigDecimal("5")));
        assertEquals(new BigDecimal("0.00"), CommissionService.caseAmount(0, new BigDecimal("100000")));
        assertEquals(BigDecimal.ZERO, CommissionService.sellerAmount(BigDecimal.TEN, null));
    }

    private static SaleRepository.SellerTotal sellerTotal(Long id, long count, String total) {
        return new SaleRepository.SellerTotal() {
            public Long getEmployeeId() { return id; }
            public long getSales() { return count; }
            public BigDecimal getTotal() { return new BigDecimal(total); }
        };
    }

    private static ServiceContractRepository.PreparationCount preparations(Long id, long count) {
        return new ServiceContractRepository.PreparationCount() {
            public Long getThanatopractorId() { return id; }
            public long getCases() { return count; }
        };
    }
}
