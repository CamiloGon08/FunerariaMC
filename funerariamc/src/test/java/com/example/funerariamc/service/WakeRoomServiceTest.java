package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Manager;
import com.example.funerariamc.model.Role;
import com.example.funerariamc.model.Sale;
import com.example.funerariamc.model.SaleDetail;
import com.example.funerariamc.model.ServiceContract;
import com.example.funerariamc.model.WakeRoom;
import com.example.funerariamc.model.enums.ContractStatus;
import com.example.funerariamc.model.enums.SaleStatus;
import com.example.funerariamc.model.enums.TransactionStatus;
import com.example.funerariamc.model.enums.TransactionType;
import com.example.funerariamc.persistence.DeathCertificateRepository;
import com.example.funerariamc.persistence.FinancialTransactionRepository;
import com.example.funerariamc.persistence.SaleDetailRepository;
import com.example.funerariamc.persistence.SaleRepository;
import com.example.funerariamc.persistence.ServiceContractRepository;
import com.example.funerariamc.persistence.ServiceTransportRepository;
import com.example.funerariamc.persistence.WakeRoomRepository;
import com.example.funerariamc.service.WakeRoomService.RoomCard;
import com.example.funerariamc.service.WakeRoomService.RoomState;
import com.example.funerariamc.service.WakeRoomService.Wake;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Criterios de aceptación: registrar salas, asignarlas sin cruces con su recargo y ver la ocupación por día.
 */
class WakeRoomServiceTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    /** "Ahora" de las pruebas: 24/10/2026 a las 10:00. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 24, 10, 0);

    private final WakeRoomRepository rooms = mock(WakeRoomRepository.class);
    private final ServiceContractRepository contracts = mock(ServiceContractRepository.class);
    private final ServiceTransportRepository transports = mock(ServiceTransportRepository.class);
    private final DeathCertificateRepository certificates = mock(DeathCertificateRepository.class);
    private final SaleRepository sales = mock(SaleRepository.class);
    private final SaleDetailRepository details = mock(SaleDetailRepository.class);
    private final FinancialTransactionRepository transactions = mock(FinancialTransactionRepository.class);
    private final SaleParameters parameters = mock(SaleParameters.class);
    private final AccessLogService accessLog = mock(AccessLogService.class);
    private final SessionContext session = new SessionContext();
    private final WakeRoomService service = new WakeRoomService(rooms, contracts, transports, certificates, sales,
            details, transactions, parameters, accessLog, session,
            Clock.fixed(NOW.atZone(BOGOTA).toInstant(), BOGOTA));

    @BeforeEach
    void loginAsSecretary() {
        loginAs(RoleNames.SECRETARY);
        when(rooms.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(contracts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ---------- tablero ----------

    @Test
    void wakeFromEightToSixIsInWakeThatDayAndAvailableTheNext() {
        // Caso: velación en Sala Edén de 08:00 a 18:00 del 24/10.
        WakeRoom eden = room(1L, "Sala Edén", true);
        Wake wake = wake(LocalDateTime.of(2026, 10, 24, 8, 0), LocalDateTime.of(2026, 10, 24, 18, 0));

        RoomCard sameDay = WakeRoomService.card(eden, 1, List.of(wake), LocalDate.of(2026, 10, 24), NOW);
        RoomCard nextDay = WakeRoomService.card(eden, 1, List.of(), LocalDate.of(2026, 10, 25), NOW);

        assertEquals(RoomState.IN_WAKE, sameDay.state());
        assertEquals(RoomState.AVAILABLE, nextDay.state());
    }

    @Test
    void laterWakeTodayIsReservedAndFinishedWakeLeavesTheRoomFree() {
        WakeRoom paz = room(2L, "Sala Paz", false);
        Wake later = wake(LocalDateTime.of(2026, 10, 24, 15, 30), LocalDateTime.of(2026, 10, 25, 9, 0));
        Wake finished = wake(LocalDateTime.of(2026, 10, 23, 20, 0), LocalDateTime.of(2026, 10, 24, 8, 0));

        RoomCard reserved = WakeRoomService.card(paz, 2, List.of(later), NOW.toLocalDate(), NOW);
        RoomCard free = WakeRoomService.card(paz, 2, List.of(finished), NOW.toLocalDate(), NOW);

        assertEquals(RoomState.RESERVED, reserved.state());
        assertEquals(RoomState.AVAILABLE, free.state());
        assertEquals(LocalDateTime.of(2026, 10, 24, 8, 0), free.freeFrom());
    }

    @Test
    void boardNeedsARoleWithAccessToSalas() {
        loginAs(RoleNames.CEMETERY_ADMIN);
        assertThrows(UnauthorizedActionException.class, () -> service.board(NOW.toLocalDate()));
    }

    // ---------- registrar ----------

    @Test
    void roomWithZeroCapacityIsRejected() {
        // Caso: registrar una sala con capacidad 0; PASA si se rechaza.
        loginAs(RoleNames.MASTER_ADMIN);
        assertThrows(InvalidDataException.class,
                () -> service.register(new WakeRoomData("Sala Luz", 0, new BigDecimal("400000"), false)));
        verify(rooms, never()).save(any());
    }

    @Test
    void onlyTheMasterAdminRegistersRooms() {
        assertThrows(UnauthorizedActionException.class,
                () -> service.register(new WakeRoomData("Sala Luz", 40, new BigDecimal("400000"), false)));
    }

    @Test
    void registerSavesTheRoomAndLogsIt() {
        loginAs(RoleNames.MASTER_ADMIN);
        WakeRoom saved = service.register(new WakeRoomData("  Sala   Luz ", 40, new BigDecimal("400000"), true));
        assertEquals("Sala Luz", saved.getName());
        assertTrue(saved.getSurchargeApplies());
        verify(accessLog).record(5L, AccessLogService.WAKE_ROOM_REGISTERED + "Sala Luz");
    }

    // ---------- asignar ----------

    @Test
    void specialRoomAddsTheParameterizedSurchargeAsItsOwnLine() {
        // Caso: sala especial con recargo de $50.000; PASA si se agrega una línea de $50.000.
        WakeRoom eden = room(1L, "Sala Edén", true);
        ServiceContract contract = contract(30L);
        Sale sale = new Sale();
        sale.setId(70L);
        sale.setTotalAmount(new BigDecimal("200000"));
        when(contracts.findDetailById(30L)).thenReturn(Optional.of(contract));
        when(rooms.findById(1L)).thenReturn(Optional.of(eden));
        when(sales.findByContract(30L, SaleStatus.ACTIVE)).thenReturn(List.of(sale));
        when(details.findBySale(70L)).thenReturn(List.of());
        when(parameters.wakeRoomSurcharge()).thenReturn(new BigDecimal("50000"));

        service.assign(new AssignWakeData(1L, 30L, LocalDateTime.of(2026, 10, 24, 8, 0),
                LocalDateTime.of(2026, 10, 24, 18, 0)));

        ArgumentCaptor<SaleDetail> line = ArgumentCaptor.forClass(SaleDetail.class);
        verify(details).save(line.capture());
        assertEquals(0, new BigDecimal("50000").compareTo(line.getValue().getSubtotal()));
        assertTrue(line.getValue().getDescription().startsWith(WakeRoomService.SURCHARGE_LINE));
        assertEquals(0, new BigDecimal("250000").compareTo(sale.getTotalAmount()));
        assertSame(eden, contract.getWakeRoom());
    }

    @Test
    void overlappingWakeInTheSameRoomIsRejected() {
        WakeRoom eden = room(1L, "Sala Edén", false);
        when(contracts.findDetailById(30L)).thenReturn(Optional.of(contract(30L)));
        when(rooms.findById(1L)).thenReturn(Optional.of(eden));
        when(contracts.existsWakeOverlap(eq(1L), eq(30L), any(), any(), eq(ContractStatus.CANCELLED))).thenReturn(true);
        when(contracts.findOverlappingNumbers(eq(1L), eq(30L), any(), any(), eq(ContractStatus.CANCELLED), any()))
                .thenReturn(List.of("CT-2026-0142"));

        InvalidDataException error = assertThrows(InvalidDataException.class, () -> service.assign(new AssignWakeData(
                1L, 30L, LocalDateTime.of(2026, 10, 24, 12, 0), LocalDateTime.of(2026, 10, 24, 20, 0))));
        assertTrue(error.getMessage().contains("CT-2026-0142"));
        verify(contracts, never()).save(any());
    }

    @Test
    void departureMustBeAfterArrival() {
        assertThrows(InvalidDataException.class, () -> service.assign(new AssignWakeData(1L, 30L,
                LocalDateTime.of(2026, 10, 24, 18, 0), LocalDateTime.of(2026, 10, 24, 8, 0))));
    }

    @Test
    void movingToARegularRoomRemovesTheSurchargeLine() {
        WakeRoom eden = room(1L, "Sala Edén", true);
        WakeRoom paz = room(2L, "Sala Paz", false);
        ServiceContract contract = contract(30L);
        contract.setWakeRoom(eden);
        contract.setWakeStartAt(LocalDateTime.of(2026, 10, 24, 8, 0));
        contract.setWakeEndAt(LocalDateTime.of(2026, 10, 24, 18, 0));
        Sale sale = new Sale();
        sale.setId(70L);
        sale.setTotalAmount(new BigDecimal("250000"));
        SaleDetail surcharge = WakeRoomService.surchargeLine(sale, eden, new BigDecimal("50000"));
        when(contracts.findDetailById(30L)).thenReturn(Optional.of(contract));
        when(rooms.findById(2L)).thenReturn(Optional.of(paz));
        when(sales.findByContract(30L, SaleStatus.ACTIVE)).thenReturn(List.of(sale));
        when(details.findBySale(70L)).thenReturn(List.of(surcharge));
        when(transactions.sumIncomeBySale(70L, TransactionType.INCOME, TransactionStatus.ACTIVE))
                .thenReturn(BigDecimal.ZERO);

        service.changeRoom(30L, 2L);

        verify(details).deleteAll(List.of(surcharge));
        assertEquals(0, new BigDecimal("200000").compareTo(sale.getTotalAmount()));
        assertSame(paz, contract.getWakeRoom());
        verify(accessLog).record(eq(5L), eq(AccessLogService.WAKE_ROOM_CHANGED + "CT-2026-0001 Sala Edén > Sala Paz"));
    }

    // ---------- ayudas ----------

    private static WakeRoom room(Long id, String name, boolean special) {
        WakeRoom room = new WakeRoom();
        room.setId(id);
        room.setName(name);
        room.setCapacity(60);
        room.setBasePrice(new BigDecimal("500000"));
        room.setSurchargeApplies(special);
        return room;
    }

    private static Wake wake(LocalDateTime start, LocalDateTime end) {
        return new Wake(30L, "CT-2026-0001", "José Antonio Ríos Pérez", "Familia Ríos", null, start, end, true,
                null, null);
    }

    private static ServiceContract contract(Long id) {
        ServiceContract contract = new ServiceContract();
        contract.setId(id);
        contract.setContractNumber("CT-2026-0001");
        contract.setStatus(ContractStatus.ACTIVE);
        return contract;
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
