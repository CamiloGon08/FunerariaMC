package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.CremationProvider;
import com.example.funerariamc.model.CremationPurchase;
import com.example.funerariamc.model.FinancialTransaction;
import com.example.funerariamc.model.Manager;
import com.example.funerariamc.model.Role;
import com.example.funerariamc.model.enums.CremationPurchaseStatus;
import com.example.funerariamc.model.enums.TransactionStatus;
import com.example.funerariamc.model.enums.TransactionType;
import com.example.funerariamc.persistence.CremationProviderRepository;
import com.example.funerariamc.persistence.CremationPurchaseRepository;
import com.example.funerariamc.persistence.FinancialTransactionRepository;
import com.example.funerariamc.service.CremationStockService.ProviderStock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Cremaciones prepagadas: proveedores, compras (solicitada, confirmada con gasto en Finanzas,
 * recibida o anulada) y el saldo de cupos disponibles, separados y cremados.
 */
class CremationStockServiceTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 10, 0);

    private final CremationProviderRepository providers = mock(CremationProviderRepository.class);
    private final CremationPurchaseRepository purchases = mock(CremationPurchaseRepository.class);
    private final FinancialTransactionRepository transactions = mock(FinancialTransactionRepository.class);
    private final AccessLogService accessLog = mock(AccessLogService.class);
    private final SessionContext session = new SessionContext();
    private final CremationStockService service = new CremationStockService(providers, purchases, transactions,
            accessLog, session, Clock.fixed(NOW.atZone(BOGOTA).toInstant(), BOGOTA));
    private final Manager employee = new Manager();

    @BeforeEach
    void loginAsCemeteryAdmin() {
        loginAs(RoleNames.CEMETERY_ADMIN);
        when(providers.save(any())).thenAnswer(i -> {
            CremationProvider p = i.getArgument(0);
            if (p.getId() == null) {
                p.setId(4L);
            }
            return p;
        });
        when(purchases.save(any())).thenAnswer(i -> {
            CremationPurchase p = i.getArgument(0);
            if (p.getId() == null) {
                p.setId(3L);
            }
            return p;
        });
        when(transactions.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void stockCountsAvailableReservedAndUsedSlots() {
        ProviderStock stock = new ProviderStock(1L, "BAQ-CREMA1", "Crematorio Barranquilla", 2, true,
                10, 3, 5, 10, 0);
        assertEquals(2, stock.available());
        assertTrue(stock.atMinimum());
        ProviderStock full = new ProviderStock(1L, "BAQ-CREMA1", "Crematorio Barranquilla", 2, true, 10, 1, 0, 0, 0);
        assertEquals(9, full.available());
        assertFalse(full.atMinimum());
    }

    @Test
    void newProviderIsSavedInUppercaseAndUnique() {
        CremationProvider saved = service.saveProvider(new CremationProviderData(null, " baq-crema1 ",
                "Crematorio  Barranquilla", 2, true));
        assertEquals("BAQ-CREMA1", saved.getCode());
        assertEquals("Crematorio Barranquilla", saved.getName());
        verify(accessLog).record(5L, AccessLogService.CREMATION_PROVIDER_SAVED + "BAQ-CREMA1");

        when(providers.existsByCode("BAQ-CREMA1")).thenReturn(true);
        assertThrows(InvalidDataException.class, () -> service.saveProvider(
                new CremationProviderData(null, "BAQ-CREMA1", "Otro", 0, true)));
    }

    @Test
    void providerDataIsValidated() {
        assertThrows(InvalidDataException.class, () -> service.saveProvider(
                new CremationProviderData(null, "PROVEEDOR-MUY-LARGO", "Nombre", 0, true)));
        assertThrows(InvalidDataException.class, () -> service.saveProvider(
                new CremationProviderData(null, "BAQ-01", " ", 0, true)));
        assertThrows(InvalidDataException.class, () -> service.saveProvider(
                new CremationProviderData(null, "BAQ-01", "Nombre", -1, true)));
    }

    @Test
    void requestWaitsForConfirmation() {
        when(providers.findById(1L)).thenReturn(Optional.of(provider(true)));
        var purchase = service.requestPurchase(new CremationPurchaseData(1L, 10, new BigDecimal("1800000")));
        assertEquals(CremationPurchaseStatus.REQUESTED, purchase.status());
        assertEquals("CP-0003", purchase.code());
        assertEquals(new BigDecimal("18000000"), purchase.total());
        assertEquals(NOW, purchase.requestedAt());
        verify(transactions, never()).save(any());
        verify(accessLog).record(5L, AccessLogService.CREMATION_PURCHASE_REQUESTED + "CP-0003 BAQ-CREMA1 x10");
    }

    @Test
    void requestNeedsQuantityPriceAndActiveProvider() {
        when(providers.findById(1L)).thenReturn(Optional.of(provider(false)));
        assertThrows(InvalidDataException.class,
                () -> service.requestPurchase(new CremationPurchaseData(1L, 0, new BigDecimal("1800000"))));
        assertThrows(InvalidDataException.class,
                () -> service.requestPurchase(new CremationPurchaseData(1L, 10, BigDecimal.ZERO)));
        assertThrows(InvalidDataException.class,
                () -> service.requestPurchase(new CremationPurchaseData(1L, 10, new BigDecimal("1800000"))));
    }

    @Test
    void confirmRegistersTheExpenseInFinance() {
        CremationPurchase purchase = givenPurchase(CremationPurchaseStatus.REQUESTED);
        service.confirm(3L);

        ArgumentCaptor<FinancialTransaction> captor = ArgumentCaptor.forClass(FinancialTransaction.class);
        verify(transactions).save(captor.capture());
        FinancialTransaction expense = captor.getValue();
        assertEquals(TransactionType.EXPENSE, expense.getTransactionType());
        assertEquals(new BigDecimal("18000000"), expense.getAmount());
        assertEquals(TransactionStatus.ACTIVE, expense.getStatus());
        assertSame(employee, expense.getEmployee());
        assertTrue(expense.getDescription().startsWith("Compra de 10 cremaciones prepagadas CP-0003"));
        assertEquals(CremationPurchaseStatus.ORDERED, purchase.getStatus());
        assertSame(expense, purchase.getFinancialTransaction());
        assertEquals(NOW, purchase.getOrderedAt());
    }

    @Test
    void receiveOnlyAfterConfirmation() {
        givenPurchase(CremationPurchaseStatus.REQUESTED);
        assertThrows(InvalidDataException.class, () -> service.receive(3L));
        CremationPurchase purchase = givenPurchase(CremationPurchaseStatus.ORDERED);
        service.receive(3L);
        assertEquals(CremationPurchaseStatus.RECEIVED, purchase.getStatus());
        assertEquals(NOW, purchase.getReceivedAt());
    }

    @Test
    void cancellingAConfirmedPurchaseVoidsItsExpense() {
        CremationPurchase purchase = givenPurchase(CremationPurchaseStatus.ORDERED);
        FinancialTransaction expense = new FinancialTransaction();
        expense.setStatus(TransactionStatus.ACTIVE);
        purchase.setFinancialTransaction(expense);

        service.cancel(3L);

        assertEquals(CremationPurchaseStatus.CANCELLED, purchase.getStatus());
        assertEquals(TransactionStatus.VOIDED, expense.getStatus());
    }

    @Test
    void receivedPurchaseCannotBeCancelled() {
        givenPurchase(CremationPurchaseStatus.RECEIVED);
        assertThrows(InvalidDataException.class, () -> service.cancel(3L));
    }

    @Test
    void secretaryCannotManagePrepaidCremations() {
        loginAs(RoleNames.SECRETARY);
        assertThrows(UnauthorizedActionException.class, service::stock);
        assertThrows(UnauthorizedActionException.class, () -> service.confirm(3L));
    }

    private static CremationProvider provider(boolean active) {
        return new CremationProvider(1L, "BAQ-CREMA1", "Crematorio Barranquilla", 2, active);
    }

    private CremationPurchase givenPurchase(CremationPurchaseStatus status) {
        CremationPurchase purchase = new CremationPurchase();
        purchase.setId(3L);
        purchase.setProvider(provider(true));
        purchase.setQuantity(10);
        purchase.setUnitPrice(new BigDecimal("1800000"));
        purchase.setStatus(status);
        purchase.setRequestedAt(NOW.minusDays(1));
        when(purchases.findDetailById(3L)).thenReturn(Optional.of(purchase));
        return purchase;
    }

    private void loginAs(String roleName) {
        employee.setId(99L);
        employee.setRole(new Role(1L, roleName, null));
        AppUser user = new AppUser();
        user.setId(5L);
        user.setEmployee(employee);
        session.start(user);
    }
}
