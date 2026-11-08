package com.example.funerariamc.service;

import com.example.funerariamc.exception.CremationNotAuthorizedException;
import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Cremation;
import com.example.funerariamc.model.CremationBatch;
import com.example.funerariamc.model.CremationProvider;
import com.example.funerariamc.model.CremationPurchase;
import com.example.funerariamc.model.Deceased;
import com.example.funerariamc.model.Manager;
import com.example.funerariamc.model.Role;
import com.example.funerariamc.model.ServiceContract;
import com.example.funerariamc.model.Thanatopractor;
import com.example.funerariamc.model.enums.ContractStatus;
import com.example.funerariamc.model.enums.CremationBatchStatus;
import com.example.funerariamc.model.enums.CremationPurchaseStatus;
import com.example.funerariamc.model.enums.DeathType;
import com.example.funerariamc.model.enums.DocumentType;
import com.example.funerariamc.model.enums.EmployeeStatus;
import com.example.funerariamc.model.enums.ExhumationDestination;
import com.example.funerariamc.persistence.CremationBatchRepository;
import com.example.funerariamc.persistence.CremationBatchRepository.BoardRow;
import com.example.funerariamc.persistence.CremationProviderRepository;
import com.example.funerariamc.persistence.CremationPurchaseRepository;
import com.example.funerariamc.persistence.CremationRepository;
import com.example.funerariamc.persistence.DeceasedRepository;
import com.example.funerariamc.persistence.ServiceContractRepository;
import com.example.funerariamc.persistence.StaffOptionRepository;
import com.example.funerariamc.service.CremationService.Batch;
import com.example.funerariamc.service.CremationService.Furnace;
import com.example.funerariamc.service.CremationService.FurnaceState;
import com.example.funerariamc.service.CremationService.Kpis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Criterios de aceptación del módulo: programar
 * lotes, agregar difuntos con el control de la Fiscalía, registrar certificados y ver
 * los hornos o proveedores con las cremaciones del día.
 */
class CremationServiceTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    /** "Ahora" de las pruebas: 06/11/2026 a las 10:00. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 10, 0);

    private final CremationBatchRepository batches = mock(CremationBatchRepository.class);
    private final CremationRepository cremations = mock(CremationRepository.class);
    private final DeceasedRepository deceasedRepository = mock(DeceasedRepository.class);
    private final ServiceContractRepository contracts = mock(ServiceContractRepository.class);
    private final StaffOptionRepository staff = mock(StaffOptionRepository.class);
    private final CremationProviderRepository providers = mock(CremationProviderRepository.class);
    private final CremationPurchaseRepository purchases = mock(CremationPurchaseRepository.class);
    private final AccessLogService accessLog = mock(AccessLogService.class);
    private final SessionContext session = new SessionContext();
    private final CremationService service = new CremationService(batches, cremations, deceasedRepository,
            contracts, staff, providers, purchases, accessLog, session, Clock.fixed(NOW.atZone(BOGOTA).toInstant(), BOGOTA));

    @BeforeEach
    void loginAsCemeteryAdmin() {
        loginAs(RoleNames.CEMETERY_ADMIN);
        when(batches.save(any())).thenAnswer(invocation -> {
            CremationBatch batch = invocation.getArgument(0);
            if (batch.getId() == null) {
                batch.setId(33L);
            }
            return batch;
        });
        when(cremations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cremations.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(contracts.findByDeceasedId(anyLong())).thenReturn(Optional.empty());
        // Por defecto el proveedor existe, está activo y tiene un cupo libre en la compra CP-0003.
        when(providers.findByCode(anyString())).thenAnswer(i -> Optional.of(provider(i.getArgument(0), true)));
        when(providers.lockByCode(anyString())).thenAnswer(i -> Optional.of(provider(i.getArgument(0), true)));
        when(purchases.findWithFreeSlots(anyLong(), eq(CremationPurchaseStatus.RECEIVED), any()))
                .thenReturn(List.of(slot));
    }

    private final CremationPurchase slot = purchase(3L);

    private static CremationProvider provider(String code, boolean active) {
        return new CremationProvider(code.equals("JARDINES-01") ? 2L : 1L, code, "Proveedor " + code, 2, active);
    }

    private static CremationPurchase purchase(Long id) {
        CremationPurchase purchase = new CremationPurchase();
        purchase.setId(id);
        purchase.setStatus(CremationPurchaseStatus.RECEIVED);
        purchase.setQuantity(10);
        return purchase;
    }

    // ---------- programar el lote ----------

    @Test
    void batchScheduledYesterdayIsRejected() {
        // Prueba de Jira: programar un lote con fecha de ayer; PASA si el sistema lo rechaza.
        assertThrows(InvalidDataException.class,
                () -> service.schedule(new CremationBatchData("BAQ-01", NOW.minusDays(1))));
        verify(batches, never()).save(any());
    }

    @Test
    void scheduleSavesTheBatchAsScheduledAndLogsIt() {
        Batch batch = service.schedule(new CremationBatchData("  BAQ   01 ", NOW.plusHours(5)));
        assertEquals("L-0033", batch.code());
        assertEquals("BAQ 01", batch.furnaceCode());
        assertEquals(CremationBatchStatus.SCHEDULED, batch.status());
        verify(accessLog).record(5L, AccessLogService.CREMATION_BATCH_SCHEDULED + "L-0033 BAQ 01");
    }

    @Test
    void schedulingUsesTheSameUppercaseCodeAsTheProviderRegistry() {
        when(providers.findByCode("baq-01")).thenReturn(Optional.empty());

        Batch batch = service.schedule(new CremationBatchData(" baq-01 ", NOW.plusHours(1)));

        assertEquals("BAQ-01", batch.furnaceCode());
        verify(providers).findByCode("BAQ-01");
    }

    @Test
    void furnaceCodeIsRequiredAndFitsTheColumn() {
        assertThrows(InvalidDataException.class, () -> service.schedule(new CremationBatchData(" ", NOW.plusHours(1))));
        assertThrows(InvalidDataException.class,
                () -> service.schedule(new CremationBatchData("CREMATORIO-DEL-NORTE", NOW.plusHours(1))));
    }

    @Test
    void secretaryCannotOpenCremations() {
        loginAs(RoleNames.SECRETARY);
        assertThrows(UnauthorizedActionException.class, service::screen);
        assertThrows(UnauthorizedActionException.class,
                () -> service.schedule(new CremationBatchData("BAQ-01", NOW.plusHours(1))));
    }

    // ---------- reprogramar el lote ----------

    @Test
    void rescheduleChangesDateAndFurnaceAndLogsIt() {
        CremationBatch batch = givenBatch(CremationBatchStatus.SCHEDULED);
        LocalDateTime newDate = LocalDateTime.of(2026, 11, 7, 8, 0);
        service.reschedule(33L, new CremationBatchData(" JARDINES-01 ", newDate));
        assertEquals("JARDINES-01", batch.getFurnaceCode());
        assertEquals(newDate, batch.getScheduledDate());
        assertEquals(CremationBatchStatus.SCHEDULED, batch.getStatus());
        verify(accessLog).record(5L, AccessLogService.CREMATION_BATCH_RESCHEDULED
                + "L-0033 JARDINES-01 2026-11-07 08:00");
    }

    @Test
    void rescheduleOnlyWhileScheduledAndNotIntoThePast() {
        givenBatch(CremationBatchStatus.IN_PROGRESS);
        assertThrows(InvalidDataException.class,
                () -> service.reschedule(33L, new CremationBatchData("BAQ-01", NOW.plusDays(1))));
        CremationBatch batch = givenBatch(CremationBatchStatus.SCHEDULED);
        assertThrows(InvalidDataException.class,
                () -> service.reschedule(33L, new CremationBatchData("BAQ-01", NOW.minusHours(1))));
        assertThrows(InvalidDataException.class,
                () -> service.reschedule(33L, new CremationBatchData("BAQ-01", batch.getScheduledDate())));
        verify(batches, never()).save(any());
    }

    // ---------- cupos prepagados ----------

    @Test
    void batchNeedsARegisteredActiveProvider() {
        when(providers.findByCode("NUEVO-01")).thenReturn(Optional.empty());
        assertThrows(InvalidDataException.class,
                () -> service.schedule(new CremationBatchData("NUEVO-01", NOW.plusHours(2))));
        when(providers.findByCode("VIEJO-01")).thenReturn(Optional.of(provider("VIEJO-01", false)));
        assertThrows(InvalidDataException.class,
                () -> service.schedule(new CremationBatchData("VIEJO-01", NOW.plusHours(2))));
        verify(batches, never()).save(any());
    }

    @Test
    void addingTheDeceasedReservesAPrepaidSlot() {
        givenBatch(CremationBatchStatus.SCHEDULED);
        givenDeceased(42L, DeathType.NATURAL);
        givenThanatopractor(7L);
        Cremation saved = service.assign(new AssignCremationData(33L, 42L, 7L, null));
        assertSame(slot, saved.getCremationPurchase());
        verify(providers).lockByCode("BAQ-01");
    }

    @Test
    void providerWithoutFreeSlotsBlocksTheDeceased() {
        givenBatch(CremationBatchStatus.SCHEDULED);
        givenDeceased(42L, DeathType.NATURAL);
        givenThanatopractor(7L);
        when(purchases.findWithFreeSlots(anyLong(), eq(CremationPurchaseStatus.RECEIVED), any())).thenReturn(List.of());
        InvalidDataException error = assertThrows(InvalidDataException.class,
                () -> service.assign(new AssignCremationData(33L, 42L, 7L, null)));
        assertTrue(error.getMessage().contains("no tiene cremaciones prepagadas disponibles"));
        verify(cremations, never()).save(any());
    }

    @Test
    void rescheduleToAnotherProviderMovesTheSlots() {
        givenBatch(CremationBatchStatus.SCHEDULED);
        Cremation cremation = cremation(CremationBatchStatus.SCHEDULED);
        cremation.setCremationPurchase(purchase(1L));
        when(cremations.findByBatchWithDeceased(33L)).thenReturn(List.of(cremation));
        CremationPurchase jardines = purchase(8L);
        when(purchases.findWithFreeSlots(eq(2L), eq(CremationPurchaseStatus.RECEIVED), any())).thenReturn(List.of(jardines));

        service.reschedule(33L, new CremationBatchData("JARDINES-01", NOW.plusDays(1)));

        assertSame(jardines, cremation.getCremationPurchase());
    }

    @Test
    void rescheduleToAProviderWithoutSlotsIsRejected() {
        givenBatch(CremationBatchStatus.SCHEDULED);
        when(cremations.findByBatchWithDeceased(33L)).thenReturn(List.of(cremation(CremationBatchStatus.SCHEDULED)));
        when(purchases.findWithFreeSlots(eq(2L), eq(CremationPurchaseStatus.RECEIVED), any())).thenReturn(List.of());
        assertThrows(InvalidDataException.class,
                () -> service.reschedule(33L, new CremationBatchData("JARDINES-01", NOW.plusDays(1))));
        verify(batches, never()).save(any());
    }

    // ---------- agregar difuntos ----------

    @Test
    void nonNaturalDeathWithoutReleaseOrderCannotBeScheduled() {
        // Prueba de Jira: muerte NO_NATURAL sin autorización; PASA si rechaza con el mensaje.
        givenBatch(CremationBatchStatus.SCHEDULED);
        givenDeceased(42L, DeathType.NON_NATURAL);
        givenThanatopractor(7L);
        when(cremations.countReleaseOrders(42L, DocumentType.BODY_RELEASE_ORDER)).thenReturn(0L);

        CremationNotAuthorizedException error = assertThrows(CremationNotAuthorizedException.class,
                () -> service.assign(new AssignCremationData(33L, 42L, 7L, null)));
        assertEquals("Cremación no autorizada: pendiente autorización de la Fiscalía", error.getMessage());
        verify(cremations, never()).save(any());
    }

    @Test
    void nonNaturalDeathWithReleaseOrderIsAdded() {
        givenBatch(CremationBatchStatus.SCHEDULED);
        givenDeceased(42L, DeathType.NON_NATURAL);
        givenThanatopractor(7L);
        when(cremations.countReleaseOrders(42L, DocumentType.BODY_RELEASE_ORDER)).thenReturn(1L);

        Cremation saved = service.assign(new AssignCremationData(33L, 42L, 7L, null));

        assertEquals(42L, saved.getDeceased().getId());
        assertEquals(7L, saved.getThanatopractor().getId());
        assertNull(saved.getCompletedAt());
        verify(accessLog).record(5L, AccessLogService.CREMATION_ASSIGNED + "L-0033 DF-0042");
    }

    @Test
    void deceasedWithACremationCannotGetASecondOne() {
        givenBatch(CremationBatchStatus.SCHEDULED);
        givenDeceased(42L, DeathType.NATURAL);
        when(cremations.existsByDeceasedId(42L)).thenReturn(true);
        assertThrows(InvalidDataException.class, () -> service.assign(new AssignCremationData(33L, 42L, 7L, null)));
        verify(cremations, never()).save(any());
    }

    @Test
    void deceasedInAVaultOrWithAnotherDestinationIsRejected() {
        givenBatch(CremationBatchStatus.SCHEDULED);
        givenDeceased(42L, DeathType.NATURAL);
        when(cremations.isInVault(42L)).thenReturn(true);
        assertThrows(InvalidDataException.class, () -> service.assign(new AssignCremationData(33L, 42L, 7L, null)));

        when(cremations.isInVault(42L)).thenReturn(false);
        when(cremations.hasOtherDestination(42L, ExhumationDestination.CREMATION)).thenReturn(true);
        assertThrows(InvalidDataException.class, () -> service.assign(new AssignCremationData(33L, 42L, 7L, null)));
        verify(cremations, never()).save(any());
    }

    @Test
    void onlyScheduledBatchesReceiveDeceased() {
        givenBatch(CremationBatchStatus.IN_PROGRESS);
        assertThrows(InvalidDataException.class, () -> service.assign(new AssignCremationData(33L, 42L, 7L, null)));
    }

    @Test
    void deathTypeIsNeededToCheckTheAuthorization() {
        givenBatch(CremationBatchStatus.SCHEDULED);
        givenDeceased(42L, null);
        assertThrows(InvalidDataException.class, () -> service.assign(new AssignCremationData(33L, 42L, 7L, null)));
    }

    @Test
    void activeContractWithoutPreparationGetsItRegisteredByTheAdmin() {
        // El administrador registra la preparación a nombre del tanatólogo.
        givenBatch(CremationBatchStatus.SCHEDULED);
        givenDeceased(42L, DeathType.NATURAL);
        givenThanatopractor(7L);
        ServiceContract contract = contract(null, null);
        when(contracts.findByDeceasedId(42L)).thenReturn(Optional.of(contract));
        LocalDateTime finished = NOW.minusHours(3);

        service.assign(new AssignCremationData(33L, 42L, 7L, finished));

        assertEquals(finished, contract.getPreparationCompletedAt());
        assertEquals(7L, contract.getThanatopractor().getId());
        verify(contracts).save(contract);
        verify(accessLog).record(5L, AccessLogService.PREPARATION_REGISTERED + "CT-2026-0001");
    }

    @Test
    void preparationTimeIsRequiredAndNotInTheFuture() {
        givenBatch(CremationBatchStatus.SCHEDULED);
        givenDeceased(42L, DeathType.NATURAL);
        givenThanatopractor(7L);
        when(contracts.findByDeceasedId(42L)).thenReturn(Optional.of(contract(null, null)));
        assertThrows(InvalidDataException.class, () -> service.assign(new AssignCremationData(33L, 42L, 7L, null)));
        assertThrows(InvalidDataException.class,
                () -> service.assign(new AssignCremationData(33L, 42L, 7L, NOW.plusHours(1))));
    }

    @Test
    void registeredPreparationKeepsItsThanatopractor() {
        givenBatch(CremationBatchStatus.SCHEDULED);
        givenDeceased(42L, DeathType.NATURAL);
        givenThanatopractor(7L);
        Thanatopractor other = new Thanatopractor();
        other.setId(8L);
        other.setName("Hernán");
        other.setLastname("Quintero");
        when(contracts.findByDeceasedId(42L)).thenReturn(Optional.of(contract(other, NOW.minusDays(1))));

        InvalidDataException error = assertThrows(InvalidDataException.class,
                () -> service.assign(new AssignCremationData(33L, 42L, 7L, null)));
        assertFalse(error.getMessage().contains("Hernán"));
        assertFalse(error.getMessage().contains("Quintero"));
        verify(contracts, never()).save(any());
    }

    @Test
    void removeOnlyFromScheduledBatches() {
        Cremation cremation = cremation(CremationBatchStatus.IN_PROGRESS);
        when(cremations.findDetailById(90L)).thenReturn(Optional.of(cremation));
        assertThrows(InvalidDataException.class, () -> service.remove(90L));

        cremation.getCremationBatch().setStatus(CremationBatchStatus.SCHEDULED);
        service.remove(90L);
        verify(cremations).delete(cremation);
        verify(accessLog).record(5L, AccessLogService.CREMATION_REMOVED + "L-0033 DF-0042");
    }

    // ---------- entregar al horno y certificados ----------

    @Test
    void emptyBatchCannotStart() {
        givenBatch(CremationBatchStatus.SCHEDULED);
        when(cremations.findByBatchWithDeceased(33L)).thenReturn(List.of());
        assertThrows(InvalidDataException.class, () -> service.start(33L));
    }

    @Test
    void startRechecksTheReleaseOrder() {
        CremationBatch batch = givenBatch(CremationBatchStatus.SCHEDULED);
        Cremation cremation = cremation(CremationBatchStatus.SCHEDULED);
        cremation.getDeceased().setDeathType(DeathType.NON_NATURAL);
        when(cremations.findByBatchWithDeceased(33L)).thenReturn(List.of(cremation));
        when(cremations.countReleaseOrders(42L, DocumentType.BODY_RELEASE_ORDER)).thenReturn(0L);

        assertThrows(CremationNotAuthorizedException.class, () -> service.start(33L));
        assertEquals(CremationBatchStatus.SCHEDULED, batch.getStatus());
    }

    @Test
    void startMovesTheBatchToInProgress() {
        CremationBatch batch = givenBatch(CremationBatchStatus.SCHEDULED);
        when(cremations.findByBatchWithDeceased(33L)).thenReturn(List.of(cremation(CremationBatchStatus.SCHEDULED)));
        service.start(33L);
        assertEquals(CremationBatchStatus.IN_PROGRESS, batch.getStatus());
        verify(accessLog).record(5L, AccessLogService.CREMATION_BATCH_STARTED + "L-0033");
    }

    @Test
    void certificateNeedsTheBatchDeliveredToTheFurnace() {
        when(cremations.findDetailById(90L)).thenReturn(Optional.of(cremation(CremationBatchStatus.SCHEDULED)));
        assertThrows(InvalidDataException.class,
                () -> service.registerCertificate(new CremationCertificateData(90L, "CC-1001", NOW.minusHours(1))));
    }

    @Test
    void lastCertificateFinishesTheBatch() {
        Cremation cremation = cremation(CremationBatchStatus.IN_PROGRESS);
        when(cremations.findDetailById(90L)).thenReturn(Optional.of(cremation));
        when(cremations.countByCremationBatchIdAndCompletedAtIsNull(33L)).thenReturn(0L);

        service.registerCertificate(new CremationCertificateData(90L, " CC-1001 ", NOW.minusHours(1)));

        assertEquals("CC-1001", cremation.getCertificateNumber());
        assertEquals(NOW.minusHours(1), cremation.getCompletedAt());
        assertEquals(CremationBatchStatus.FINISHED, cremation.getCremationBatch().getStatus());
        verify(accessLog).record(5L, AccessLogService.CREMATION_REGISTERED + "L-0033 DF-0042 CC-1001");
    }

    @Test
    void batchStaysInProgressWhileCertificatesArePending() {
        Cremation cremation = cremation(CremationBatchStatus.IN_PROGRESS);
        when(cremations.findDetailById(90L)).thenReturn(Optional.of(cremation));
        when(cremations.countByCremationBatchIdAndCompletedAtIsNull(33L)).thenReturn(1L);
        service.registerCertificate(new CremationCertificateData(90L, "CC-1001", NOW.minusHours(1)));
        assertEquals(CremationBatchStatus.IN_PROGRESS, cremation.getCremationBatch().getStatus());
    }

    @Test
    void certificateCannotBeInTheFutureNorRegisteredTwice() {
        Cremation cremation = cremation(CremationBatchStatus.IN_PROGRESS);
        when(cremations.findDetailById(90L)).thenReturn(Optional.of(cremation));
        assertThrows(InvalidDataException.class,
                () -> service.registerCertificate(new CremationCertificateData(90L, "CC-1", NOW.plusMinutes(5))));
        cremation.setCompletedAt(NOW.minusHours(2));
        assertThrows(InvalidDataException.class,
                () -> service.registerCertificate(new CremationCertificateData(90L, "CC-1", NOW.minusHours(1))));
    }

    // ---------- pantalla ----------

    @Test
    void batchTodayAtThreeIsInTheAgendaAndIsTheFurnaceNextBatch() {
        // Prueba de Jira: lote hoy a las 15:00 en el Horno 1; aparece en la agenda y como siguiente.
        LocalDateTime three = NOW.toLocalDate().atTime(15, 0);
        List<Batch> list = CremationService.batches(List.of(
                row(33L, three, "Horno 1", CremationBatchStatus.SCHEDULED, 90L, DeathType.NATURAL, 0L, null)));
        List<Furnace> furnaces = CremationService.furnaces(list);
        Kpis kpis = CremationService.kpis(list, null, NOW.toLocalDate());

        assertEquals(1, furnaces.size());
        assertEquals(FurnaceState.SCHEDULED, furnaces.get(0).state());
        assertEquals(33L, furnaces.get(0).next().id());
        assertEquals(1, kpis.scheduledToday());
        assertEquals(1, kpis.batchesToday());
    }

    @Test
    void inProgressBatchMakesTheFurnaceBusyAndCountsPendingCertificates() {
        List<Batch> list = CremationService.batches(List.of(
                row(30L, NOW.minusHours(2), "BAQ-01", CremationBatchStatus.IN_PROGRESS, 80L, DeathType.NATURAL, 0L, null),
                row(30L, NOW.minusHours(2), "BAQ-01", CremationBatchStatus.IN_PROGRESS, 81L, DeathType.NATURAL, 0L,
                        NOW.minusHours(1)),
                row(31L, NOW.plusDays(1), "baq-01", CremationBatchStatus.SCHEDULED, null, null, null, null)));
        List<Furnace> furnaces = CremationService.furnaces(list);
        Kpis kpis = CremationService.kpis(list, null, NOW.toLocalDate());

        assertEquals(2, list.size());
        assertTrue(list.get(1).items().isEmpty());
        assertEquals(1, furnaces.size());
        assertEquals(FurnaceState.IN_USE, furnaces.get(0).state());
        assertEquals(30L, furnaces.get(0).current().id());
        assertEquals(31L, furnaces.get(0).next().id());
        assertEquals(1, kpis.inProgress());
        assertEquals(1, kpis.pendingCertificates());
    }

    @Test
    void nonNaturalWithoutReleaseOrderShowsTheBatchBlocked() {
        List<Batch> list = CremationService.batches(List.of(
                row(30L, NOW.plusHours(2), "BAQ-01", CremationBatchStatus.SCHEDULED, 80L, DeathType.NON_NATURAL, 0L, null)));
        assertTrue(list.get(0).blocked());
        assertFalse(CremationService.isAuthorized(DeathType.NON_NATURAL, false));
        assertTrue(CremationService.isAuthorized(DeathType.NON_NATURAL, true));
        assertTrue(CremationService.isAuthorized(DeathType.NATURAL, false));
    }

    @Test
    void screenUsesTwoQueries() {
        loginAs(RoleNames.MASTER_ADMIN);
        when(batches.findBoardRows(any(), any(), eq(DocumentType.BODY_RELEASE_ORDER))).thenReturn(List.of());
        CremationService.Screen screen = service.screen();
        assertTrue(screen.batches().isEmpty());
        verify(batches).findBoardRows(any(), eq(LocalDate.of(2026, 10, 7).atStartOfDay()),
                eq(DocumentType.BODY_RELEASE_ORDER));
        verify(batches).countFinished(LocalDate.of(2026, 11, 1).atStartOfDay(),
                LocalDate.of(2026, 12, 1).atStartOfDay());
        verifyNoInteractions(cremations);
    }

    // ---------- datos de prueba ----------

    private CremationBatch givenBatch(CremationBatchStatus status) {
        CremationBatch batch = new CremationBatch(33L, NOW.plusHours(4), "BAQ-01", status);
        when(batches.findById(33L)).thenReturn(Optional.of(batch));
        return batch;
    }

    private Deceased givenDeceased(Long id, DeathType type) {
        Deceased deceased = new Deceased();
        deceased.setId(id);
        deceased.setName("Luis Carlos");
        deceased.setLastname("Mendoza");
        deceased.setDeathType(type);
        when(deceasedRepository.findById(id)).thenReturn(Optional.of(deceased));
        return deceased;
    }

    private void givenThanatopractor(Long id) {
        Thanatopractor thanatopractor = new Thanatopractor();
        thanatopractor.setId(id);
        thanatopractor.setName("Laura");
        thanatopractor.setLastname("Martínez");
        thanatopractor.setStatus(EmployeeStatus.ACTIVE);
        when(staff.findById(id)).thenReturn(Optional.of(thanatopractor));
    }

    private static ServiceContract contract(Thanatopractor thanatopractor, LocalDateTime preparedAt) {
        ServiceContract contract = new ServiceContract();
        contract.setId(1L);
        contract.setContractNumber("CT-2026-0001");
        contract.setStatus(ContractStatus.ACTIVE);
        contract.setThanatopractor(thanatopractor);
        contract.setPreparationCompletedAt(preparedAt);
        return contract;
    }

    private static Cremation cremation(CremationBatchStatus status) {
        Deceased deceased = new Deceased();
        deceased.setId(42L);
        deceased.setDeathType(DeathType.NATURAL);
        Cremation cremation = new Cremation();
        cremation.setId(90L);
        cremation.setDeceased(deceased);
        cremation.setCremationBatch(new CremationBatch(33L, NOW.minusHours(3), "BAQ-01", status));
        return cremation;
    }

    private static BoardRow row(Long batchId, LocalDateTime at, String furnace, CremationBatchStatus status,
                                Long cremationId, DeathType type, Long releaseOrders, LocalDateTime completedAt) {
        return new BoardRow() {
            public Long getBatchId() { return batchId; }
            public LocalDateTime getScheduledDate() { return at; }
            public String getFurnaceCode() { return furnace; }
            public CremationBatchStatus getStatus() { return status; }
            public Long getCremationId() { return cremationId; }
            public Long getDeceasedId() { return cremationId == null ? null : cremationId + 1000; }
            public String getName() { return "Rosa"; }
            public String getLastname() { return "Pérez Vega"; }
            public String getDocumentNumber() { return "1065000000"; }
            public DeathType getDeathType() { return type; }
            public Long getReleaseOrders() { return releaseOrders; }
            public String getThanatopractorName() { return "Laura"; }
            public String getThanatopractorLastname() { return "Martínez"; }
            public LocalDateTime getCompletedAt() { return completedAt; }
            public String getCertificateNumber() { return completedAt == null ? null : "CC-1"; }
        };
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
