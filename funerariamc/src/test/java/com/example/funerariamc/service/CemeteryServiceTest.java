package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Cemetery;
import com.example.funerariamc.model.Client;
import com.example.funerariamc.model.Deceased;
import com.example.funerariamc.model.Exhumation;
import com.example.funerariamc.model.Manager;
import com.example.funerariamc.model.Role;
import com.example.funerariamc.model.Vault;
import com.example.funerariamc.model.enums.ExhumationDestination;
import com.example.funerariamc.model.enums.ExhumationReason;
import com.example.funerariamc.model.enums.VaultStatus;
import com.example.funerariamc.exception.FileUploadException;
import com.example.funerariamc.persistence.CemeteryRepository;
import com.example.funerariamc.persistence.ClientRepository;
import com.example.funerariamc.persistence.DeceasedRepository;
import com.example.funerariamc.persistence.ExhumationRepository;
import com.example.funerariamc.persistence.LeaseNotificationRepository;
import com.example.funerariamc.persistence.VaultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Criterios de aceptación de RF-BOV-01, RF-BOV-02, RF-BOV-05 y RF-BOV-06 (HU-55): registrar, asignar, renovar y exhumar.
 * También revisa que la pantalla cargue con pocas consultas (proyecciones y una sola carga inicial).
 */
class CemeteryServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);
    private static final Path AUTHORIZATION = Path.of("autorizacion-a16.pdf");
    private static final String STORED_URL = "file:///home/funeraria/archivos/exhumaciones/a16.pdf";

    private final CemeteryRepository cemeteries = mock(CemeteryRepository.class);
    private final VaultRepository vaults = mock(VaultRepository.class);
    private final ExhumationRepository exhumations = mock(ExhumationRepository.class);
    private final LeaseNotificationRepository notices = mock(LeaseNotificationRepository.class);
    private final ClientRepository clients = mock(ClientRepository.class);
    private final DeceasedRepository deceased = mock(DeceasedRepository.class);
    private final FileStorageService files = mock(FileStorageService.class);
    private final AccessLogService accessLog = mock(AccessLogService.class);
    private final SessionContext session = new SessionContext();
    private final CemeteryService service = new CemeteryService(cemeteries, vaults, exhumations, notices,
            clients, deceased, files, accessLog, session,
            Clock.fixed(TODAY.atStartOfDay(ZoneId.of("America/Bogota")).toInstant(), ZoneId.of("America/Bogota")));

    @BeforeEach
    void loginAsCemeteryAdmin() {
        loginAs(RoleNames.CEMETERY_ADMIN);
        when(vaults.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(exhumations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(files.store(any(), eq(CemeteryService.AUTHORIZATION_FOLDER))).thenReturn(STORED_URL);
    }

    // ---------- asignar (RF-BOV-02) ----------

    @Test
    void assignComputesLeaseEndFourYearsLaterAndOccupiesTheVault() {
        // Prueba de Jira (RF-BOV-02): asignar el 01/01/2026; PASA si vence el 01/01/2030.
        Vault vault = vault(7L, "A-07", VaultStatus.AVAILABLE);
        when(vaults.findDetailById(7L)).thenReturn(Optional.of(vault));
        when(clients.findById(40L)).thenReturn(Optional.of(client("Martha Cecilia", "Durán")));
        when(deceased.findById(9L)).thenReturn(Optional.of(deceased("Rafael Antonio", "Durán")));

        Long savedId = service.assign(new AssignVaultData(7L, 40L, 9L, LocalDate.of(2026, 1, 1)));

        assertEquals(7L, savedId);
        assertEquals(LocalDate.of(2030, 1, 1), vault.getLeaseEndDate());
        assertEquals(VaultStatus.OCCUPIED, vault.getStatus());
        assertEquals("Durán", vault.getDeceased().getLastname());
        verify(accessLog).record(5L, "VAULT_ASSIGNED: A-07");
    }

    @Test
    void assignRejectsAVaultThatIsNotAvailable() {
        Vault vault = occupied(7L, "A-07", LocalDate.of(2024, 3, 1));
        when(vaults.findDetailById(7L)).thenReturn(Optional.of(vault));

        InvalidDataException error = assertThrows(InvalidDataException.class,
                () -> service.assign(new AssignVaultData(7L, 40L, 9L, TODAY)));

        assertTrue(error.getMessage().contains("no está disponible"));
        verify(vaults, never()).save(any());
    }

    @Test
    void assignRejectsADeceasedAlreadyInAnotherVault() {
        when(vaults.findDetailById(7L)).thenReturn(Optional.of(vault(7L, "A-07", VaultStatus.AVAILABLE)));
        when(vaults.existsByDeceasedId(9L)).thenReturn(true);

        assertThrows(InvalidDataException.class, () -> service.assign(new AssignVaultData(7L, 40L, 9L, TODAY)));
        verify(vaults, never()).save(any());
    }

    @Test
    void assignRequiresHolderDeceasedAndStartDate() {
        assertThrows(InvalidDataException.class, () -> service.assign(new AssignVaultData(7L, null, 9L, TODAY)));
        assertThrows(InvalidDataException.class, () -> service.assign(new AssignVaultData(7L, 40L, null, TODAY)));
        assertThrows(InvalidDataException.class, () -> service.assign(new AssignVaultData(7L, 40L, 9L, null)));
    }

    // ---------- renovar ----------

    @Test
    void renewAddsFourYearsToTheCurrentEndAndClearsTheOverdueStatus() {
        Vault vault = occupied(11L, "A-11", LocalDate.of(2022, 10, 12));
        vault.setStatus(VaultStatus.OVERDUE);
        when(vaults.findDetailById(11L)).thenReturn(Optional.of(vault));

        LocalDate newEnd = service.renewLease(11L);

        assertEquals(LocalDate.of(2030, 10, 12), newEnd);
        assertEquals(VaultStatus.OCCUPIED, vault.getStatus());
        verify(accessLog).record(5L, "VAULT_LEASE_RENEWED: A-11");
    }

    @Test
    void renewRejectsAFreeVault() {
        when(vaults.findDetailById(3L)).thenReturn(Optional.of(vault(3L, "A-03", VaultStatus.AVAILABLE)));

        assertThrows(InvalidDataException.class, () -> service.renewLease(3L));
    }

    // ---------- exhumar (RF-BOV-05) ----------

    @Test
    void exhumationBeforeFourYearsIsRejectedWithTheDateItIsAllowed() {
        // Prueba de Jira (RF-BOV-05): exhumar de una bóveda arrendada hace 2 años; PASA si se rechaza.
        Vault vault = occupied(7L, "A-07", LocalDate.of(2024, 10, 4));
        when(vaults.findDetailById(7L)).thenReturn(Optional.of(vault));

        InvalidDataException error = assertThrows(InvalidDataException.class,
                () -> service.exhume(exhumation(7L, ExhumationReason.FAMILY_REQUEST, ExhumationDestination.FAMILY)));

        assertTrue(error.getMessage().contains("04/10/2028"), error.getMessage());
        verify(exhumations, never()).save(any());
        verify(files, never()).store(any(), anyString()); // nada se copia si la exhumación no procede
    }

    @Test
    void exhumationFreesTheVaultAndRecordsTheEmployee() {
        Vault vault = occupied(16L, "A-16", LocalDate.of(2021, 5, 2));
        Deceased buried = vault.getDeceased();
        when(vaults.findDetailById(16L)).thenReturn(Optional.of(vault));

        Exhumation saved = service.exhume(exhumation(16L, ExhumationReason.UNCLAIMED, ExhumationDestination.OSSUARY));

        assertSame(buried, saved.getDeceased());
        assertEquals(99L, saved.getRegisteredByEmployee().getId());
        // RN-25: se guarda la URL del archivo copiado por FileStorageService
        assertEquals(STORED_URL, saved.getAuthorizationFileUrl());
        verify(files).store(AUTHORIZATION, CemeteryService.AUTHORIZATION_FOLDER);
        // RN-30: la bóveda queda libre
        assertEquals(VaultStatus.AVAILABLE, vault.getStatus());
        assertNull(vault.getDeceased());
        assertNull(vault.getClient());
        assertNull(vault.getLeaseEndDate());
        verify(accessLog).record(5L, "EXHUMATION_REGISTERED: A-16");
    }

    @Test
    void exhumationRequiresTheAuthorizationFile() {
        when(vaults.findDetailById(16L)).thenReturn(Optional.of(occupied(16L, "A-16", LocalDate.of(2021, 5, 2))));

        assertThrows(InvalidDataException.class, () -> service.exhume(new ExhumationData(16L, TODAY,
                ExhumationReason.FAMILY_REQUEST, ExhumationDestination.FAMILY, null, null)));
        verify(files, never()).store(any(), anyString());
    }

    @Test
    void aRejectedFileStopsTheExhumation() {
        when(vaults.findDetailById(16L)).thenReturn(Optional.of(occupied(16L, "A-16", LocalDate.of(2021, 5, 2))));
        when(files.store(any(), anyString())).thenThrow(new FileUploadException("El archivo debe ser PDF, PNG o JPG."));

        assertThrows(FileUploadException.class,
                () -> service.exhume(exhumation(16L, ExhumationReason.FAMILY_REQUEST, ExhumationDestination.FAMILY)));
        verify(exhumations, never()).save(any());
    }

    @Test
    void exhumationDateCannotBeInTheFuture() {
        when(vaults.findDetailById(16L)).thenReturn(Optional.of(occupied(16L, "A-16", LocalDate.of(2021, 5, 2))));

        assertThrows(InvalidDataException.class, () -> service.exhume(new ExhumationData(16L, TODAY.plusDays(1),
                ExhumationReason.FAMILY_REQUEST, ExhumationDestination.FAMILY, AUTHORIZATION, null)));
    }

    @Test
    void unclaimedBodyOutsideCommonGraveOrOssuaryNeedsAWarning() {
        // RN-07
        assertTrue(CemeteryService.needsUnclaimedWarning(ExhumationReason.UNCLAIMED, ExhumationDestination.FAMILY));
        assertTrue(CemeteryService.needsUnclaimedWarning(ExhumationReason.UNCLAIMED, ExhumationDestination.CREMATION));
        assertFalse(CemeteryService.needsUnclaimedWarning(ExhumationReason.UNCLAIMED, ExhumationDestination.COMMON_GRAVE));
        assertFalse(CemeteryService.needsUnclaimedWarning(ExhumationReason.FAMILY_REQUEST, ExhumationDestination.FAMILY));
    }

    // ---------- muro y detalle (RF-BOV-06) ----------

    @Test
    void overviewGroupsByPavilionAndListsOverdueFirst() {
        Row expiring = occupiedRow(4L, "A-04", LocalDate.of(2022, 11, 8)); // vence el 08/11/2026
        Row overdue = occupiedRow(11L, "A-11", LocalDate.of(2022, 10, 12)); // venció el 12/10/2026
        Row free = freeRow(3L, "A-03");
        Row other = occupiedRow(12L, "B-12", LocalDate.of(2025, 1, 1));
        when(vaults.findWallRows(1L)).thenReturn(List.of(expiring, overdue, free, other));
        when(exhumations.findRecentRows(eq(1L), any())).thenReturn(List.of());

        CemeteryService.Overview overview = service.overview(1L);

        assertEquals(List.of("A", "B"), List.copyOf(overview.pavilions().keySet()));
        List<CemeteryService.WallCell> a = overview.pavilions().get("A");
        assertEquals(List.of("A-03", "A-04", "A-11"), a.stream().map(cell -> cell.code()).toList());
        assertEquals(2, a.get(2).position().level());
        assertEquals(1, a.get(2).position().column());
        assertEquals(List.of("A-11", "A-04"), overview.attention().stream().map(attention -> attention.code()).toList());
        assertEquals("Durán", a.get(1).occupantLastname());
        assertEquals("Martha Cecilia Durán", overview.attention().get(0).holder());

        CemeteryService.Kpis kpis = CemeteryService.Kpis.of(a);
        assertEquals(3, kpis.total());
        assertEquals(1, kpis.available());
        assertEquals(1, kpis.overdue());
        assertEquals(1, kpis.expiring());
        assertEquals(67, kpis.occupancyPercent());
    }

    @Test
    void detailShowsPreviousExhumationInTheHistory() {
        // Prueba de Jira (HU-55): una bóveda con exhumación anterior la muestra en su historial.
        when(vaults.findDetailRow(7L)).thenReturn(Optional.of(occupiedRow(7L, "A-07", LocalDate.of(2026, 10, 4))));
        when(exhumations.findHistoryRows(7L)).thenReturn(List.of(
                new ExhumationRowStub(7L, "A-07", "Pedro", "Baute", LocalDate.of(2025, 3, 15), ExhumationDestination.OSSUARY)));
        when(notices.findByVaultIdOrderByScheduledDateDesc(7L)).thenReturn(List.of());

        CemeteryService.VaultDetail detail = service.detail(7L);

        assertEquals("SC-0587", detail.contractNumber());
        assertEquals("CC 49786112", detail.clientDocument());
        assertEquals("Rafael Antonio Durán", detail.deceasedName());
        assertEquals(VaultState.OCCUPIED, detail.state());
        assertEquals(LocalDate.of(2030, 10, 4), detail.exhumationAllowedFrom());
        assertFalse(detail.canExhume(), "RN-06: no procede antes de 4 años");
        assertEquals(3, detail.history().size());
        assertEquals("Exhumación de Pedro Baute · libera la bóveda", detail.history().get(2).text());
        assertFalse(detail.history().get(2).current());
    }

    @Test
    void onlyCemeteryAdminAndMasterAdminCanUseTheModule() {
        loginAs(RoleNames.SALESPERSON);
        assertThrows(UnauthorizedActionException.class, () -> service.overview(1L));
        loginAs(RoleNames.MASTER_ADMIN);
        when(vaults.findWallRows(1L)).thenReturn(List.of());
        when(exhumations.findRecentRows(eq(1L), any())).thenReturn(List.of());
        assertTrue(service.overview(1L).pavilions().isEmpty());
    }

    // ---------- carga inicial (rendimiento) ----------

    @Test
    void screenLoadsTabsAndTheFirstCemeteryWallTogether() {
        when(cemeteries.findAllByOrderByNameAsc()).thenReturn(List.of(
                new Cemetery(2L, "Jardines del Ecce Homo", null), new Cemetery(1L, "Los Olivos", null)));
        when(vaults.findWallRows(2L)).thenReturn(List.of(freeRow(3L, "A-03")));
        when(exhumations.findRecentRows(eq(2L), any())).thenReturn(List.of());

        CemeteryService.Screen screen = service.screen(null);

        assertEquals(2, screen.cemeteries().size());
        assertEquals(2L, screen.cemeteryId());
        assertEquals(1, screen.overview().pavilions().get("A").size());
        verify(vaults, never()).findWallRows(1L); // solo se consulta el muro que se va a ver
    }

    @Test
    void screenKeepsTheRequestedCemeteryAndHandlesNoCemeteries() {
        when(cemeteries.findAllByOrderByNameAsc()).thenReturn(List.of(
                new Cemetery(2L, "Jardines del Ecce Homo", null), new Cemetery(1L, "Los Olivos", null)));
        when(vaults.findWallRows(1L)).thenReturn(List.of());
        when(exhumations.findRecentRows(eq(1L), any())).thenReturn(List.of());
        assertEquals(1L, service.screen(1L).cemeteryId());

        when(cemeteries.findAllByOrderByNameAsc()).thenReturn(List.of());
        CemeteryService.Screen empty = service.screen(1L);
        assertTrue(empty.cemeteries().isEmpty());
        assertNull(empty.overview());
    }

    @Test
    void clientSearchUsesTheListQueryWithoutCount() {
        when(clients.searchOptions(eq("%durán%"), any())).thenReturn(List.of(client("Martha Cecilia", "Durán")));

        List<CemeteryService.Option> options = service.searchClients(" Durán ");

        assertEquals("Martha Cecilia Durán", options.get(0).label());
        assertEquals("CC 49786112", options.get(0).detail());
        verify(clients, never()).search(anyString(), any(), any());
    }

    // ---------- posición y estado ----------

    @Test
    void vaultCodeGivesLevelAndColumn() {
        VaultPosition a07 = VaultPosition.of("A-07");
        assertEquals("A", a07.pavilion());
        assertEquals(1, a07.level());
        assertEquals(7, a07.column());
        VaultPosition b40 = VaultPosition.of("b40");
        assertEquals("B", b40.pavilion());
        assertEquals(4, b40.level());
        assertEquals(10, b40.column());
        assertNull(VaultPosition.of("Lote norte"));
        assertNull(VaultPosition.of(null));
    }

    @Test
    void stateComesFromStatusAndLeaseEnd() {
        assertEquals(VaultState.AVAILABLE, VaultState.of(vault(1L, "A-01", VaultStatus.AVAILABLE), TODAY));
        assertEquals(VaultState.OCCUPIED, VaultState.of(occupied(1L, "A-01", LocalDate.of(2025, 1, 1)), TODAY));
        assertEquals(VaultState.EXPIRING, VaultState.of(occupied(1L, "A-01", LocalDate.of(2022, 11, 20)), TODAY));
        assertEquals(VaultState.OVERDUE, VaultState.of(occupied(1L, "A-01", LocalDate.of(2022, 10, 1)), TODAY));
        Vault flagged = occupied(1L, "A-01", LocalDate.of(2025, 1, 1));
        flagged.setStatus(VaultStatus.OVERDUE);
        assertEquals(VaultState.OVERDUE, VaultState.of(flagged, TODAY));
    }

    // ---------- datos de prueba ----------

    private static ExhumationData exhumation(Long vaultId, ExhumationReason reason, ExhumationDestination destination) {
        return new ExhumationData(vaultId, TODAY, reason, destination, AUTHORIZATION, " ");
    }

    private static Vault vault(Long id, String code, VaultStatus status) {
        Cemetery cemetery = new Cemetery(1L, "Jardines del Ecce Homo", null);
        Vault vault = new Vault();
        vault.setId(id);
        vault.setCode(code);
        vault.setCemetery(cemetery);
        vault.setStatus(status);
        return vault;
    }

    /** Bóveda ocupada con arriendo de 4 años desde {@code start}. */
    private static Vault occupied(Long id, String code, LocalDate start) {
        Vault vault = vault(id, code, VaultStatus.OCCUPIED);
        vault.setClient(client("Martha Cecilia", "Durán"));
        vault.setDeceased(deceased("Rafael Antonio", "Durán"));
        vault.setLeaseStartDate(start);
        vault.setLeaseEndDate(start.plusYears(4));
        return vault;
    }

    private static Client client(String name, String lastname) {
        Client client = new Client();
        client.setId(40L);
        client.setName(name);
        client.setLastname(lastname);
        client.setDocumentType("CC");
        client.setDocumentNumber("49786112");
        return client;
    }

    private static Deceased deceased(String name, String lastname) {
        Deceased d = new Deceased();
        d.setId(9L);
        d.setName(name);
        d.setLastname(lastname);
        return d;
    }

    /** Fila de proyección del muro y del panel, como la devuelve VaultRepository. */
    private record Row(Long getId, String getCode, VaultStatus getStatus, LocalDate getLeaseStartDate,
                       LocalDate getLeaseEndDate, String getDeceasedName, String getDeceasedLastname,
                       String getClientName, String getClientLastname, String getCemeteryName, Long getDeceasedId,
                       LocalDate getDeceasedBirthDate, LocalDate getDeceasedDeathDate, Long getClientId,
                       String getClientDocumentType, String getClientDocumentNumber, String getClientPhone,
                       String getClientEmail, String getContractNumber) implements VaultRepository.DetailRow {
    }

    private record ExhumationRowStub(Long getVaultId, String getVaultCode, String getDeceasedName,
                                     String getDeceasedLastname, LocalDate getExhumationDate,
                                     ExhumationDestination getDestination) implements ExhumationRepository.ExhumationRow {
    }

    private static Row freeRow(Long id, String code) {
        return new Row(id, code, VaultStatus.AVAILABLE, null, null, null, null, null, null,
                "Jardines del Ecce Homo", null, null, null, null, null, null, null, null, null);
    }

    /** Fila ocupada con arriendo de 4 años desde {@code start}. */
    private static Row occupiedRow(Long id, String code, LocalDate start) {
        return new Row(id, code, VaultStatus.OCCUPIED, start, start.plusYears(4), "Rafael Antonio", "Durán",
                "Martha Cecilia", "Durán", "Jardines del Ecce Homo", 9L, LocalDate.of(1948, 2, 1),
                LocalDate.of(2026, 9, 30), 40L, "CC", "49786112", "3004567890", null, "SC-0587");
    }

    // ---------- registrar cementerios y bóvedas (RF-BOV-01) ----------

    @Test
    void onlyMasterAdminRegistersCemeteries() {
        // El Administrador del Cementerio (sesión por defecto) no puede (RF-BOV-01, actor ACT-01).
        assertFalse(service.canRegister());
        assertThrows(UnauthorizedActionException.class, () -> service.registerCemetery("Central", null));
        verify(cemeteries, never()).save(any());

        loginAs(RoleNames.MASTER_ADMIN);
        when(cemeteries.save(any())).thenAnswer(invocation -> {
            Cemetery saved = invocation.getArgument(0);
            saved.setId(3L);
            return saved;
        });
        assertEquals(3L, service.registerCemetery("  Cementerio Central ", " "));
        ArgumentCaptor<Cemetery> captor = ArgumentCaptor.forClass(Cemetery.class);
        verify(cemeteries).save(captor.capture());
        assertEquals("Cementerio Central", captor.getValue().getName());
        assertNull(captor.getValue().getAddress());
    }

    @Test
    void registerCemeteryRequiresAName() {
        loginAs(RoleNames.MASTER_ADMIN);
        assertThrows(InvalidDataException.class, () -> service.registerCemetery("   ", "Cl. 1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void addPavilionCreatesAvailableVaultsWithLevelCodesAndSkipsRepeated() {
        // RF-BOV-01: cada bóveda nace DISPONIBLE; 2a: un código repetido no se registra.
        loginAs(RoleNames.MASTER_ADMIN);
        Cemetery cemetery = new Cemetery(1L, "Jardines", null);
        when(cemeteries.findById(1L)).thenReturn(Optional.of(cemetery));
        when(vaults.findCodesByCemeteryId(1L)).thenReturn(List.of("A1-02"));

        CemeteryService.VaultBatch batch = service.addPavilion(1L, "a", 2, 15, 1);

        assertEquals(29, batch.created());
        assertEquals(List.of("A1-02"), batch.skipped());
        ArgumentCaptor<List<Vault>> captor = ArgumentCaptor.forClass(List.class);
        verify(vaults).saveAll(captor.capture());
        List<Vault> created = captor.getValue();
        assertEquals("A1-01", created.get(0).getCode());
        assertEquals("A2-15", created.get(created.size() - 1).getCode());
        assertTrue(created.stream().allMatch(v -> v.getStatus() == VaultStatus.AVAILABLE && v.getCemetery() == cemetery));
    }

    @Test
    void addVaultRejectsACodeThatAlreadyExistsOrTakesAnOccupiedSpot() {
        // Prueba de Jira (RF-BOV-01): dos bóvedas con el mismo código; PASA si la segunda se rechaza.
        loginAs(RoleNames.MASTER_ADMIN);
        when(cemeteries.findById(1L)).thenReturn(Optional.of(new Cemetery(1L, "Jardines", null)));
        when(vaults.findCodesByCemeteryId(1L)).thenReturn(List.of("A2-15", "A-11"));

        assertThrows(InvalidDataException.class, () -> service.addVault(1L, "a2-15"));
        // "A-11" (formato anterior) está en el nivel 2, columna 1: "A2-01" caería encima.
        assertThrows(InvalidDataException.class, () -> service.addVault(1L, "A2-01"));
        assertEquals(1, service.addVault(1L, "A2-16").created());
    }

    @Test
    void addPavilionValidatesItsNumbers() {
        loginAs(RoleNames.MASTER_ADMIN);
        assertThrows(InvalidDataException.class, () -> service.addPavilion(1L, "A", 0, 10, 1));
        assertThrows(InvalidDataException.class, () -> service.addPavilion(1L, "A", 2, 0, 1));
        assertThrows(InvalidDataException.class, () -> service.addPavilion(1L, "7", 2, 10, 1));
    }

    @Test
    void vaultPositionReadsBothCodeFormatsWithoutAColumnLimit() {
        VaultPosition wide = VaultPosition.of("B3-42");
        assertEquals("B", wide.pavilion());
        assertEquals(3, wide.level());
        assertEquals(42, wide.column());
        VaultPosition old = VaultPosition.of("A-11");
        assertEquals(2, old.level());
        assertEquals(1, old.column());
        assertEquals("A1-05", VaultPosition.code("a", 1, 5));
        assertNull(VaultPosition.of("Nicho especial"));
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
