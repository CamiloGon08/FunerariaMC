package com.example.funerariamc.service;

import com.example.funerariamc.exception.ExistingClientException;
import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.ResourceNotFoundException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Client;
import com.example.funerariamc.model.Manager;
import com.example.funerariamc.model.Role;
import com.example.funerariamc.model.enums.ClientType;
import com.example.funerariamc.persistence.ClientRepository;
import com.example.funerariamc.persistence.PersonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Criterios de aceptación de HU-01 (FUN-22): registrar cliente titular sin duplicar personas. */
class ClientServiceTest {

    private final ClientRepository clients = mock(ClientRepository.class);
    private final PersonRepository persons = mock(PersonRepository.class);
    private final AccessLogService accessLog = mock(AccessLogService.class);
    private final SessionContext session = new SessionContext();
    private final ClientService service = new ClientService(clients, persons, accessLog, session);

    @BeforeEach
    void loginAsSalesperson() {
        loginAs(RoleNames.SALESPERSON);
        when(clients.save(any())).thenAnswer(invocation -> {
            Client client = invocation.getArgument(0);
            client.setId(40L);
            return client;
        });
    }

    // ---------- registrar ----------

    @Test
    void registersNaturalClientWithNormalizedDocument() {
        Client saved = service.register(natural("1.065.832.417"));

        assertEquals("1065832417", saved.getDocumentNumber());
        assertEquals(ClientType.NATURAL, saved.getClientType());
        assertEquals("Martha Cecilia", saved.getName());
        assertEquals("Durán Araújo", saved.getLastname());
        assertNull(saved.getEmail());
        assertEquals(LocalDate.now(AccessLogService.APP_ZONE), saved.getRegistrationDate());
        verify(accessLog).record(5L, "CLIENT_CREATED: 1065832417");
    }

    @Test
    void secondClientWithSameDocumentIsRejectedAndExistingOneIsReturned() {
        // Prueba de Jira (HU-01): registrar dos clientes con el mismo documento; PASA si el segundo se rechaza.
        Client existing = new Client();
        existing.setId(7L);
        existing.setDocumentNumber("1065832417");
        when(clients.findByDocumentNumber("1065832417")).thenReturn(Optional.of(existing));

        ExistingClientException error = assertThrows(ExistingClientException.class,
                () -> service.register(natural("1065 832 417")));

        assertSame(existing, error.getExistingClient());
        assertFalse(error.getMessage().contains("1065832417"), "RNF-SEC-02: el mensaje no repite el documento");
        verify(clients, never()).save(any());
    }

    @Test
    void documentOfAnEmployeeCannotBeUsedForAClient() {
        when(persons.existsByDocumentNumber("1065832417")).thenReturn(true);

        assertThrows(InvalidDataException.class, () -> service.register(natural("1065832417")));
        verify(clients, never()).save(any());
    }

    @Test
    void registersCompanyWithNitAndBusinessNameWithoutLastname() {
        ClientData data = new ClientData(ClientType.CORPORATE, "NIT", "800.197.268-4",
                "Transportes Cesar S.A.S.", "ignorado", "605 580 4411", "contabilidad@transcesar.co",
                "Cl. 44 # 4-120, Zona Industrial");

        Client saved = service.register(data);

        assertEquals("8001972684", saved.getDocumentNumber());
        assertEquals("", saved.getLastname());
        assertEquals("Transportes Cesar S.A.S.", ClientService.displayName(saved));
        assertEquals("6055804411", saved.getPhone());
    }

    @Test
    void nitCheckDigitMustMatch() {
        ClientData data = new ClientData(ClientType.CORPORATE, "NIT", "800197268-5",
                "Empresa", null, "6055804411", null, "Calle 1");

        InvalidDataException error = assertThrows(InvalidDataException.class, () -> service.register(data));
        assertTrue(error.getMessage().contains("dígito de verificación"));
    }

