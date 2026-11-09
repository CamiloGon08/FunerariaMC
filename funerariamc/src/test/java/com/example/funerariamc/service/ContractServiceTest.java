package com.example.funerariamc.service;

import com.example.funerariamc.exception.InstallmentExceedsBalanceException;
import com.example.funerariamc.exception.InactiveEmployeeException;
import com.example.funerariamc.exception.InsufficientStockException;
import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.WaitingPeriodException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Thanatopractor;
import com.example.funerariamc.model.Client;
import com.example.funerariamc.model.Deceased;
import com.example.funerariamc.model.FinancialTransaction;
import com.example.funerariamc.model.FuneralSalesperson;
import com.example.funerariamc.model.InventoryItem;
import com.example.funerariamc.model.Manager;
import com.example.funerariamc.model.Role;
import com.example.funerariamc.model.Sale;
import com.example.funerariamc.model.ServiceContract;
import com.example.funerariamc.model.WakeRoom;
import com.example.funerariamc.model.enums.ClientType;
import com.example.funerariamc.model.enums.ContractStatus;
import com.example.funerariamc.model.enums.EmployeeStatus;
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
import com.example.funerariamc.service.ContractService.PaymentState;
import com.example.funerariamc.service.ContractService.Quote;
import com.example.funerariamc.service.ContractService.SaleKind;
import com.example.funerariamc.service.ContractService.SaleRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Criterios de aceptación: contrato único por difunto, venta con detalle por concepto, pensión, excedente, buses,
 * existencias, abonos con saldo y filtro por tipo.
 */
class ContractServiceTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);

    private final SaleRepository sales = mock(SaleRepository.class);
    private final SaleDetailRepository details = mock(SaleDetailRepository.class);
    private final FinancialTransactionRepository transactions = mock(FinancialTransactionRepository.class);
    private final ServiceContractRepository contracts = mock(ServiceContractRepository.class);
    private final InventoryItemRepository items = mock(InventoryItemRepository.class);
    private final StaffOptionRepository staff = mock(StaffOptionRepository.class);
    private final WakeRoomRepository rooms = mock(WakeRoomRepository.class);
    private final WakeRoomService wakeRooms = mock(WakeRoomService.class);
    private final FuneralPlanService plans = mock(FuneralPlanService.class);
    private final SaleParameters parameters = mock(SaleParameters.class);
    private final AccessLogService accessLog = mock(AccessLogService.class);
    private final SessionContext session = new SessionContext();
    private final ContractService service = new ContractService(sales, details, transactions, contracts, items,
            staff, rooms, wakeRooms, plans, parameters, accessLog, session,
            Clock.fixed(TODAY.atTime(10, 0).atZone(BOGOTA).toInstant(), BOGOTA));

    @BeforeEach
    void loginAsSalesperson() {
        loginAs(RoleNames.SALESPERSON);
        when(sales.save(any())).thenAnswer(invocation -> {
            Sale sale = invocation.getArgument(0);
            if (sale.getId() == null) {
                sale.setId(148L);
            }
            return sale;
        });
        when(contracts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(contracts.findByDeceasedId(any())).thenReturn(Optional.empty());
        when(sales.findClientById(10L)).thenReturn(Optional.of(client(10L, ClientType.NATURAL)));
        when(sales.findClientById(11L)).thenReturn(Optional.of(client(11L, ClientType.CORPORATE)));
        when(sales.findDeceasedById(20L)).thenReturn(Optional.of(new Deceased()));
        FuneralSalesperson seller = new FuneralSalesperson();
        seller.setId(3L);
        seller.setStatus(EmployeeStatus.ACTIVE);
        when(staff.findById(3L)).thenReturn(Optional.of(seller));
    }

    // ---------- venta con detalle ----------

    @Test
    void inactiveThanatopractorCannotBeAssignedToPreparation() {
        loginAs(RoleNames.SECRETARY);
        ServiceContract contract = new ServiceContract();
        contract.setId(90L);
        contract.setStatus(ContractStatus.ACTIVE);
        when(contracts.findDetailById(90L)).thenReturn(Optional.of(contract));
        Thanatopractor inactive = new Thanatopractor();
        inactive.setId(8L);
        inactive.setStatus(EmployeeStatus.INACTIVE);
        when(staff.findById(8L)).thenReturn(Optional.of(inactive));

        assertThrows(InactiveEmployeeException.class,
                () -> service.registerPreparation(90L, 8L, TODAY.atTime(9, 0)));

        verify(contracts, never()).save(any());
        verifyNoInteractions(accessLog);
    }

    @Test
    void specialRoomAndOneExtraBusOverABaseFeeGiveThreeLines() {
        // Caso: sala especial ($50.000) y un bus adicional ($80.000) sobre una
        // cuota base de $200.000; PASA si el total es $330.000 en tres líneas.
        when(rooms.findById(1L)).thenReturn(Optional.of(room(true)));
        when(parameters.wakeRoomSurcharge()).thenReturn(new BigDecimal("50000"));
        when(parameters.extraBusFee()).thenReturn(new BigDecimal("80000"));

        Quote quote = service.quote(privateService(new BigDecimal("200000"), 1L, 1, null));

        assertEquals(3, quote.lines().size());
        assertEquals(0, new BigDecimal("330000").compareTo(quote.total()));
    }

    @Test
    void twoExtraBusesAreChargedAtTheParameterizedFee() {
        // Caso: 2 buses adicionales con tarifa de $80.000; PASA si el cobro es $160.000.
        when(parameters.extraBusFee()).thenReturn(new BigDecimal("80000"));
        Quote quote = service.quote(privateService(BigDecimal.ZERO, null, 2, null));
        assertEquals(0, new BigDecimal("160000").compareTo(quote.lines().get(1).subtotal()));
    }

    @Test
    void withAPlanOnlyTheCoffinExcessIsCharged() {
        // Caso: una gama que cuesta $35.000 más que la básica; PASA si el excedente es $35.000.
        InventoryItem coffin = item(50L, "Ataúd Roble", "1235000", 3);
        when(items.findById(50L)).thenReturn(Optional.of(coffin));
        when(parameters.basicCoffinPrice()).thenReturn(new BigDecimal("1200000"));
        FuneralPlanService.PlanDetail detail = mock(FuneralPlanService.PlanDetail.class);
        when(plans.detail(9L)).thenReturn(detail);
        when(detail.totalSurcharge()).thenReturn(BigDecimal.ZERO);

        Quote quote = service.quote(new ContractData(SaleKind.PLAN, 10L, 20L, 9L, 3L, BigDecimal.ZERO, null,
                null, null, null, 50L, 0, List.of(), null));

        assertEquals(0, new BigDecimal("35000").compareTo(quote.lines().get(1).subtotal()));
    }

    @Test
    void pensionServiceIsWorthTheWagesTimesTheMinimumWage() {
        // Caso: 5 salarios mínimos; PASA si el total es 5 veces el parametrizado.
        when(parameters.minimumWage()).thenReturn(new BigDecimal("1423500"));
        Quote quote = service.quote(new ContractData(SaleKind.PENSION, 11L, 20L, null, 3L, null,
                new BigDecimal("5"), null, null, null, null, 0, List.of(), null));
        assertEquals(0, new BigDecimal("7117500").compareTo(quote.total()));
    }

    @Test
    void pensionForANaturalClientIsRejected() {
        // La entidad pagadora debe ser cliente corporativo.
        when(parameters.minimumWage()).thenReturn(new BigDecimal("1423500"));
        assertThrows(InvalidDataException.class, () -> service.register(new ContractData(SaleKind.PENSION, 10L,
                20L, null, 3L, null, new BigDecimal("5"), null, null, null, null, 0, List.of(), null)));
        verify(sales, never()).save(any());
    }

    @Test
    void coffinWithoutStockIsRejected() {
        // Caso: vender un ataúd con existencias en cero; PASA si se rechaza.
        when(items.findById(50L)).thenReturn(Optional.of(item(50L, "Ataúd Cedro", "900000", 0)));
        InsufficientStockException error = assertThrows(InsufficientStockException.class,
                () -> service.quote(new ContractData(SaleKind.PRODUCT, 10L, null, null, null, null, null, null,
                        null, null, null, 0, List.of(new ContractData.ItemLine(50L, 1)), null)));
        assertEquals(0, error.getAvailableQuantity());
    }

    // ---------- contrato ----------

    @Test
    void secondContractForTheSameDeceasedIsRejected() {
        // Caso: crear dos contratos para el mismo difunto; PASA si el segundo se rechaza.
        ServiceContract existing = new ServiceContract();
        existing.setContractNumber("CT-2026-0142");
        existing.setStatus(ContractStatus.ACTIVE);
        when(contracts.findByDeceasedId(20L)).thenReturn(Optional.of(existing));

        InvalidDataException error = assertThrows(InvalidDataException.class,
                () -> service.register(privateService(new BigDecimal("200000"), null, 0, null)));
        assertTrue(error.getMessage().contains("CT-2026-0142"));
        verify(sales, never()).save(any());
    }

    @Test
    void planInWaitingPeriodStopsTheContract() {
        // Caso: plan afiliado hace 30 días; PASA si se rechaza e indica 32 días.
        doThrow(new WaitingPeriodException(32)).when(plans).validateBenefitsAvailable(9L);
        WaitingPeriodException error = assertThrows(WaitingPeriodException.class,
                () -> service.register(new ContractData(SaleKind.PLAN, 10L, 20L, 9L, 3L, BigDecimal.ZERO, null,
                        null, null, null, null, 0, List.of(), null)));
        assertEquals(32, error.getDaysRemaining());
    }

    @Test
    void registerNumbersTheContractAndSavesTheInitialPayment() {
        when(contracts.findLastSequence("CT-2026-%")).thenReturn(147);

        Long saleId = service.register(privateService(new BigDecimal("300000"), null, 0, new BigDecimal("100000")));

        ArgumentCaptor<ServiceContract> contract = ArgumentCaptor.forClass(ServiceContract.class);
        verify(contracts).save(contract.capture());
        assertEquals("CT-2026-0148", contract.getValue().getContractNumber());
        assertEquals(148L, saleId);
        ArgumentCaptor<FinancialTransaction> income = ArgumentCaptor.forClass(FinancialTransaction.class);
        verify(transactions).save(income.capture());
        assertEquals(TransactionType.INCOME, income.getValue().getTransactionType());
        assertEquals(0, new BigDecimal("100000").compareTo(income.getValue().getAmount()));
        verify(accessLog).record(eq(5L), anyString());
    }

    // ---------- abonos ----------

    @Test
    void paymentAboveTheBalanceIsRejectedAndReportsIt() {
        // Caso: venta de $300.000 con abonos de $100.000 y $50.000; el saldo es $150.000.
        Sale sale = sale(300000);
        when(sales.findDetailById(148L)).thenReturn(Optional.of(sale));
        when(transactions.sumIncomeBySale(148L, TransactionType.INCOME, TransactionStatus.ACTIVE))
                .thenReturn(new BigDecimal("150000"));

        InstallmentExceedsBalanceException error = assertThrows(InstallmentExceedsBalanceException.class,
                () -> service.registerPayment(new SalePaymentData(148L, new BigDecimal("150001"), null)));
        assertEquals(0, new BigDecimal("150000").compareTo(error.getOutstandingBalance()));

        service.registerPayment(new SalePaymentData(148L, new BigDecimal("150000"), null));
        verify(transactions).save(any(FinancialTransaction.class));
    }

    @Test
    void voidedSaleDoesNotReceivePayments() {
        Sale sale = sale(300000);
        sale.setStatus(SaleStatus.VOIDED);
        when(sales.findDetailById(148L)).thenReturn(Optional.of(sale));
        assertThrows(InvalidDataException.class,
                () -> service.registerPayment(new SalePaymentData(148L, new BigDecimal("1000"), null)));
    }

    @Test
    void voidingKeepsTheSaleAndVoidsItsPayments() {
        Sale sale = sale(300000);
        FinancialTransaction payment = new FinancialTransaction();
        payment.setStatus(TransactionStatus.ACTIVE);
        when(sales.findDetailById(148L)).thenReturn(Optional.of(sale));
        when(transactions.findIncomeBySale(148L, TransactionType.INCOME)).thenReturn(List.of(payment));
        when(details.findBySale(148L)).thenReturn(List.of());

        service.voidSale(148L);

        assertEquals(SaleStatus.VOIDED, sale.getStatus());
        assertEquals(TransactionStatus.VOIDED, payment.getStatus());
        verify(sales, never()).delete(any());
    }

    // ---------- filtro ----------

    @Test
    void productFilterShowsOnlySalesWithoutAServiceContract() {
        // Caso: elegir «Venta»; PASA si solo aparecen ventas sin contrato.
        List<SaleRow> rows = List.of(
                row(1L, SaleKind.PRODUCT, PaymentState.PAID, "venta sin servicio"),
                row(2L, SaleKind.PLAN, PaymentState.WITH_BALANCE, "jose rios"),
                row(3L, SaleKind.PRIVATE, PaymentState.VOIDED, "betty ovalle"));
        assertEquals(List.of(1L), ContractService.filter(rows, SaleType.PRODUCT, null, "")
                .stream().map(row -> row.saleId()).toList());
        assertEquals(List.of(2L), ContractService.filter(rows, null, PaymentState.WITH_BALANCE, "")
                .stream().map(row -> row.saleId()).toList());
        assertEquals(List.of(3L), ContractService.filter(rows, null, null, "Óvalle")
                .stream().map(row -> row.saleId()).toList());
    }

    // ---------- ayudas ----------

    private static ContractData privateService(BigDecimal base, Long roomId, int buses, BigDecimal initial) {
        return new ContractData(SaleKind.PRIVATE, 10L, 20L, null, 3L, base, null, roomId,
                roomId == null ? null : TODAY.atTime(8, 0), roomId == null ? null : TODAY.atTime(18, 0),
                null, buses, List.of(), initial);
    }

    private static SaleRow row(Long id, SaleKind kind, PaymentState state, String text) {
        return new SaleRow(id, "%04d".formatted(id), kind, state, null, BigDecimal.TEN, BigDecimal.ONE,
                BigDecimal.ONE, null, null, text, "", ContractService.normalize(text));
    }

    private static Client client(Long id, ClientType type) {
        Client client = new Client();
        client.setId(id);
        client.setName(type == ClientType.CORPORATE ? "Colpensiones" : "Carmen");
        client.setLastname(type == ClientType.CORPORATE ? "" : "Villazón");
        client.setClientType(type);
        return client;
    }

    private static WakeRoom room(boolean special) {
        WakeRoom room = new WakeRoom();
        room.setId(1L);
        room.setName("Sala Edén");
        room.setSurchargeApplies(special);
        return room;
    }

    private static InventoryItem item(Long id, String name, String price, int stock) {
        InventoryItem item = new InventoryItem();
        item.setId(id);
        item.setName(name);
        item.setUnitPrice(new BigDecimal(price));
        item.setStockQuantity(stock);
        return item;
    }

    private static Sale sale(long total) {
        Sale sale = new Sale();
        sale.setId(148L);
        sale.setTotalAmount(BigDecimal.valueOf(total));
        sale.setStatus(SaleStatus.ACTIVE);
        return sale;
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
}
