package com.example.funerariamc.service;

import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.FinancialTransaction;
import com.example.funerariamc.model.FuneralSalesperson;
import com.example.funerariamc.model.InventoryItem;
import com.example.funerariamc.model.Receptionist;
import com.example.funerariamc.model.Role;
import com.example.funerariamc.model.enums.CremationBatchStatus;
import com.example.funerariamc.model.enums.StopType;
import com.example.funerariamc.model.enums.TransactionStatus;
import com.example.funerariamc.model.enums.TransactionType;
import com.example.funerariamc.model.enums.TransportStatus;
import com.example.funerariamc.model.enums.VaultStatus;
import com.example.funerariamc.persistence.CremationBatchRepository;
import com.example.funerariamc.persistence.FinancialTransactionRepository;
import com.example.funerariamc.persistence.InventoryItemRepository;
import com.example.funerariamc.persistence.VaultRepository;
import com.example.funerariamc.service.HomeService.Today;
import com.example.funerariamc.service.TransportService.DocStatus;
import com.example.funerariamc.service.TransportService.Stop;
import com.example.funerariamc.service.TransportService.Transfer;
import com.example.funerariamc.service.TransportService.Unit;
import com.example.funerariamc.service.TransportService.VehicleCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class HomeServiceTest {

    // Hoy es domingo 8 de noviembre de 2026, 10:00 en Colombia.
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 8);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-08T15:00:00Z"), AccessLogService.APP_ZONE);

    private final WakeRoomService rooms = mock(WakeRoomService.class);
    private final TransportService transport = mock(TransportService.class);
    private final CremationBatchRepository batches = mock(CremationBatchRepository.class);
    private final VaultRepository vaults = mock(VaultRepository.class);
    private final InventoryItemRepository inventory = mock(InventoryItemRepository.class);
    private final FinancialTransactionRepository transactions = mock(FinancialTransactionRepository.class);
    private final SessionContext session = new SessionContext();
    private final HomeService service =
            new HomeService(rooms, transport, batches, vaults, inventory, transactions, session, clock);

    @BeforeEach
    void setUp() {
        when(rooms.board(TODAY)).thenReturn(new WakeRoomService.Board(TODAY, true, List.of(),
                new WakeRoomService.Kpis(3, 2, 0, "", 1, 0, List.of())));
        when(transport.screen()).thenReturn(new TransportService.Screen(TODAY, LocalDateTime.now(clock), null,
                List.of(), List.of(), List.of(), true));
        when(batches.findBatchesBetween(any(), any())).thenReturn(List.of());
        when(inventory.findAtOrBelowMinimum(any())).thenReturn(List.of());
        when(transactions.findInRange(any(), any())).thenReturn(List.of());
    }

    @Test
    void onlyMasterAndSecretarySeeTheBoard() {
        login(new FuneralSalesperson(), RoleNames.SALESPERSON);
        assertFalse(service.canSeeHome());
        assertThrows(UnauthorizedActionException.class, service::today);

        login(new Receptionist(), RoleNames.SECRETARY);
        assertTrue(service.canSeeHome());
        assertFalse(service.canSeeMoney());
    }

    @Test
    void secretaryDoesNotSeeTodaysPayments() {
        login(new Receptionist(), RoleNames.SECRETARY);
        Today today = service.today();
        assertNull(today.payments());
        verify(transactions, never()).findInRange(any(), any());
    }

    @Test
    void masterSeesTodaysIncomeWithoutVoidedOnes() {
        login(new Receptionist(), RoleNames.MASTER_ADMIN);
        when(transactions.findInRange(any(), any())).thenReturn(List.of(
                tx(TransactionType.INCOME, TransactionStatus.ACTIVE, "150000"),
                tx(TransactionType.INCOME, TransactionStatus.VOIDED, "90000"),
                tx(TransactionType.EXPENSE, TransactionStatus.ACTIVE, "40000"),
                tx(TransactionType.INCOME, TransactionStatus.ACTIVE, "50000")));
        Today today = service.today();
        assertEquals(0, new BigDecimal("200000").compareTo(today.payments().total()));
        assertEquals(2, today.payments().count());
    }

    @Test
    void countsServicesTransfersAndCremations() {
        login(new Receptionist(), RoleNames.SECRETARY);
        Transfer inRoute = transfer("C-1", TODAY.atTime(8, 0), TransportStatus.IN_PROGRESS, 5L);
        Transfer later = transfer("C-2", TODAY.atTime(15, 30), TransportStatus.SCHEDULED, 6L);
        Transfer tomorrow = transfer("C-3", TODAY.plusDays(1).atTime(9, 0), TransportStatus.SCHEDULED, 7L);
        when(transport.screen()).thenReturn(new TransportService.Screen(TODAY, LocalDateTime.now(clock), null,
                List.of(), List.of(), List.of(inRoute, later, tomorrow), true));
        when(batches.findBatchesBetween(TODAY.atStartOfDay(), TODAY.plusDays(1).atStartOfDay())).thenReturn(List.of(
                batch(1L, TODAY.atTime(7, 0), CremationBatchStatus.FINISHED, 1),
                batch(2L, TODAY.atTime(14, 0), CremationBatchStatus.SCHEDULED, 2)));

        Today today = service.today();
        assertEquals(2, today.wakesNow());
        assertEquals(3, today.servicesNow());
        assertEquals(1, today.transfersInRoute());
        assertEquals(2, today.transfersToday());
        assertEquals(3, today.cremationsToday());
        assertEquals("H1 · próxima 14:00", today.nextCremation());
        // Agenda ordenada por hora: cremación 7:00, traslado 8:00, cremación 14:00, traslado 15:30.
        assertEquals(List.of("07:00", "08:00", "14:00", "15:30"),
                today.agenda().stream().map(a -> a.at().toLocalTime().toString()).toList());
    }

    @Test
    void alertsExpiredDocumentsMissingDriverOverdueVaultsAndLowStock() {
        login(new Receptionist(), RoleNames.SECRETARY);
        VehicleCard hearse = new VehicleCard(1L, "ABC123", null, 4, null, null,
                new DocStatus(TODAY.minusDays(1), true, false), new DocStatus(TODAY.plusYears(1), false, false),
                null, null, null, null);
        Transfer noDriver = transfer("C-9", TODAY.atTime(16, 0), TransportStatus.SCHEDULED, null);
        when(transport.screen()).thenReturn(new TransportService.Screen(TODAY, LocalDateTime.now(clock), null,
                List.of(hearse), List.of(), List.of(noDriver), true));
        when(vaults.countByStatus(VaultStatus.OVERDUE)).thenReturn(0L);
        InventoryItem candles = new InventoryItem();
        candles.setName("Velones");
        candles.setStockQuantity(1);
        candles.setMinimumStock(5);
        when(inventory.findAtOrBelowMinimum(any())).thenReturn(List.of(candles));

        List<String> titles = service.today().alerts().stream().map(HomeService.Alert::title).toList();
        assertEquals(List.of("SOAT vencido · ABC123", "Traslado C-9 sin conductor", "Inventario en el mínimo · Velones"),
                titles);
        verify(vaults, never()).findByStatusWithCemetery(eq(VaultStatus.OVERDUE), any());
    }

    private void login(com.example.funerariamc.model.Employee employee, String role) {
        employee.setRole(new Role(1L, role, null));
        AppUser user = new AppUser();
        user.setId(1L);
        user.setEmployee(employee);
        session.start(user);
    }

    private static FinancialTransaction tx(TransactionType type, TransactionStatus status, String amount) {
        FinancialTransaction t = new FinancialTransaction();
        t.setTransactionType(type);
        t.setStatus(status);
        t.setAmount(new BigDecimal(amount));
        return t;
    }

    private static Transfer transfer(String number, LocalDateTime at, TransportStatus status, Long driverId) {
        Unit unit = new Unit(1L, 1L, "XYZ987", null, driverId, null, true, status);
        Stop end = new Stop(1L, 1, StopType.DESTINATION, "Cementerio Central", null, null, null);
        return new Transfer(1L, number, "Difunto " + number, at, status,
                status == TransportStatus.IN_PROGRESS ? at : null, null, List.of(unit), List.of(end), null);
    }

    private static CremationBatchRepository.DayBatch batch(Long id, LocalDateTime at, CremationBatchStatus status,
                                                           long cremations) {
        return new CremationBatchRepository.DayBatch() {
            public Long getBatchId() { return id; }
            public LocalDateTime getScheduledDate() { return at; }
            public String getFurnaceCode() { return "H1"; }
            public CremationBatchStatus getStatus() { return status; }
            public long getCremations() { return cremations; }
        };
    }
}