    @Test
    void nitCheckDigitFollowsDian() {
        assertEquals(4, ClientService.nitCheckDigit("800197268"));
        assertEquals(4, ClientService.nitCheckDigit("900412377"));
        assertEquals(6, ClientService.nitCheckDigit("901088214"));
    }

    @Test
    void companyMustUseNitAndPersonCannotUseNit() {
        ClientData companyWithCc = new ClientData(ClientType.CORPORATE, "CC", "1065832417",
                "Empresa", null, "6055804411", null, "Calle 1");
        ClientData personWithNit = new ClientData(ClientType.NATURAL, "NIT", "8001972684",
                "Ana", "Pérez", "3015562087", null, "Calle 1");

        assertThrows(InvalidDataException.class, () -> service.register(companyWithCc));
        assertThrows(InvalidDataException.class, () -> service.register(personWithNit));
    }

    @Test
    void requiresNamesPhoneAndAddress() {
        assertThrows(InvalidDataException.class, () -> service.register(new ClientData(ClientType.NATURAL,
                "CC", "1065832417", "Martha", null, "3015562087", null, "Calle 1")));
        assertThrows(InvalidDataException.class, () -> service.register(new ClientData(ClientType.NATURAL,
                "CC", "1065832417", "Martha", "Durán", null, null, "Calle 1")));
        assertThrows(InvalidDataException.class, () -> service.register(new ClientData(ClientType.NATURAL,
                "CC", "1065832417", "Martha", "Durán", "3015562087", null, " ")));
        assertThrows(InvalidDataException.class, () -> service.register(new ClientData(null,
                "CC", "1065832417", "Martha", "Durán", "3015562087", null, "Calle 1")));
    }

    @Test
    void rejectsInvalidPhoneEmailAndCedula() {
        assertThrows(InvalidDataException.class, () -> service.register(new ClientData(ClientType.NATURAL,
                "CC", "1065832417", "Martha", "Durán", "301-ABC", null, "Calle 1")));
        assertThrows(InvalidDataException.class, () -> service.register(new ClientData(ClientType.NATURAL,
                "CC", "1065832417", "Martha", "Durán", "3015562087", "martha.gmail.com", "Calle 1")));
        assertThrows(InvalidDataException.class, () -> service.register(new ClientData(ClientType.NATURAL,
                "CC", "12345", "Martha", "Durán", "3015562087", null, "Calle 1")));
    }

    @Test
    void passportKeepsLettersInUpperCase() {
        Client saved = service.register(new ClientData(ClientType.NATURAL, "PA", "ab-123456",
                "John", "Smith", "301 556 2087", null, "Calle 1"));

        assertEquals("AB123456", saved.getDocumentNumber());
    }

    // ---------- actualizar ----------

    @Test
    void updateKeepsOwnDocumentAndLogsChange() {
        Client client = new Client();
        client.setId(40L);
        client.setDocumentNumber("1065832417");
        when(clients.findById(40L)).thenReturn(Optional.of(client));
        when(clients.findByDocumentNumber("1065832417")).thenReturn(Optional.of(client));

        service.update(40L, new ClientData(ClientType.NATURAL, "CC", "1065832417",
                "Martha Cecilia", "Durán", "3009998877", "nuevo@correo.com", "Cra. 9 # 16-42"));

        assertEquals("3009998877", client.getPhone());
        assertEquals("nuevo@correo.com", client.getEmail());
        verify(accessLog).record(5L, "CLIENT_UPDATED: 1065832417");
    }

    @Test
    void updateCannotTakeAnotherClientsDocument() {
        Client client = new Client();
        client.setId(40L);
        Client other = new Client();
        other.setId(41L);
        when(clients.findById(40L)).thenReturn(Optional.of(client));
        when(clients.findByDocumentNumber("77015342")).thenReturn(Optional.of(other));

        assertThrows(ExistingClientException.class, () -> service.update(40L, natural("77015342")));
    }

    // ---------- buscar y permisos ----------

