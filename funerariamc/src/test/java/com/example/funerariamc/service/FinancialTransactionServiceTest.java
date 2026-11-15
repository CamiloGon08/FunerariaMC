package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.FinanceAccount;
import com.example.funerariamc.model.FinancialTransaction;
import com.example.funerariamc.model.Manager;
import com.example.funerariamc.model.Receptionist;
import com.example.funerariamc.model.Role;
import com.example.funerariamc.model.Sale;
import com.example.funerariamc.utils.enums.SaleStatus;
import com.example.funerariamc.utils.enums.TransactionStatus;
import com.example.funerariamc.utils.enums.TransactionType;
import com.example.funerariamc.persistence.AlliedPaymentRepository;
import com.example.funerariamc.persistence.FinanceAccountRepository;
import com.example.funerariamc.persistence.FinancialTransactionRepository;
import com.example.funerariamc.persistence.SaleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinancialTransactionServiceTest {

    private final FinancialTransactionRepository transactions = mock(FinancialTransactionRepository.class);
    private final SaleRepository sales = mock(SaleRepository.class);
    private final ContractService contracts = mock(ContractService.class);
    private final AlliedPaymentRepository allied = mock(AlliedPaymentRepository.class);
    private final FinanceAccountRepository accounts = mock(FinanceAccountRepository.class);
    private final AccessLogService audit = mock(AccessLogService.class);
    private final SessionContext session = new SessionContext();
    private final FinancialTransactionService service =
            new FinancialTransactionService(transactions, sales, allied, accounts, contracts, audit, session);
    private Receptionist secretary;

    @BeforeEach
    void setUp() {
        secretary = new Receptionist();
        secretary.setId(3L);
        secretary.setName("Laura");
        secretary.setLastname("Pérez");
        secretary.setRole(new Role(4L, RoleNames.SECRETARY, null));
        AppUser user = new AppUser();
        user.setId(9L);
        user.setEmployee(secretary);
        session.start(user);
        when(transactions.save(any())).thenAnswer(call -> {
            FinancialTransaction t = call.getArgument(0);
            if (t.getId() == null) {
                t.setId(12L);
            }
            return t;
        });
    }

    @Test
    void secretaryRegistersAManualExpenseAndItIsLogged() {
        Long id = service.register(new MovementData(TransactionType.EXPENSE, new BigDecimal("80000.004"), "  Papelería  ", null, null));
        assertEquals(12L, id);
        ArgumentCaptor<FinancialTransaction> saved = ArgumentCaptor.forClass(FinancialTransaction.class);
        verify(transactions).save(saved.capture());
        assertEquals(new BigDecimal("80000.00"), saved.getValue().getAmount());
        assertEquals("Papelería", saved.getValue().getDescription());
        assertSame(secretary, saved.getValue().getEmployee());
        assertEquals(TransactionStatus.ACTIVE, saved.getValue().getStatus());
        verify(audit).record(9L, "FINANCIAL_TRANSACTION_REGISTERED: MV-0012 EXPENSE 80000.00");
    }

    @Test
    void anExpenseIsChargedToTheChosenAccount() {
        FinanceAccount cemetery = new FinanceAccount(2L, "Gastos del cementerio", true);
        when(accounts.findById(2L)).thenReturn(Optional.of(cemetery));
        service.register(new MovementData(TransactionType.EXPENSE, new BigDecimal("120000"), "Jardinería", null, 2L));
        ArgumentCaptor<FinancialTransaction> saved = ArgumentCaptor.forClass(FinancialTransaction.class);
        verify(transactions).save(saved.capture());
        assertSame(cemetery, saved.getValue().getFinanceAccount());
    }

    @Test
    void anExpenseWithoutAccountIsSavedWithoutOne() {
        service.register(new MovementData(TransactionType.EXPENSE, BigDecimal.TEN, "Varios", null, null));
        ArgumentCaptor<FinancialTransaction> saved = ArgumentCaptor.forClass(FinancialTransaction.class);
        verify(transactions).save(saved.capture());
        assertNull(saved.getValue().getFinanceAccount());
        verifyNoInteractions(accounts);
    }

    @Test
    void onlyExpensesUseActiveAccounts() {
        assertThrows(InvalidDataException.class,
                () -> service.register(new MovementData(TransactionType.INCOME, BigDecimal.TEN, "x", null, 2L)));
        when(accounts.findById(3L)).thenReturn(Optional.of(new FinanceAccount(3L, "Vieja", false)));
        assertThrows(InvalidDataException.class,
                () -> service.register(new MovementData(TransactionType.EXPENSE, BigDecimal.TEN, "x", null, 3L)));
        when(accounts.findById(4L)).thenReturn(Optional.empty());
        assertThrows(InvalidDataException.class,
                () -> service.register(new MovementData(TransactionType.EXPENSE, BigDecimal.TEN, "x", null, 4L)));
        verify(transactions, never()).save(any());
    }

    @Test
    void listsActiveAccountsForTheForm() {
        when(accounts.findByActiveTrueOrderByName()).thenReturn(java.util.List.of(
                new FinanceAccount(1L, "Gastos de administración", true)));
        assertEquals(java.util.List.of(new FinancialTransactionService.AccountOption(1L, "Gastos de administración")),
                service.accounts());
    }

    @Test
    void incomeOfASaleIsRegisteredAsAPaymentOfThatSale() {
        assertNull(service.register(new MovementData(TransactionType.INCOME, new BigDecimal("50000"), "Abono", 7L, null)));
        verify(contracts).registerPayment(new SalePaymentData(7L, new BigDecimal("50000.00"), "Abono"));
        verify(transactions, never()).save(any());
    }

    @Test
    void rejectsInvalidMovements() {
        assertThrows(InvalidDataException.class,
                () -> service.register(new MovementData(null, BigDecimal.TEN, "x", null, null)));
        assertThrows(InvalidDataException.class,
                () -> service.register(new MovementData(TransactionType.INCOME, BigDecimal.ZERO, "x", null, null)));
        assertThrows(InvalidDataException.class,
                () -> service.register(new MovementData(TransactionType.INCOME, BigDecimal.TEN, "  ", null, null)));
        assertThrows(InvalidDataException.class,
                () -> service.register(new MovementData(TransactionType.INCOME, BigDecimal.TEN, "x".repeat(151), null, null)));
        assertThrows(InvalidDataException.class,
                () -> service.register(new MovementData(TransactionType.EXPENSE, BigDecimal.TEN, "x", 7L, null)));
        verifyNoInteractions(audit);
    }

    @Test
    void voidsAManualMovementWithoutDeletingIt() {
        FinancialTransaction movement = movement(12L, TransactionType.EXPENSE, null);
        when(transactions.findDetailById(12L)).thenReturn(Optional.of(movement));
        service.voidMovement(12L);
        assertEquals(TransactionStatus.VOIDED, movement.getStatus());
        verify(transactions, never()).delete(any());
        verify(audit).record(9L, "FINANCIAL_TRANSACTION_VOIDED: MV-0012");
    }

    @Test
    void movementsOfOtherModulesAreVoidedThere() {
        FinancialTransaction movement = movement(12L, TransactionType.INCOME, null);
        when(transactions.findDetailById(12L)).thenReturn(Optional.of(movement));
        when(transactions.isPlanPayment(12L)).thenReturn(true);
        InvalidDataException error = assertThrows(InvalidDataException.class, () -> service.voidMovement(12L));
        assertTrue(error.getMessage().contains("Planes exequiales"));
        assertEquals(TransactionStatus.ACTIVE, movement.getStatus());

        when(transactions.isPlanPayment(12L)).thenReturn(false);
        when(transactions.isCremationPurchase(12L)).thenReturn(true);
        assertTrue(assertThrows(InvalidDataException.class, () -> service.voidMovement(12L))
                .getMessage().contains("Cremaciones"));

        when(transactions.isCremationPurchase(12L)).thenReturn(false);
        when(allied.existsByFinancialTransactionId(12L)).thenReturn(true);
        assertTrue(assertThrows(InvalidDataException.class, () -> service.voidMovement(12L))
                .getMessage().contains("Red de funerarias aliadas"));
    }

    @Test
    void cannotVoidTwiceNorAPaymentOfAVoidedSale() {
        FinancialTransaction voided = movement(12L, TransactionType.EXPENSE, null);
        voided.setStatus(TransactionStatus.VOIDED);
        when(transactions.findDetailById(12L)).thenReturn(Optional.of(voided));
        assertThrows(InvalidDataException.class, () -> service.voidMovement(12L));

        Sale sale = new Sale();
        sale.setId(4L);
        sale.setStatus(SaleStatus.VOIDED);
        when(transactions.findDetailById(13L)).thenReturn(Optional.of(movement(13L, TransactionType.INCOME, sale)));
        assertThrows(InvalidDataException.class, () -> service.voidMovement(13L));
        verifyNoInteractions(audit);
    }

    @Test
    void classifiesTheOriginOfEachMovement() {
        var owners = new FinancialTransactionService.Owners(Set.of(1L), Set.of(2L), Set.of(3L));
        Sale sale = new Sale();
        sale.setId(5L);
        FinancialTransaction commission = movement(6L, TransactionType.EXPENSE, null);
        commission.setDescription("Comisión 2026-10 · Ana Ruiz · 3 ventas al 5 %");
        assertEquals(FinancialTransactionService.Origin.PLAN_INSTALLMENT,
                FinancialTransactionService.classify(movement(1L, TransactionType.INCOME, null), owners));
        assertEquals(FinancialTransactionService.Origin.CREMATION_PURCHASE,
                FinancialTransactionService.classify(movement(2L, TransactionType.EXPENSE, null), owners));
        assertEquals(FinancialTransactionService.Origin.ALLIED_PAYMENT,
                FinancialTransactionService.classify(movement(3L, TransactionType.EXPENSE, null), owners));
        assertEquals(FinancialTransactionService.Origin.SALE_PAYMENT,
                FinancialTransactionService.classify(movement(4L, TransactionType.INCOME, sale), owners));
        assertEquals(FinancialTransactionService.Origin.COMMISSION,
                FinancialTransactionService.classify(commission, owners));
        assertEquals(FinancialTransactionService.Origin.MANUAL,
                FinancialTransactionService.classify(movement(7L, TransactionType.INCOME, null), owners));
        var row = FinancialTransactionService.row(movement(1L, TransactionType.INCOME, null), owners);
        assertFalse(row.voidableHere());
        assertEquals("MV-0001", row.code());
    }

    @Test
    void onlyFinanceRolesCanUseIt() {
        Manager cemetery = new Manager();
        cemetery.setRole(new Role(2L, RoleNames.CEMETERY_ADMIN, null));
        AppUser user = new AppUser();
        user.setId(10L);
        user.setEmployee(cemetery);
        session.start(user);
        assertThrows(UnauthorizedActionException.class,
                () -> service.register(new MovementData(TransactionType.INCOME, BigDecimal.TEN, "x", null, null)));
        session.clear();
        assertThrows(UnauthorizedActionException.class, () -> service.voidMovement(1L));
    }

    @Test
    void classifiesNetworkFeesAndVaultRenewalsByTheirTypeAndPrefix() {
        var owners = new FinancialTransactionService.Owners(Set.of(), Set.of(), Set.of());
        FinancialTransaction network = movement(30L, TransactionType.EXPENSE, null);
        network.setDescription(AlliedNetworkService.NETWORK_FEE_PREFIX + "2026 · Transferencia");
        FinancialTransaction renewal = movement(31L, TransactionType.INCOME, null);
        renewal.setDescription(CemeteryService.RENEWAL_PREFIX + "A-001 · 1 año");

        assertEquals(FinancialTransactionService.Origin.NETWORK_FEE,
                FinancialTransactionService.classify(network, owners));
        assertEquals(FinancialTransactionService.Origin.VAULT_RENEWAL,
                FinancialTransactionService.classify(renewal, owners));
    }

    @Test
    void matchingDescriptionWithoutMatchingTransactionTypeStaysManual() {
        var owners = new FinancialTransactionService.Owners(Set.of(), Set.of(), Set.of());
        FinancialTransaction network = movement(30L, TransactionType.INCOME, null);
        network.setDescription(AlliedNetworkService.NETWORK_FEE_PREFIX + "2026");
        FinancialTransaction renewal = movement(31L, TransactionType.EXPENSE, null);
        renewal.setDescription(CemeteryService.RENEWAL_PREFIX + "A-001");

        assertEquals(FinancialTransactionService.Origin.MANUAL,
                FinancialTransactionService.classify(network, owners));
        assertEquals(FinancialTransactionService.Origin.MANUAL,
                FinancialTransactionService.classify(renewal, owners));
    }

    @Test
    void linkedPaymentOwnershipTakesPrecedenceOverDescriptionPrefixes() {
        var owners = new FinancialTransactionService.Owners(Set.of(30L), Set.of(), Set.of(31L));
        FinancialTransaction plan = movement(30L, TransactionType.INCOME, null);
        plan.setDescription(CemeteryService.RENEWAL_PREFIX + "A-001");
        FinancialTransaction allied = movement(31L, TransactionType.EXPENSE, null);
        allied.setDescription(AlliedNetworkService.NETWORK_FEE_PREFIX + "2026");

        assertEquals(FinancialTransactionService.Origin.PLAN_INSTALLMENT,
                FinancialTransactionService.classify(plan, owners));
        assertEquals(FinancialTransactionService.Origin.ALLIED_PAYMENT,
                FinancialTransactionService.classify(allied, owners));
    }

    private static FinancialTransaction movement(Long id, TransactionType type, Sale sale) {
        FinancialTransaction t = new FinancialTransaction();
        t.setId(id);
        t.setTransactionType(type);
        t.setAmount(new BigDecimal("1000.00"));
        t.setDescription("Movimiento");
        t.setRelatedSale(sale);
        t.setTransactionDate(LocalDateTime.of(2026, 10, 5, 15, 0));
        t.setStatus(TransactionStatus.ACTIVE);
        return t;
    }
}
