package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AlliedFuneralHome;
import com.example.funerariamc.model.AlliedPayment;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Manager;
import com.example.funerariamc.model.Receptionist;
import com.example.funerariamc.model.Role;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class AlliedNetworkServiceTest {

    private final AlliedFuneralHomeRepository homes = mock(AlliedFuneralHomeRepository.class);
    private final AlliedPaymentRepository payments = mock(AlliedPaymentRepository.class);
    private final ServiceContractRepository contracts = mock(ServiceContractRepository.class);
    private final FinancialTransactionRepository transactions = mock(FinancialTransactionRepository.class);
    private final AccessLogService audit = mock(AccessLogService.class);
    private final SessionContext session = new SessionContext();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-08T15:00:00Z"), AccessLogService.APP_ZONE);
    private final AlliedNetworkService service =
            new AlliedNetworkService(homes, payments, contracts, transactions, audit, session, clock);
    private Manager master;
    private AlliedFuneralHome home;
    private ServiceContract contract;

    @BeforeEach
    void setUp() {
        master = new Manager();
        master.setRole(new Role(1L, RoleNames.MASTER_ADMIN, null));
        AppUser user = new AppUser();
        user.setId(5L);
        user.setEmployee(master);
        session.start(user);
        home = new AlliedFuneralHome();
        home.setId(3L);
        home.setName("Funerales San José");
        home.setNit("800991447-1");
        home.setActive(true);
        contract = new ServiceContract();
        contract.setId(9L);
        contract.setContractNumber("CT-2026-0402");
        contract.setStatus(ContractStatus.ACTIVE);
        when(homes.findById(3L)).thenReturn(Optional.of(home));
        when(contracts.findById(9L)).thenReturn(Optional.of(contract));
        when(homes.save(any())).thenAnswer(call -> {
            AlliedFuneralHome h = call.getArgument(0);
            if (h.getId() == null) {
                h.setId(4L);
            }
            return h;
        });
        when(payments.save(any())).thenAnswer(call -> {
            AlliedPayment p = call.getArgument(0);
            if (p.getId() == null) {
                p.setId(20L);
            }
            return p;
        });
        when(transactions.save(any())).thenAnswer(call -> {
            FinancialTransaction t = call.getArgument(0);
            t.setId(45L);
            return t;
        });
    }

    private static AlliedHomeData data(String nit) {
        return new AlliedHomeData("  Funeraria La Esperanza ", nit, "300 555 0142", "Santa Marta", "laesperanza@correo.co",
                LocalDate.of(2021, 3, 12));
    }

    @Test
    void registersAnActiveHome() {
        assertEquals(4L, service.registerHome(data("900.214.556-3")));
        ArgumentCaptor<AlliedFuneralHome> saved = ArgumentCaptor.forClass(AlliedFuneralHome.class);
        verify(homes).save(saved.capture());
        assertEquals("Funeraria La Esperanza", saved.getValue().getName());
        assertEquals("3005550142", saved.getValue().getContactPhone());
        assertTrue(saved.getValue().getActive());
        verify(homes).existsNit("9002145563", null);
        verify(audit).record(5L, "ALLIED_HOME_REGISTERED: 900.214.556-3");
    }

    @Test
    void theSameNitIsNotRegisteredTwice() {
        when(homes.existsNit(eq("9002145563"), isNull())).thenReturn(true);
        InvalidDataException error = assertThrows(InvalidDataException.class, () -> service.registerHome(data("900214556-3")));
        assertTrue(error.getMessage().contains("Ya hay una funeraria"));
        verify(homes, never()).save(any());
    }

    @Test
    void editingKeepsItsOwnNit() {
        service.updateHome(3L, data("800991447-1"));
        verify(homes).existsNit("8009914471", 3L);
        verify(audit).record(5L, "ALLIED_HOME_UPDATED: 800991447-1");
    }

    @Test
    void rejectsInvalidHomeData() {
        assertThrows(InvalidDataException.class, () -> service.registerHome(new AlliedHomeData(" ", "900214556", "3005550142",
                null, null, LocalDate.now())));
        assertThrows(InvalidDataException.class, () -> service.registerHome(data("NIT-ABC")));
        assertThrows(InvalidDataException.class, () -> service.registerHome(new AlliedHomeData("X", "900214556", "12",
                null, null, LocalDate.now())));
        assertThrows(InvalidDataException.class, () -> service.registerHome(new AlliedHomeData("X", "900214556", "3005550142",
                null, "correo-malo", LocalDate.now())));
        assertThrows(InvalidDataException.class, () -> service.registerHome(new AlliedHomeData("X", "900214556", "3005550142",
                null, null, null)));
        verify(homes, never()).save(any());
    }

    @Test
    void aPaymentIsSavedPendingWithItsContract() {
        assertEquals(20L, service.registerPayment(new AlliedPaymentData(3L, 9L, new BigDecimal("3200000"), LocalDate.of(2026, 10, 9))));
        ArgumentCaptor<AlliedPayment> saved = ArgumentCaptor.forClass(AlliedPayment.class);
        verify(payments).save(saved.capture());
        assertEquals(AlliedPaymentStatus.PENDING, saved.getValue().getStatus());
        assertSame(contract, saved.getValue().getRelatedServiceContract());
        assertEquals(new BigDecimal("3200000.00"), saved.getValue().getAmount());
        verify(transactions, never()).save(any());
        verify(audit).record(5L, "ALLIED_PAYMENT_REGISTERED: 800991447-1 CT-2026-0402 3200000.00");
    }

    @Test
    void rejectsInvalidPayments() {
        assertThrows(InvalidDataException.class,
                () -> service.registerPayment(new AlliedPaymentData(3L, 9L, BigDecimal.ZERO, LocalDate.now())));
        assertThrows(InvalidDataException.class,
                () -> service.registerPayment(new AlliedPaymentData(3L, null, BigDecimal.TEN, LocalDate.now())));
        assertThrows(InvalidDataException.class,
                () -> service.registerPayment(new AlliedPaymentData(3L, 9L, BigDecimal.TEN, null)));
        contract.setStatus(ContractStatus.CANCELLED);
        assertThrows(InvalidDataException.class,
                () -> service.registerPayment(new AlliedPaymentData(3L, 9L, BigDecimal.TEN, LocalDate.now())));
        contract.setStatus(ContractStatus.ACTIVE);
        home.setActive(false);
        assertThrows(InvalidDataException.class,
                () -> service.registerPayment(new AlliedPaymentData(3L, 9L, BigDecimal.TEN, LocalDate.now())));
        verify(payments, never()).save(any());
    }

    @Test
    void markingPaidRegistersTheExpenseInFinance() {
        AlliedPayment payment = payment(AlliedPaymentStatus.PENDING);
        when(payments.findDetailById(20L)).thenReturn(Optional.of(payment));
        service.markPaid(20L);
        ArgumentCaptor<FinancialTransaction> expense = ArgumentCaptor.forClass(FinancialTransaction.class);
        verify(transactions).save(expense.capture());
        assertEquals(TransactionType.EXPENSE, expense.getValue().getTransactionType());
        assertEquals(new BigDecimal("3200000.00"), expense.getValue().getAmount());
        assertEquals("Pago a funeraria aliada · Funerales San José · contrato CT-2026-0402", expense.getValue().getDescription());
        assertSame(master, expense.getValue().getEmployee());
        assertEquals(AlliedPaymentStatus.PAID, payment.getStatus());
        assertSame(expense.getValue(), payment.getFinancialTransaction());
        verify(audit).record(5L, "ALLIED_PAYMENT_PAID: 800991447-1 MV-0045 3200000.00");

        assertThrows(InvalidDataException.class, () -> service.markPaid(20L));
    }

    @Test
    void voidingAPaidPaymentAlsoVoidsItsExpense() {
        AlliedPayment payment = payment(AlliedPaymentStatus.PAID);
        FinancialTransaction expense = new FinancialTransaction();
        expense.setId(45L);
        expense.setStatus(TransactionStatus.ACTIVE);
        payment.setFinancialTransaction(expense);
        when(payments.findDetailById(20L)).thenReturn(Optional.of(payment));
        service.voidPayment(20L);
        assertEquals(AlliedPaymentStatus.VOIDED, payment.getStatus());
        assertEquals(TransactionStatus.VOIDED, expense.getStatus());
        verify(payments, never()).delete(any());
        assertThrows(InvalidDataException.class, () -> service.voidPayment(20L));
    }

    @Test
    void voidingAPendingPaymentTouchesNoExpense() {
        when(payments.findDetailById(20L)).thenReturn(Optional.of(payment(AlliedPaymentStatus.PENDING)));
        service.voidPayment(20L);
        verify(transactions, never()).save(any());
    }

    @Test
    void deactivatingTwiceIsRejected() {
        service.setActive(3L, false);
        assertFalse(home.getActive());
        verify(audit).record(5L, "ALLIED_HOME_DEACTIVATED: 800991447-1");
        assertThrows(InvalidDataException.class, () -> service.setActive(3L, false));
    }

    @Test
    void overviewOrdersByBalanceAndCountsThisMonth() {
        AlliedFuneralHome other = new AlliedFuneralHome();
        other.setId(7L);
        other.setName("Exequiales del Caribe");
        other.setActive(true);
        when(homes.findAllByOrderByNameAsc()).thenReturn(List.of(other, home));
        when(payments.totalsByHome(AlliedPaymentStatus.PENDING, AlliedPaymentStatus.VOIDED,
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1)))
                .thenReturn(List.of(totals(3L, "4100000", 2, 3, LocalDate.of(2026, 9, 18))));
        when(payments.countServices(AlliedPaymentStatus.VOIDED, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1)))
                .thenReturn(5L);
        AlliedNetworkService.Overview overview = service.overview();
        assertEquals("Funerales San José", overview.homes().get(0).name());
        assertEquals(AlliedNetworkService.HomeState.WITH_BALANCE, overview.homes().get(0).state());
        assertEquals(AlliedNetworkService.HomeState.UP_TO_DATE, overview.homes().get(1).state());
        assertEquals(new BigDecimal("4100000"), overview.pending());
        assertEquals(3, overview.servicesThisMonth());
        assertEquals(5, overview.servicesLastMonth());
        assertEquals(LocalDate.of(2026, 9, 18), overview.oldestPending());
    }

    @Test
    void initialsSkipShortWords() {
        assertEquals("FE", AlliedNetworkService.initials("Funeraria La Esperanza"));
        assertEquals("EC", AlliedNetworkService.initials("Exequiales del Caribe"));
    }

    @Test
    void onlyTheMasterAdministratorUsesIt() {
        Receptionist secretary = new Receptionist();
        secretary.setRole(new Role(2L, RoleNames.SECRETARY, null));
        AppUser user = new AppUser();
        user.setId(9L);
        user.setEmployee(secretary);
        session.start(user);
        assertThrows(UnauthorizedActionException.class, service::overview);
        assertThrows(UnauthorizedActionException.class, () -> service.markPaid(20L));
        verify(audit, never()).record(anyLong(), anyString());
    }

    private AlliedPayment payment(AlliedPaymentStatus status) {
        AlliedPayment p = new AlliedPayment();
        p.setId(20L);
        p.setAlliedFuneralHome(home);
        p.setRelatedServiceContract(contract);
        p.setAmount(new BigDecimal("3200000.00"));
        p.setPaymentDate(LocalDate.of(2026, 10, 9));
        p.setStatus(status);
        return p;
    }

    private static AlliedPaymentRepository.HomeTotals totals(Long id, String pending, long count, long services,
                                                             LocalDate oldest) {
        return new AlliedPaymentRepository.HomeTotals() {
            public Long getHomeId() { return id; }
            public BigDecimal getPending() { return new BigDecimal(pending); }
            public long getPendingCount() { return count; }
            public long getServices() { return services; }
            public LocalDate getOldestPending() { return oldest; }
        };
    }
}