    @Test
    void searchSendsPatternAllTypesAndCapsPageSize() {
        when(clients.search(anyString(), any(), any())).thenReturn(Page.empty());

        service.search("  1.065.832 ", null, -1, 500);

        verify(clients).search(eq("%1065832%"),
                eq(List.of(ClientType.NATURAL, ClientType.CORPORATE)),
                eq(PageRequest.of(0, ClientService.MAX_PAGE_SIZE)));
    }

    @Test
    void searchByNameIsLowerCaseAndFiltersByType() {
        when(clients.search(anyString(), any(), any())).thenReturn(Page.empty());

        service.search("Durán", ClientType.CORPORATE, 2, 10);

        verify(clients).search(eq("%durán%"), eq(List.of(ClientType.CORPORATE)), eq(PageRequest.of(2, 10)));
    }

    @Test
    void balanceFilterUsesActiveIncomeOnly() {
        when(clients.searchByBalance(anyString(), any(), anyString(), any(), any(), any())).thenReturn(Page.empty());

        service.search("", ClientType.NATURAL, ClientService.BalanceFilter.WITH_BALANCE, 0, 12);

        verify(clients).searchByBalance(eq("%"), eq(List.of(ClientType.NATURAL)), eq("WITH"),
                eq(com.example.funerariamc.model.enums.TransactionType.INCOME),
                eq(com.example.funerariamc.model.enums.TransactionStatus.ACTIVE), eq(PageRequest.of(0, 12)));
    }

    @Test
    void balanceFilterAllUsesPlainSearch() {
        when(clients.search(anyString(), any(), any())).thenReturn(Page.empty());

        service.search("", null, ClientService.BalanceFilter.ALL, 0, 12);

        verify(clients).search(eq("%"), eq(List.of(ClientType.values())), eq(PageRequest.of(0, 12)));
        verify(clients, never()).searchByBalance(anyString(), any(), anyString(), any(), any(), any());
    }

    @Test
    void upToDateFilterPreservesSearchNormalizationAndPageBounds() {
        when(clients.searchByBalance(anyString(), any(), anyString(), any(), any(), any())).thenReturn(Page.empty());

        service.search(" 1.065.832 ", null, ClientService.BalanceFilter.UP_TO_DATE, -3, 500);

        verify(clients).searchByBalance(eq("%1065832%"), eq(List.of(ClientType.values())), eq("WITHOUT"),
                eq(com.example.funerariamc.model.enums.TransactionType.INCOME),
                eq(com.example.funerariamc.model.enums.TransactionStatus.ACTIVE),
                eq(PageRequest.of(0, ClientService.MAX_PAGE_SIZE)));
    }

    @Test
    void nullBalanceFilterUsesPlainSearch() {
        when(clients.search(anyString(), any(), any())).thenReturn(Page.empty());
        service.search("", null, null, 0, 10);
        verify(clients).search(eq("%"), eq(List.of(ClientType.values())), eq(PageRequest.of(0, 10)));
        verify(clients, never()).searchByBalance(anyString(), any(), anyString(), any(), any(), any());
    }

    @Test
    void findByDocumentNormalizesBeforeLooking() {
        service.findByDocument("1.065.832.417");

        verify(clients).findByDocumentNumber("1065832417");
    }

    @Test
    void secretaryAndMasterAdminMayUseClientsButCemeteryAdminMayNot() {
        loginAs(RoleNames.SECRETARY);
        assertDoesNotThrow(() -> service.count(null));
        loginAs(RoleNames.MASTER_ADMIN);
        assertDoesNotThrow(() -> service.count(ClientType.NATURAL));
        loginAs(RoleNames.CEMETERY_ADMIN);
        assertThrows(UnauthorizedActionException.class, () -> service.register(natural("1065832417")));
        session.clear();
        assertThrows(UnauthorizedActionException.class, () -> service.count(null));
    }

