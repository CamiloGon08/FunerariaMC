package com.example.funerariamc.service;

import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.FinancialTransaction;
import com.example.funerariamc.model.FuneralSalesperson;
import com.example.funerariamc.model.Receptionist;
import com.example.funerariamc.model.Role;
import com.example.funerariamc.model.WakeRoom;
import com.example.funerariamc.model.enums.SaleStatus;
import com.example.funerariamc.model.enums.SaleType;
import com.example.funerariamc.model.enums.TransactionStatus;
import com.example.funerariamc.model.enums.TransactionType;
import com.example.funerariamc.model.enums.VaultStatus;
import com.example.funerariamc.persistence.FinancialTransactionRepository;
import com.example.funerariamc.persistence.SaleRepository;
import com.example.funerariamc.persistence.ServiceContractRepository;
import com.example.funerariamc.persistence.StaffOptionRepository;
import com.example.funerariamc.persistence.VaultRepository;
import com.example.funerariamc.persistence.WakeRoomRepository;
import com.example.funerariamc.service.AnalyticsService.Overview;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AnalyticsServiceTest {

    private static final FinancePeriod NOVEMBER = FinancePeriod.month(YearMonth.of(2026, 11));
    // Hoy es domingo 8 de noviembre de 2026, 10:00 en Colombia.
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-08T15:00:00Z"), AccessLogService.APP_ZONE);

    private final SaleRepository sales = mock(SaleRepository.class);
    private final ServiceContractRepository contracts = mock(ServiceContractRepository.class);
    private final WakeRoomRepository rooms = mock(WakeRoomRepository.class);
    private final VaultRepository vaults = mock(VaultRepository.class);
    private final StaffOptionRepository staff = mock(StaffOptionRepository.class);
    private final FinancialTransactionRepository transactions = mock(FinancialTransactionRepository.class);
    private final AccessLogService audit = mock(AccessLogService.class);
    private final SessionContext session = new SessionContext();
    private final AnalyticsService service =
            new AnalyticsService(sales, contracts, rooms, vaults, staff, transactions, audit, session, clock);

    @BeforeEach
    void setUp() {
        login(RoleNames.MASTER_ADMIN);
    }

    @Test
    void onlyMasterAdminOpensThePanel() {
        login(RoleNames.SECRETARY);
        assertThrows(UnauthorizedActionException.class, () -> service.overview(NOVEMBER));
        assertThrows(UnauthorizedActionException.class, () -> service.exportPdf(NOVEMBER, Path.of("x.pdf")));
    }

    @Test
    void servicesLeaveOutProductSalesAndVoidedOnes() {
        List<SaleRepository.SaleRow> rows = List.of(
                sale(SaleType.CONTRACT, 3L, SaleStatus.ACTIVE, "3000000", 2),
                sale(SaleType.PRIVATE, null, SaleStatus.ACTIVE, "2000000", 3),
                sale(SaleType.PRODUCT, null, SaleStatus.ACTIVE, "100000", 4),
                sale(SaleType.CONTRACT, null, SaleStatus.VOIDED, "9000000", 5));
        when(sales.findRowsBetween(any(), any(), eq(TransactionType.INCOME), eq(TransactionStatus.ACTIVE)))
                .thenReturn(rows);

        Overview o = service.overview(NOVEMBER);
        assertEquals(2, o.kpis().services());
        assertEquals(0, new BigDecimal("2500000").compareTo(o.kpis().averageValue()));
        // Mes actual: plan, particular y venta de productos (la anulada no cuenta).
        AnalyticsService.KindMonth last = o.kindMonths().get(o.kindMonths().size() - 1);
        assertEquals(YearMonth.of(2026, 11), last.month());
        assertArrayEquals(new long[]{1, 0, 1, 1}, last.counts());
        assertEquals(AnalyticsService.KIND_MONTHS, o.kindMonths().size());
    }

    @Test
    void yearChartStopsAtTheCurrentMonth() {
        Overview o = service.overview(FinancePeriod.year(2026));
        assertEquals(YearMonth.of(2026, 11), o.kindMonths().get(o.kindMonths().size() - 1).month());
    }

    @Test
    void vaultOccupancyCountsEveryNonAvailableVaultAndShowsOverdueApart() {
        List<VaultRepository.CemeteryCount> counts = List.of(
                count(1L, "Central", VaultStatus.AVAILABLE, 6),
                count(1L, "Central", VaultStatus.OCCUPIED, 3),
                count(1L, "Central", VaultStatus.OVERDUE, 1));
        when(vaults.countByCemeteryAndStatus()).thenReturn(counts);

        Overview o = service.overview(NOVEMBER);
        AnalyticsService.CemeteryOccupancy central = o.cemeteries().get(0);
        assertEquals(10, central.total());
        assertEquals(4, central.occupied());
        assertEquals(1, central.overdue());
        assertEquals(40, central.percent());
        assertEquals(40, o.kpis().vaultOccupancy());
    }

    @Test
    void roomHeatOnlyCountsHoursUntilNow() {
        WakeRoom room = new WakeRoom();
        room.setId(1L);
        room.setName("Sala 1");
        when(rooms.findAllByOrderByIdAsc()).thenReturn(List.of(room));
        // Velación del domingo 1 de noviembre todo el día: 24 h de un domingo; hasta hoy hay 2 domingos (24 + 10 h).
        ServiceContractRepository.WakeRow wake = mock(ServiceContractRepository.WakeRow.class);
        when(wake.getRoomId()).thenReturn(1L);
        when(wake.getWakeStartAt()).thenReturn(LocalDateTime.of(2026, 11, 1, 0, 0));
        when(wake.getWakeEndAt()).thenReturn(LocalDateTime.of(2026, 11, 2, 0, 0));
        when(contracts.findWakes(any(), any(), any())).thenReturn(List.of(wake));

        Overview o = service.overview(NOVEMBER);
        int[] percents = o.rooms().get(0).percents();
        assertEquals(Math.round(24 * 100.0 / 34), percents[6]);
        assertEquals(0, percents[0]);
        // Del 1 al 8 de noviembre a las 10:00 van 7 días y 10 horas = 178 h, de ellas 24 ocupadas.
        assertEquals(Math.round(24 * 100.0 / 178), o.kpis().roomOccupancy());
    }

    @Test
    void commissionsComeFromTheRegisteredExpenses() {
        FuneralSalesperson seller = new FuneralSalesperson();
        seller.setId(20L);
        seller.setName("Ana");
        seller.setLastname("Ruiz");
        when(staff.findAllSalespeople()).thenReturn(List.of(seller));
        FinancialTransaction paid = new FinancialTransaction();
        paid.setTransactionType(TransactionType.EXPENSE);
        paid.setStatus(TransactionStatus.ACTIVE);
        paid.setDescription(FinancialTransactionService.COMMISSION_PREFIX + "de 2026-11 · Ana Ruiz");
        paid.setEmployee(seller);
        paid.setAmount(new BigDecimal("250000"));
        FinancialTransaction voided = new FinancialTransaction();
        voided.setTransactionType(TransactionType.EXPENSE);
        voided.setStatus(TransactionStatus.VOIDED);
        voided.setDescription(FinancialTransactionService.COMMISSION_PREFIX + "de 2026-10 · Ana Ruiz");
        voided.setEmployee(seller);
        voided.setAmount(new BigDecimal("99000"));
        when(transactions.findInRange(any(), any())).thenReturn(List.of(paid, voided));

        Overview o = service.overview(NOVEMBER);
        assertEquals(0, new BigDecimal("250000").compareTo(o.kpis().commissions()));
        assertEquals(1, o.kpis().commissionPeople());
        assertEquals(0, new BigDecimal("250000").compareTo(o.sellers().get(0).commission()));
    }

    @Test
    void exportWritesAPdfAndLeavesItInTheLog(@TempDir Path folder) throws Exception {
        Path target = folder.resolve(AnalyticsService.suggestedName(NOVEMBER));
        service.exportPdf(NOVEMBER, target);
        byte[] bytes = Files.readAllBytes(target);
        assertEquals("%PDF", new String(bytes, 0, 4));
        assertEquals("Panel_de_analisis_2026-11-01_2026-11-30.pdf", target.getFileName().toString());
        verify(audit).record(eq(1L), eq("ANALYTICS_REPORT_EXPORTED: 2026-11-01 2026-11-30"));
        try (var files = Files.list(folder)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void pdfIsWrittenEvenWithoutData() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AnalyticsService.writePdf(service.overview(NOVEMBER), out);
        assertTrue(out.size() > 500);
    }

    private void login(String role) {
        Receptionist employee = new Receptionist();
        employee.setRole(new Role(1L, role, null));
        AppUser user = new AppUser();
        user.setId(1L);
        user.setEmployee(employee);
        session.start(user);
    }

    private static SaleRepository.SaleRow sale(SaleType type, Long planId, SaleStatus status, String total, int day) {
        SaleRepository.SaleRow row = mock(SaleRepository.SaleRow.class);
        when(row.getSaleType()).thenReturn(type);
        when(row.getPlanId()).thenReturn(planId);
        when(row.getStatus()).thenReturn(status);
        when(row.getTotal()).thenReturn(new BigDecimal(total));
        // Hora de la BD (UTC): mediodía en Colombia.
        when(row.getSaleDate()).thenReturn(LocalDateTime.of(2026, 11, day, 17, 0));
        return row;
    }

    private static VaultRepository.CemeteryCount count(Long id, String name, VaultStatus status, long n) {
        VaultRepository.CemeteryCount c = mock(VaultRepository.CemeteryCount.class);
        when(c.getCemeteryId()).thenReturn(id);
        when(c.getCemeteryName()).thenReturn(name);
        when(c.getStatus()).thenReturn(status);
        when(c.getVaults()).thenReturn(n);
        return c;
    }
}
