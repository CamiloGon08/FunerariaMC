package com.example.funerariamc.service;

import com.example.funerariamc.exception.DuplicateDocumentException;
import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.ResourceNotFoundException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Driver;
import com.example.funerariamc.model.Employee;
import com.example.funerariamc.model.FuneralSalesperson;
import com.example.funerariamc.model.Manager;
import com.example.funerariamc.model.Receptionist;
import com.example.funerariamc.model.Role;
import com.example.funerariamc.model.Thanatopractor;
import com.example.funerariamc.model.enums.EmployeePosition;
import com.example.funerariamc.model.enums.EmployeeStatus;
import com.example.funerariamc.persistence.AppUserRepository;
import com.example.funerariamc.persistence.EmployeeRepository;
import com.example.funerariamc.persistence.PersonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Criterios de aceptación de HU-02, HU-03 y HU-04 (FUN-23, FUN-24, FUN-25). */
class EmployeeServiceTest {

    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final PersonRepository persons = mock(PersonRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final AccessLogService accessLog = mock(AccessLogService.class);
    private final SessionContext session = new SessionContext();
    private final EmployeeService service = new EmployeeService(employees, persons, users, accessLog, session);

    @BeforeEach
    void loginAsMasterAdmin() {
        Manager admin = new Manager();
        admin.setId(99L);
        admin.setRole(new Role(1L, RoleNames.MASTER_ADMIN, null));
        AppUser user = new AppUser();
        user.setId(5L);
        user.setEmployee(admin);
        session.start(user);
        when(employees.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ---------- HU-02 ----------

    @Test
    void registersDriverAsSubtypeWithNormalizedDocument() {
        Employee saved = service.register(driver("12.725.893", "file:/C:/licencia.pdf",
                LocalDate.now().plusYears(2)));

        assertInstanceOf(Driver.class, saved);
        assertEquals("12725893", saved.getDocumentNumber());
        assertEquals(EmployeeStatus.ACTIVE, saved.getStatus());
        verify(accessLog).record(5L, "EMPLOYEE_CREATED: 12725893");
    }

    @Test
    void rejectsDocumentAlreadyRegisteredForAnyPerson() {
        when(persons.existsByDocumentNumber("12725893")).thenReturn(true);

        assertThrows(DuplicateDocumentException.class,
                () -> service.register(driver("12725893", "file:/C:/licencia.pdf", LocalDate.now())));
        verify(employees, never()).save(any());
    }

    @Test
    void externalAuditorIsNotAnInternalPosition() {
        // RN-23: el cargo no existe en la lista, así que no se puede registrar.
        for (EmployeePosition position : EmployeePosition.values()) {
            assertFalse(position.name().contains("AUDITOR"));
        }
    }

    @Test
    void salespersonCommissionMustBeBetweenZeroAndHundred() {
        EmployeeData data = new EmployeeData("Laura", "Daza", "CC", "1065000111", null, null, null, null,
                EmployeePosition.FUNERAL_SALESPERSON, LocalDate.of(2024, 3, 1), new BigDecimal("1600000"),
                new BigDecimal("120"), null, null, null, null, null, null, null, null);

        assertThrows(InvalidDataException.class, () -> service.register(data));
    }

    @Test
    void salespersonKeepsCommissionRate() {
        EmployeeData data = new EmployeeData("Laura", "Daza", "CC", "1065000111", null, null, null, null,
                EmployeePosition.FUNERAL_SALESPERSON, LocalDate.of(2024, 3, 1), new BigDecimal("1600000"),
                new BigDecimal("5.5"), null, null, null, null, null, null, null, null);

        FuneralSalesperson saved = (FuneralSalesperson) service.register(data);

        assertEquals(new BigDecimal("5.5"), saved.getCommissionRate());
    }

    @Test
    void hireDateCannotBeInTheFuture() {
        EmployeeData data = new EmployeeData("Ana", "Mejía", "CC", "77000111", null, null, null, null,
                EmployeePosition.RECEPTIONIST, LocalDate.now(AccessLogService.APP_ZONE).plusDays(3), new BigDecimal("1423500"),
                null, null, null, null, null, null, null, null, null);

        assertThrows(InvalidDataException.class, () -> service.register(data));
    }

    // ---------- HU-03 ----------

    @Test
    void thanatopractorWithoutLicenseNumberIsAccepted() {
        EmployeeData data = new EmployeeData("Andrea", "Pumarejo", "CC", "1003418267", null, null, null, null,
                EmployeePosition.THANATOPRACTOR, LocalDate.of(2023, 1, 15), new BigDecimal("2000000"),
                null, "SENA", LocalDate.of(2022, 5, 1), "file:/C:/certificado.pdf", new BigDecimal("80000"),
                null, null, null, null);

        assertInstanceOf(Thanatopractor.class, service.register(data));
    }

    @Test
    void thanatopractorNeedsCertificateFile() {
        EmployeeData data = new EmployeeData("Andrea", "Pumarejo", "CC", "1003418267", null, null, null, null,
                EmployeePosition.THANATOPRACTOR, LocalDate.of(2023, 1, 15), new BigDecimal("2000000"),
                null, "SENA", LocalDate.of(2022, 5, 1), null, new BigDecimal("80000"),
                null, null, null, null);

        assertThrows(InvalidDataException.class, () -> service.register(data));
    }

    @Test
    void driverNeedsLicenseFile() {
        assertThrows(InvalidDataException.class,
                () -> service.register(driver("12725893", null, LocalDate.now().plusYears(1))));
    }

    @Test
    void expiredLicenseIsSavedButFlagged() {
        // RN-26: se guarda y la pantalla advierte.
        Employee saved = service.register(driver("12725893", "file:/C:/licencia.pdf",
                LocalDate.now(AccessLogService.APP_ZONE).minusDays(1)));

        assertTrue(service.hasExpiredLicense(saved));
    }

    @Test
    void positionCannotChangeOnUpdate() {
        Receptionist receptionist = new Receptionist();
        receptionist.setId(7L);
        when(employees.findById(7L)).thenReturn(Optional.of(receptionist));

        assertThrows(InvalidDataException.class,
                () -> service.update(7L, driver("1", "file:/C:/licencia.pdf", LocalDate.now())));
    }

    // ---------- HU-04 ----------

    @Test
    void deactivatingDisablesTheUserAccount() {
        Receptionist receptionist = receptionistWithId(7L);
        AppUser account = new AppUser();
        account.setIsEnabled(true);
        when(employees.findById(7L)).thenReturn(Optional.of(receptionist));
        when(users.findByEmployee_Id(7L)).thenReturn(Optional.of(account));

        service.deactivate(7L);

        assertFalse(receptionist.isActive());
        assertFalse(account.getIsEnabled());
        verify(employees, never()).delete(any());
        verify(accessLog).record(5L, "EMPLOYEE_DEACTIVATED: 1065999000");
    }

    @Test
    void activatingEnablesTheUserAccountAgain() {
        Receptionist receptionist = receptionistWithId(7L);
        receptionist.deactivate();
        AppUser account = new AppUser();
        account.setIsEnabled(false);
        when(employees.findById(7L)).thenReturn(Optional.of(receptionist));
        when(users.findByEmployee_Id(7L)).thenReturn(Optional.of(account));

        service.activate(7L);

        assertTrue(receptionist.isActive());
        assertTrue(account.getIsEnabled());
    }

    @Test
    void cannotDeactivateTheLoggedInEmployee() {
        Employee self = session.getCurrentUser().orElseThrow().getEmployee();
        when(employees.findById(99L)).thenReturn(Optional.of(self));

        assertThrows(InvalidDataException.class, () -> service.deactivate(99L));
    }

    // ---------- Lista y permisos ----------

    @Test
    void searchNormalizesDocumentAndBlankText() {
        when(employees.search(any(), any(), any(), any())).thenReturn(Page.empty());

        service.search("1.065.234", EmployeeStatus.ACTIVE, null, 0, 10);
        service.search("   ", null, null, 0, 10);
        service.search("Jorge ARAÚ", null, null, 0, 10);

        verify(employees).search(eq("%1065234%"), eq(List.of(EmployeeStatus.ACTIVE)), any(), any());
        verify(employees).search(eq("%"), eq(List.of(EmployeeStatus.values())), any(), any());
        verify(employees).search(eq("%jorge araú%"), any(), any(), any());
    }

    @Test
    void searchFiltersByPositionAndPage() {
        when(employees.search(any(), any(), any(), any())).thenReturn(Page.empty());

        service.search("", null, EmployeePosition.DRIVER, 2, 10);
        service.search("", null, null, -1, 500);

        verify(employees).search(eq("%"), any(), eq(List.of(Driver.class)), eq(PageRequest.of(2, 10)));
        verify(employees).search(eq("%"), any(), argThat(types -> types.size() == EmployeePosition.values().length),
                eq(PageRequest.of(0, EmployeeService.MAX_PAGE_SIZE)));
    }

    @Test
    void onlyMasterAdminUsesTheModule() {
        session.getCurrentUser().orElseThrow().getEmployee().getRole().setName(RoleNames.SECRETARY);

        assertThrows(UnauthorizedActionException.class, () -> service.search("x", null, null, 0, 10));
    }

    @Test
    void nullFormsAreRejectedBeforeLookingUpOrSavingEmployees() {
        assertThrows(InvalidDataException.class, () -> service.register(null));
        assertThrows(InvalidDataException.class, () -> service.update(7L, null));

        verifyNoInteractions(employees, persons, users, accessLog);
    }

    @ParameterizedTest
    @ValueSource(strings = {".", "--", " . - "})
    void normalizedDocumentCannotBeEmpty(String document) {
        assertThrows(InvalidDataException.class, () -> service.register(receptionist(document, null)));

        verifyNoInteractions(employees, persons, users, accessLog);
    }

    @ParameterizedTest
    @ValueSource(strings = {"@", "ana@", "ana correo@example.com", "ana@example"})
    void invalidEmailIsRejectedBeforePersistence(String email) {
        assertThrows(InvalidDataException.class, () -> service.register(receptionist("77000111", email)));

        verifyNoInteractions(employees, persons, users, accessLog);
    }

    @Test
    void documentCannotExceedThePersonColumnLength() {
        assertThrows(InvalidDataException.class,
                () -> service.register(receptionist("1".repeat(31), null)));

        verifyNoInteractions(employees, persons, users, accessLog);
    }

    @Test
    void updateKeepsPositionRoleAndInactiveStatusWhileChangingContactDetails() {
        Receptionist employee = receptionistWithId(7L);
        employee.deactivate();
        Role originalRole = new Role(2L, RoleNames.SECRETARY, null);
        employee.setRole(originalRole);
        when(employees.findById(7L)).thenReturn(Optional.of(employee));

        Employee updated = service.update(7L, receptionist("77.000.111", " ana@example.com "));

        assertSame(employee, updated);
        assertInstanceOf(Receptionist.class, updated);
        assertSame(originalRole, updated.getRole());
        assertFalse(updated.isActive());
        assertEquals("Ana", updated.getName());
        assertEquals("Mejía", updated.getLastname());
        assertEquals("CC", updated.getDocumentType());
        assertEquals("77000111", updated.getDocumentNumber());
        assertEquals("ana@example.com", updated.getEmail());
        verify(persons).existsByDocumentNumberAndIdNot("77000111", 7L);
        verify(accessLog).record(5L, "EMPLOYEE_UPDATED: 77000111");
    }

    @Test
    void duplicateDocumentOnUpdateLeavesTheEmployeeUnchanged() {
        Receptionist employee = receptionistWithId(7L);
        when(employees.findById(7L)).thenReturn(Optional.of(employee));
        when(persons.existsByDocumentNumberAndIdNot("77000111", 7L)).thenReturn(true);

        DuplicateDocumentException failure = assertThrows(DuplicateDocumentException.class,
                () -> service.update(7L, receptionist("77.000.111", null)));

        assertEquals("1065999000", employee.getDocumentNumber());
        assertFalse(failure.getMessage().contains("77000111"));
        verifyNoInteractions(accessLog);
    }

    @Test
    void missingEmployeesRaiseBusinessErrorsForReadsAndWrites() {
        assertThrows(ResourceNotFoundException.class, () -> service.findById(7L));
        assertThrows(ResourceNotFoundException.class,
                () -> service.update(7L, receptionist("77000111", null)));
        assertThrows(ResourceNotFoundException.class, () -> service.activate(7L));
        assertThrows(ResourceNotFoundException.class, () -> service.deactivate(7L));

        verifyNoInteractions(persons, users, accessLog);
    }

    @Test
    void unidentifiedActorCannotPerformAnyWriteOrSkipAuditing() {
        session.getCurrentUser().orElseThrow().setId(null);

        assertThrows(UnauthorizedActionException.class,
                () -> service.register(receptionist("77000111", null)));
        assertThrows(UnauthorizedActionException.class,
                () -> service.update(7L, receptionist("77000111", null)));
        assertThrows(UnauthorizedActionException.class, () -> service.activate(7L));
        assertThrows(UnauthorizedActionException.class, () -> service.deactivate(7L));

        verifyNoInteractions(employees, persons, users, accessLog);
    }

    @Test
    void auditFailureIsNotSwallowedBySensitiveOperations() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("Audit unavailable");
        doThrow(failure).when(accessLog).record(eq(5L), anyString());
        when(employees.findById(7L)).thenReturn(Optional.of(receptionistWithId(7L)));

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.register(receptionist("77000111", null))));
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.deactivate(7L)));
    }

    @Test
    void statusCanChangeWhenEmployeeHasNoUserAccount() {
        Receptionist employee = receptionistWithId(7L);
        when(employees.findById(7L)).thenReturn(Optional.of(employee));

        assertSame(employee, service.deactivate(7L));
        assertFalse(employee.isActive());
        assertSame(employee, service.activate(7L));
        assertTrue(employee.isActive());
        verify(accessLog).record(5L, "EMPLOYEE_DEACTIVATED: 1065999000");
        verify(accessLog).record(5L, "EMPLOYEE_ACTIVATED: 1065999000");
    }

    @ParameterizedTest
    @ValueSource(strings = {RoleNames.SECRETARY, RoleNames.SALESPERSON, RoleNames.CEMETERY_ADMIN})
    void otherRolesCannotReadOrModifyPersonnel(String roleName) {
        session.getCurrentUser().orElseThrow().getEmployee().getRole().setName(roleName);

        assertPersonnelAccessDenied();
    }

    @Test
    void unauthenticatedSessionCannotReadOrModifyPersonnel() {
        session.clear();

        assertPersonnelAccessDenied();
    }

    private void assertPersonnelAccessDenied() {
        assertThrows(UnauthorizedActionException.class, () -> service.search("", null, null, 0, 10));
        assertThrows(UnauthorizedActionException.class, () -> service.count(null));
        assertThrows(UnauthorizedActionException.class, () -> service.findById(7L));
        assertThrows(UnauthorizedActionException.class,
                () -> service.register(receptionist("77000111", null)));
        assertThrows(UnauthorizedActionException.class,
                () -> service.update(7L, receptionist("77000111", null)));
        assertThrows(UnauthorizedActionException.class, () -> service.activate(7L));
        assertThrows(UnauthorizedActionException.class, () -> service.deactivate(7L));
        verifyNoInteractions(employees, persons, users, accessLog);
    }

    private static EmployeeData receptionist(String document, String email) {
        return new EmployeeData(" Ana ", " Mejía ", " CC ", document, " 3001234567 ", email,
                " Calle 1 ", null, EmployeePosition.RECEPTIONIST, LocalDate.of(2024, 3, 1),
                new BigDecimal("1600000"), null, null, null, null, null, null, null, null, null);
    }

    private static EmployeeData driver(String document, String licenseFile, LocalDate licenseExpiry) {
        return new EmployeeData("Jorge Luis", "Araújo", "CC", document, null, null, null, null,
                EmployeePosition.DRIVER, LocalDate.of(2021, 6, 20), new BigDecimal("1850000"),
                null, null, null, null, null, "C2", "123-C2", licenseExpiry, licenseFile);
    }

    private static Receptionist receptionistWithId(Long id) {
        Receptionist receptionist = new Receptionist();
        receptionist.setId(id);
        receptionist.setDocumentNumber("1065999000");
        return receptionist;
    }
}