    @Test
    void nullFormsAreRejectedBeforeLookingUpOrSavingClients() {
        assertThrows(InvalidDataException.class, () -> service.register(null));
        assertThrows(InvalidDataException.class, () -> service.update(40L, null));

        verifyNoInteractions(clients, persons, accessLog);
    }

    @ParameterizedTest
    @ValueSource(strings = {".", "--", " . - "})
    void normalizedDocumentCannotBeEmpty(String document) {
        assertThrows(InvalidDataException.class, () -> service.register(natural(document)));

        verifyNoInteractions(clients, persons, accessLog);
    }

    @Test
    void unidentifiedActorCannotPerformAnyWriteOrSkipAuditing() {
        session.getCurrentUser().orElseThrow().setId(null);

        assertThrows(UnauthorizedActionException.class, () -> service.register(natural("1065832417")));
        assertThrows(UnauthorizedActionException.class, () -> service.update(40L, natural("1065832417")));

        verifyNoInteractions(clients, persons, accessLog);
    }

    @Test
    void missingClientsRaiseBusinessErrorsForReadsAndUpdates() {
        assertThrows(ResourceNotFoundException.class, () -> service.findById(40L));
        assertThrows(ResourceNotFoundException.class, () -> service.update(40L, natural("1065832417")));

        verifyNoInteractions(persons, accessLog);
    }

    @Test
    void updateCannotTakeAnEmployeesDocumentAndLeavesTheClientUnchanged() {
        Client client = new Client();
        client.setId(40L);
        client.setDocumentNumber("1065832417");
        when(clients.findById(40L)).thenReturn(Optional.of(client));
        when(persons.existsByDocumentNumberAndIdNot("77015342", 40L)).thenReturn(true);

        assertThrows(InvalidDataException.class, () -> service.update(40L, natural("77015342")));

        assertEquals("1065832417", client.getDocumentNumber());
        verifyNoInteractions(accessLog);
    }

    @Test
    void editingContactDetailsPreservesIdentityAndRegistrationDate() {
        Client client = new Client();
        client.setId(40L);
        LocalDate registrationDate = LocalDate.of(2025, 5, 10);
        client.setRegistrationDate(registrationDate);
        when(clients.findById(40L)).thenReturn(Optional.of(client));

        Client updated = service.update(40L, natural("1065832417"));

        assertSame(client, updated);
        assertEquals(40L, updated.getId());
        assertEquals(registrationDate, updated.getRegistrationDate());
        verify(accessLog).record(5L, "CLIENT_UPDATED: 1065832417");
    }

    @Test
    void namesAndAddressesCannotExceedThePersonColumnLengths() {
        // Límites de person indicados en el contrato de Clientes.
        assertThrows(InvalidDataException.class, () -> service.register(new ClientData(ClientType.NATURAL,
                "CC", "1065832417", "X".repeat(26), "Durán", "3015562087", null, "Calle 1")));
        assertThrows(InvalidDataException.class, () -> service.register(new ClientData(ClientType.NATURAL,
                "CC", "1065832417", "Martha", "X".repeat(26), "3015562087", null, "Calle 1")));
        assertThrows(InvalidDataException.class, () -> service.register(new ClientData(ClientType.NATURAL,
                "CC", "1065832417", "Martha", "Durán", "3015562087", "x".repeat(21) + "@correo.co", "Calle 1")));
        assertThrows(InvalidDataException.class, () -> service.register(new ClientData(ClientType.NATURAL,
                "CC", "1065832417", "Martha", "Durán", "3015562087", null, "X".repeat(41))));
        assertThrows(InvalidDataException.class, () -> service.register(new ClientData(ClientType.NATURAL,
                "CC", "1065832417", "Martha", "Durán", "+57 301 556 2087", null, "Calle 1")));

        verifyNoInteractions(clients, persons, accessLog);
    }

