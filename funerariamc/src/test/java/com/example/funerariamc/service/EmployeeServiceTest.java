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
import com.example.funerariamc.model.SystemParameter;
import com.example.funerariamc.model.Thanatopractor;
import com.example.funerariamc.model.enums.EmployeePosition;
import com.example.funerariamc.model.enums.EmployeeStatus;
import com.example.funerariamc.model.enums.Sex;
import com.example.funerariamc.persistence.AppUserRepository;
import com.example.funerariamc.persistence.EmployeeRepository;
import com.example.funerariamc.persistence.PersonRepository;
import com.example.funerariamc.persistence.SystemParameterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
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
    private final SystemParameterService parameters = mock(SystemParameterService.class);
    private final EmployeeService service = new EmployeeService(employees, persons, users, accessLog, session, parameters);

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
    void savesSexAndRequiresIt() {
        Employee saved = service.register(driver("12725893", "file:/C:/licencia.pdf", LocalDate.now().plusYears(2)));
        assertEquals(Sex.MALE, saved.getSex());

        EmployeeData noSex = new EmployeeData("Jorge Luis", "Araújo", "CC", "12725894", null, null, null, null, null,
                EmployeePosition.DRIVER, LocalDate.of(2021, 6, 20), new BigDecimal("1850000"),
                null, null, null, null, null, "C2", "123-C2", LocalDate.now().plusYears(2), "file:/C:/licencia.pdf");
        clearInvocations(employees, persons, users, accessLog);
        assertThrows(InvalidDataException.class, () -> service.register(noSex));
        assertThrows(InvalidDataException.class, () -> service.update(7L, noSex));
        verifyNoInteractions(employees, persons, users, accessLog);
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
        EmployeeData data = new EmployeeData("Laura", "Daza", "CC", "1065000111", null, null, null, null, Sex.FEMALE,
                EmployeePosition.FUNERAL_SALESPERSON, LocalDate.of(2024, 3, 1), new BigDecimal("1600000"),
                new BigDecimal("120"), null, null, null, null, null, null, null, null);

        assertThrows(InvalidDataException.class, () -> service.register(data));
    }

    @Test
    void salespersonKeepsCommissionRate() {
        EmployeeData data = new EmployeeData("Laura", "Daza", "CC", "1065000111", null, null, null, null, Sex.FEMALE,
                EmployeePosition.FUNERAL_SALESPERSON, LocalDate.of(2024, 3, 1), new BigDecimal("1600000"),
                new BigDecimal("5.5"), null, null, null, null, null, null, null, null);

        FuneralSalesperson saved = (FuneralSalesperson) service.register(data);

        assertEquals(new BigDecimal("5.5"), saved.getCommissionRate());
    }

    @Test
    void hireDateCannotBeInTheFuture() {
        EmployeeData data = new EmployeeData("Ana", "Mejía", "CC", "77000111", null, null, null, null, Sex.FEMALE,
                EmployeePosition.RECEPTIONIST, LocalDate.now(AccessLogService.APP_ZONE).plusDays(3), new BigDecimal("1423500"),
                null, null, null, null, null, null, null, null, null);

        assertThrows(InvalidDataException.class, () -> service.register(data));
    }

    // ---------- HU-03 ----------

    @Test
    void thanatopractorWithoutLicenseNumberIsAccepted() {
        EmployeeData data = new EmployeeData("Andrea", "Pumarejo", "CC", "1003418267", null, null, null, null, Sex.FEMALE,
                EmployeePosition.THANATOPRACTOR, LocalDate.of(2023, 1, 15), new BigDecimal("2000000"),
                null, "SENA", LocalDate.of(2022, 5, 1), "file:/C:/certificado.pdf", new BigDecimal("80000"),
                null, null, null, null);

        assertInstanceOf(Thanatopractor.class, service.register(data));
    }

    @Test
    void thanatopractorNeedsCertificateFile() {
        EmployeeData data = new EmployeeData("Andrea", "Pumarejo", "CC", "1003418267", null, null, null, null, Sex.FEMALE,
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
    void searchByPositionsUsesTheSelectedPositionsStatusAndPage() {
        Page<Employee> expected = Page.empty(PageRequest.of(2, 10));
        when(employees.search(any(), any(), any(), any())).thenReturn(expected);

        Page<Employee> result = service.searchByPositions(" Ana ", EmployeeStatus.INACTIVE,
                List.of(EmployeePosition.MANAGER, EmployeePosition.RECEPTIONIST), 2, 10);

        assertSame(expected, result);
        verify(employees).search(eq("%ana%"), eq(List.of(EmployeeStatus.INACTIVE)),
                eq(List.of(Manager.class, Receptionist.class)), eq(PageRequest.of(2, 10)));
    }

    @Test
    void searchByPositionsAcceptsNullAndEmptySelectionsAsAllPositions() {
        when(employees.search(any(), any(), any(), any())).thenReturn(Page.empty());

        service.searchByPositions(null, null, null, 0, 10);
        service.searchByPositions("", null, List.of(), 1, 10);

        verify(employees).search(eq("%"), eq(List.of(EmployeeStatus.values())),
                argThat(types -> types.size() == EmployeePosition.values().length), eq(PageRequest.of(0, 10)));
        verify(employees).search(eq("%"), eq(List.of(EmployeeStatus.values())),
                argThat(types -> types.size() == EmployeePosition.values().length), eq(PageRequest.of(1, 10)));
    }

    @Test
    void searchByPositionsRejectsInvalidSelectionBeforeQuerying() {
        assertThrows(InvalidDataException.class, () -> service.searchByPositions("", null,
                Arrays.asList(EmployeePosition.DRIVER, null), 0, 10));

        verifyNoInteractions(employees, persons, users, accessLog);
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
        assertEquals(Sex.FEMALE, updated.getSex());
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
        assertThrows(UnauthorizedActionException.class,
                () -> service.searchByPositions("", null, List.of(EmployeePosition.MANAGER), 0, 10));
        assertThrows(UnauthorizedActionException.class, () -> service.count(null));
        assertThrows(UnauthorizedActionException.class, service::countExpiringDocuments);
        assertThrows(UnauthorizedActionException.class, service::kpis);
        assertThrows(UnauthorizedActionException.class, () -> service.hasLicenseExpiringSoon(new Driver()));
        assertThrows(UnauthorizedActionException.class, () -> service.findById(7L));
        assertThrows(UnauthorizedActionException.class,
                () -> service.register(receptionist("77000111", null)));
        assertThrows(UnauthorizedActionException.class,
                () -> service.update(7L, receptionist("77000111", null)));
        assertThrows(UnauthorizedActionException.class, () -> service.activate(7L));
        assertThrows(UnauthorizedActionException.class, () -> service.deactivate(7L));
        verifyNoInteractions(employees, persons, users, accessLog, parameters);
    }

    @Test
    void documentCountUsesTheAgreedParameterAndOnlyActiveDrivers() {
        LocalDate limit = LocalDate.now(AccessLogService.APP_ZONE).plusDays(12);
        when(parameters.getLong("DOC_EXPIRY_WARNING_DAYS")).thenReturn(12L);
        when(employees.countDriversWithLicenseExpiringBy(EmployeeStatus.ACTIVE, limit)).thenReturn(3L);

        assertEquals(3L, service.countExpiringDocuments());

        verify(parameters).getLong("DOC_EXPIRY_WARNING_DAYS");
        verify(employees).countDriversWithLicenseExpiringBy(EmployeeStatus.ACTIVE, limit);
    }

    @Test
    void kpisComeFromOneQueryWithTheAgreedWarningWindow() {
        LocalDate limit = LocalDate.now(AccessLogService.APP_ZONE).plusDays(12);
        when(parameters.getLong(EmployeeService.DOC_EXPIRY_WARNING_DAYS)).thenReturn(12L);
        when(employees.countStaff(limit)).thenReturn(new EmployeeRepository.StaffCounts() {
            public long getTotal() { return 9; }
            public long getActive() { return 7; }
            public long getWithAccount() { return 4; }
            public long getExpiring() { return 2; }
        });

        assertEquals(new EmployeeService.StaffKpis(9, 7, 4, 2), service.kpis());

        verify(parameters).getLong(EmployeeService.DOC_EXPIRY_WARNING_DAYS);
        verify(employees).countStaff(limit);
        verifyNoMoreInteractions(employees);
        verifyNoInteractions(users, persons, accessLog);
    }

    @Test
    void warningWindowIncludesTodayAndItsLastDayButNotExpiredOrLaterLicenses() {
        LocalDate today = LocalDate.now(AccessLogService.APP_ZONE);
        when(parameters.getLong(EmployeeService.DOC_EXPIRY_WARNING_DAYS)).thenReturn(12L);
        Driver driver = new Driver();

        driver.setLicenseExpiryDate(today);
        assertTrue(service.hasLicenseExpiringSoon(driver));
        driver.setLicenseExpiryDate(today.plusDays(12));
        assertTrue(service.hasLicenseExpiringSoon(driver));
        driver.setLicenseExpiryDate(today.plusDays(13));
        assertFalse(service.hasLicenseExpiringSoon(driver));
        driver.setLicenseExpiryDate(today.minusDays(1));
        assertFalse(service.hasLicenseExpiringSoon(driver));
    }

    @Test
    void renderingMultipleLicenseWarningsUsesOneParameterQueryAndNoPerCardTransactions() {
        SystemParameterRepository parameterRepository = mock(SystemParameterRepository.class);
        SystemParameter warning = new SystemParameter();
        warning.setParamName(EmployeeService.DOC_EXPIRY_WARNING_DAYS);
        warning.setParamValue("12");
        when(parameterRepository.findByParamName(EmployeeService.DOC_EXPIRY_WARNING_DAYS))
                .thenReturn(Optional.of(warning));
        SystemParameterService cachedParameters = new SystemParameterService(parameterRepository, accessLog, session);
        EmployeeService target = new EmployeeService(employees, persons, users, accessLog, session, cachedParameters);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TransactionInterceptor advice = new TransactionInterceptor();
        advice.setTransactionManager(transactions);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(advice);
        EmployeeService proxy = (EmployeeService) factory.getProxy();

        for (int card = 0; card < 20; card++) {
            Driver driver = new Driver();
            driver.setLicenseExpiryDate(LocalDate.now(AccessLogService.APP_ZONE).plusDays(12));
            assertTrue(proxy.hasLicenseExpiringSoon(driver));
        }

        verify(parameterRepository).findByParamName(EmployeeService.DOC_EXPIRY_WARNING_DAYS);
        verifyNoInteractions(transactions, employees, persons, users, accessLog);
    }

    @Test
    void changedConfigurationChangesTheWarningWithoutAFixedThirtyDayDefault() {
        when(parameters.getLong(EmployeeService.DOC_EXPIRY_WARNING_DAYS)).thenReturn(10L, 15L);
        Driver driver = new Driver();
        driver.setLicenseExpiryDate(LocalDate.now(AccessLogService.APP_ZONE).plusDays(12));

        assertFalse(service.hasLicenseExpiringSoon(driver));
        assertTrue(service.hasLicenseExpiringSoon(driver));
    }

    @Test
    void zeroDayWindowOnlyFlagsLicensesExpiringToday() {
        when(parameters.getLong(EmployeeService.DOC_EXPIRY_WARNING_DAYS)).thenReturn(0L);
        Driver driver = new Driver();
        driver.setLicenseExpiryDate(LocalDate.now(AccessLogService.APP_ZONE));
        assertTrue(service.hasLicenseExpiringSoon(driver));
        driver.setLicenseExpiryDate(LocalDate.now(AccessLogService.APP_ZONE).plusDays(1));
        assertFalse(service.hasLicenseExpiringSoon(driver));
    }

    @Test
    void nonDriversMissingDatesAndExpiredLicensesDoNotReadTheWarningParameter() {
        Driver expired = new Driver();
        expired.setLicenseExpiryDate(LocalDate.now(AccessLogService.APP_ZONE).minusDays(1));

        assertFalse(service.hasLicenseExpiringSoon(new Receptionist()));
        assertFalse(service.hasLicenseExpiringSoon(new Driver()));
        assertFalse(service.hasLicenseExpiringSoon(expired));
        assertFalse(service.hasLicenseExpiringSoon(null));
        verifyNoInteractions(parameters, employees);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 2147483648L, Long.MAX_VALUE})
    void invalidWarningDaysAreRejectedBeforeQueryingDrivers(long days) {
        when(parameters.getLong(EmployeeService.DOC_EXPIRY_WARNING_DAYS)).thenReturn(days);
        Driver driver = new Driver();
        driver.setLicenseExpiryDate(LocalDate.now(AccessLogService.APP_ZONE).plusDays(1));

        assertThrows(InvalidDataException.class, service::countExpiringDocuments);
        assertThrows(InvalidDataException.class, service::kpis);
        assertThrows(InvalidDataException.class, () -> service.hasLicenseExpiringSoon(driver));
        verifyNoInteractions(employees);
    }

    @Test
    void missingWarningParameterIsReportedInsteadOfInventingADefault() {
        InvalidDataException failure = new InvalidDataException("Falta configuración");
        when(parameters.getLong(EmployeeService.DOC_EXPIRY_WARNING_DAYS)).thenThrow(failure);
        Driver driver = new Driver();
        driver.setLicenseExpiryDate(LocalDate.now(AccessLogService.APP_ZONE).plusDays(1));

        assertSame(failure, assertThrows(InvalidDataException.class, service::countExpiringDocuments));
        assertSame(failure, assertThrows(InvalidDataException.class, service::kpis));
        assertSame(failure, assertThrows(InvalidDataException.class, () -> service.hasLicenseExpiringSoon(driver)));
        verifyNoInteractions(employees, persons, users, accessLog);
    }

    private static EmployeeData receptionist(String document, String email) {
        return new EmployeeData(" Ana ", " Mejía ", " CC ", document, " 3001234567 ", email,
                " Calle 1 ", null, Sex.FEMALE, EmployeePosition.RECEPTIONIST, LocalDate.of(2024, 3, 1),
                new BigDecimal("1600000"), null, null, null, null, null, null, null, null, null);
    }

    private static EmployeeData driver(String document, String licenseFile, LocalDate licenseExpiry) {
        return new EmployeeData("Jorge Luis", "Araújo", "CC", document, null, null, null, null, Sex.MALE,
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