    @ParameterizedTest
    @ValueSource(strings = {"(301) 556-2087", "301.556.2087", " 301 556 2087 "})
    void storesFormattedPhoneAsDigits(String phone) {
        Client saved = service.register(new ClientData(ClientType.NATURAL, "CC", "1065832417",
                "Martha", "Durán", phone, null, "Calle 1"));

        assertEquals("3015562087", saved.getPhone());
    }

    @ParameterizedTest
    @ValueSource(strings = {"123456", "12345678901", "+573015562087"})
    void rejectsPhonesOutsideTheStorageLimit(String phone) {
        assertThrows(InvalidDataException.class, () -> service.register(new ClientData(ClientType.NATURAL,
                "CC", "1065832417", "Martha", "Durán", phone, null, "Calle 1")));

        verifyNoInteractions(clients, persons, accessLog);
    }

    @Test
    void acceptsValuesAtTheDocumentedStorageLimits() {
        Client saved = service.register(new ClientData(ClientType.NATURAL, "PA", "AB123456789",
                "N".repeat(25), "A".repeat(25), "3015562087", "x".repeat(19) + "@correo.com", "D".repeat(40)));

        assertEquals("AB123456789", saved.getDocumentNumber());
        assertEquals(25, saved.getName().length());
        assertEquals(25, saved.getLastname().length());
        assertEquals(30, saved.getEmail().length());
        assertEquals(40, saved.getAddress().length());
    }

    @Test
    void passportCannotExceedTheStorageLimit() {
        assertThrows(InvalidDataException.class, () -> service.register(new ClientData(ClientType.NATURAL,
                "PA", "AB1234567890", "John", "Smith", "3015562087", null, "Calle 1")));

        verifyNoInteractions(clients, persons, accessLog);
    }

    @Test
    void totalsComeFromTheRequestedClientType() {
        when(clients.count()).thenReturn(25L);
        when(clients.countByClientType(ClientType.CORPORATE)).thenReturn(4L);

        assertEquals(25L, service.count(null));
        assertEquals(4L, service.count(ClientType.CORPORATE));
    }

    @Test
    void auditFailureIsNotSwallowedByRegistrationOrUpdate() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("Audit unavailable");
        doThrow(failure).when(accessLog).record(eq(5L), anyString());
        Client client = new Client();
        client.setId(40L);
        when(clients.findById(40L)).thenReturn(Optional.of(client));

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.register(natural("1065832417"))));
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.update(40L, natural("1065832417"))));
    }

    @Test
    void blankDocumentLookupDoesNotQueryTheDatabase() {
        assertTrue(service.findByDocument(null).isEmpty());
        assertTrue(service.findByDocument(" ").isEmpty());

        verifyNoInteractions(clients, persons, accessLog);
    }

    @Test
    void cemeteryAdminAndUnauthenticatedSessionCannotReadOrModifyClients() {
        loginAs(RoleNames.CEMETERY_ADMIN);
        assertClientAccessDenied();
        session.clear();
        assertClientAccessDenied();
    }

    private void assertClientAccessDenied() {
        assertThrows(UnauthorizedActionException.class, () -> service.search("", null, 0, 10));
        assertThrows(UnauthorizedActionException.class,
                () -> service.search("", null, ClientService.BalanceFilter.WITH_BALANCE, 0, 10));
        assertThrows(UnauthorizedActionException.class, () -> service.count(null));
        assertThrows(UnauthorizedActionException.class, () -> service.findById(40L));
        assertThrows(UnauthorizedActionException.class, () -> service.findByDocument("1065832417"));
        assertThrows(UnauthorizedActionException.class, () -> service.register(natural("1065832417")));
        assertThrows(UnauthorizedActionException.class, () -> service.update(40L, natural("1065832417")));
        verifyNoInteractions(clients, persons, accessLog);
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

    private static ClientData natural(String document) {
        return new ClientData(ClientType.NATURAL, "CC", document, " Martha Cecilia ", "Durán Araújo",
                "301 556 2087", " ", "Cra. 9 # 16-42, Centro, Valledupar");
    }
}
