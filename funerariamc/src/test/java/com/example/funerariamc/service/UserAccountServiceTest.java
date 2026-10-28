package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.ResourceNotFoundException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Driver;
import com.example.funerariamc.model.Employee;
import com.example.funerariamc.model.Manager;
import com.example.funerariamc.model.Receptionist;
import com.example.funerariamc.model.Role;
import com.example.funerariamc.model.enums.EmployeePosition;
import com.example.funerariamc.persistence.AppUserRepository;
import com.example.funerariamc.persistence.EmployeeRepository;
import com.example.funerariamc.persistence.RoleRepository;
import com.example.funerariamc.service.UserAccountService.AccountCredentials;
import com.example.funerariamc.service.UserAccountService.AccountSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** HU-06 / HU-07: cuenta creada desde Personal con contraseña temporal y cambio obligatorio en el primer ingreso. */
class UserAccountServiceTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    /** Costo bajo solo para que la prueba sea rápida; la app usa 12. */
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final AccessLogService accessLog = mock(AccessLogService.class);
    private final SessionContext session = new SessionContext();
    private final UserAccountService service =
            new UserAccountService(users, employees, roles, encoder, accessLog, session);

    private Receptionist secretary;

    @BeforeEach
    void loginAsMasterAdmin() {
        Manager admin = new Manager();
        admin.setId(99L);
        admin.setRole(new Role(1L, RoleNames.MASTER_ADMIN, null));
        AppUser user = new AppUser();
        user.setId(5L);
        user.setEmployee(admin);
        session.start(user);

        secretary = new Receptionist();
        secretary.setId(20L);
        secretary.setName("Facundo");
        secretary.setLastname("Liñán Pérez");
        when(employees.findById(20L)).thenReturn(Optional.of(secretary));
        when(roles.findByName(RoleNames.SECRETARY)).thenReturn(Optional.of(new Role(4L, RoleNames.SECRETARY, null)));
        when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void cleanTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void createsAccountWithTemporaryPasswordAndRole() {
        AccountCredentials credentials = service.createAccount(20L, " FLinan ", RoleNames.SECRETARY);

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(saved.capture());
        AppUser user = saved.getValue();
        assertEquals("flinan", credentials.username());
        assertEquals("flinan", user.getUsername());
        assertTrue(user.getMustChangePassword());
        assertTrue(user.getIsEnabled());
        assertTrue(encoder.matches(credentials.temporaryPassword(), user.getPasswordHash()));
        assertNotEquals(credentials.temporaryPassword(), user.getPasswordHash());
        assertEquals(RoleNames.SECRETARY, secretary.getRole().getName());
        verify(accessLog).record(5L, "USER_ACCOUNT_CREATED: flinan");
    }

    @Test
    void temporaryPasswordHasLettersAndDigits() {
        for (int i = 0; i < 50; i++) {
            String password = service.temporaryPassword();
            assertTrue(password.matches("[A-Za-z0-9]{4}-[A-Za-z0-9]{4}"), password);
            assertTrue(password.matches(".*[A-Z].*") && password.matches(".*[a-z].*") && password.matches(".*\\d.*"));
            assertFalse(password.matches(".*[0O1lI].*"), "sin caracteres que se confunden: " + password);
        }
    }

    @Test
    void suggestsUsernameWithoutAccentsAndAddsNumberWhenTaken() {
        when(users.existsByUsernameIgnoreCase("flinan")).thenReturn(true);

        assertEquals("flinan2", service.suggestUsername("Facundo", "Liñán Pérez"));
    }

    @Test
    void usernameSuggestionStaysWithinTheColumnLengthAfterManyCollisions() {
        when(users.existsByUsernameIgnoreCase(anyString()))
                .thenAnswer(invocation -> !((String) invocation.getArgument(0)).endsWith("100"));

        String suggested = service.suggestUsername("Facundo", "abcdefghijklmnopqrstuvwxyz");

        assertEquals(25, suggested.length());
        assertTrue(suggested.endsWith("100"));
        assertTrue(suggested.matches("[a-z0-9._]+"));
    }

    @Test
    void validatesRoleAvailabilityBeforeTheFormSavesAnEmployee() {
        when(roles.findByName(RoleNames.SECRETARY)).thenReturn(Optional.empty());

        assertThrows(InvalidDataException.class,
                () -> service.validateNewAccount(EmployeePosition.RECEPTIONIST, "flinan", RoleNames.SECRETARY));

        verifyNoInteractions(employees, accessLog);
        verify(users, never()).save(any());
    }

    @Test
    void missingEmployeeCannotReceiveAnAccount() {
        assertThrows(ResourceNotFoundException.class,
                () -> service.createAccount(404L, "flinan", RoleNames.SECRETARY));

        verify(users, never()).save(any());
        verifyNoInteractions(accessLog);
    }

    @Test
    void missingActorCannotCreateAnAccountOrChangePassword() {
        session.getCurrentUser().orElseThrow().setId(null);

        assertThrows(UnauthorizedActionException.class,
                () -> service.createAccount(20L, "flinan", RoleNames.SECRETARY));
        assertThrows(UnauthorizedActionException.class,
                () -> service.changeOwnPassword("Kp7m-Qx4w", "Funeraria2026", "Funeraria2026"));

        verifyNoInteractions(users, employees, roles, accessLog);
    }

    @Test
    void rejectsDuplicateUsername() {
        when(users.existsByUsernameIgnoreCase("flinan")).thenReturn(true);

        InvalidDataException failure = assertThrows(InvalidDataException.class,
                () -> service.createAccount(20L, "flinan", RoleNames.SECRETARY));
        assertFalse(failure.getMessage().contains("flinan"));
        verify(users, never()).save(any());
    }

    @Test
    void rejectsInvalidUsername() {
        assertThrows(InvalidDataException.class, () -> service.createAccount(20L, "fl", RoleNames.SECRETARY));
        assertThrows(InvalidDataException.class, () -> service.createAccount(20L, "f liñan", RoleNames.SECRETARY));
    }

    @Test
    void rejectsRoleThatDoesNotMatchPosition() {
        assertThrows(InvalidDataException.class, () -> service.createAccount(20L, "flinan", RoleNames.MASTER_ADMIN));
        verify(users, never()).save(any());
    }

    @Test
    void positionsWithoutSystemAccessHaveNoRoles() {
        // RN-23: conductores, tanatopraxia y cafetería no ingresan al sistema.
        assertTrue(UserAccountService.rolesFor(EmployeePosition.DRIVER).isEmpty());
        assertEquals(List.of(RoleNames.CEMETERY_ADMIN, RoleNames.MASTER_ADMIN),
                UserAccountService.rolesFor(EmployeePosition.MANAGER));

        Driver driver = new Driver();
        driver.setId(30L);
        when(employees.findById(30L)).thenReturn(Optional.of(driver));
        assertThrows(InvalidDataException.class, () -> service.createAccount(30L, "jaraujo", RoleNames.SECRETARY));
    }

    @Test
    void rejectsInactiveEmployeeOrSecondAccount() {
        secretary.deactivate();
        assertThrows(InvalidDataException.class, () -> service.createAccount(20L, "flinan", RoleNames.SECRETARY));

        secretary.activate();
        when(users.existsByEmployee_Id(20L)).thenReturn(true);
        assertThrows(InvalidDataException.class, () -> service.createAccount(20L, "flinan", RoleNames.SECRETARY));
    }

    @Test
    void onlyMasterAdminCreatesAccounts() {
        session.clear();
        assertAdminEndpointsDenied();
    }

    @ParameterizedTest
    @ValueSource(strings = {RoleNames.SECRETARY, RoleNames.SALESPERSON, RoleNames.CEMETERY_ADMIN})
    void otherRolesCannotManageOrInspectAccounts(String roleName) {
        session.getCurrentUser().orElseThrow().getEmployee().setRole(new Role(2L, roleName, null));

        assertAdminEndpointsDenied();
    }

    private void assertAdminEndpointsDenied() {
        assertThrows(UnauthorizedActionException.class,
                () -> service.createAccount(20L, "flinan", RoleNames.SECRETARY));
        assertThrows(UnauthorizedActionException.class,
                () -> service.validateNewAccount(EmployeePosition.RECEPTIONIST, "flinan", RoleNames.SECRETARY));
        assertThrows(UnauthorizedActionException.class, () -> service.suggestUsername("Ana", "Pérez"));
        assertThrows(UnauthorizedActionException.class, () -> service.findAccount(20L));
        assertThrows(UnauthorizedActionException.class, () -> service.findAccounts(List.of(20L)));
        assertThrows(UnauthorizedActionException.class, service::countActiveAccounts);
        verifyNoInteractions(users, employees, roles, accessLog);
    }

    @Test
    void emptyAccountSelectionDoesNotQueryTheDatabase() {
        assertTrue(service.findAccounts(List.of()).isEmpty());
        assertTrue(service.findAccounts(null).isEmpty());
        verifyNoInteractions(users);
    }

    @Test
    void summaryCarriesRoleAndFirstLoginState() {
        secretary.setRole(new Role(4L, RoleNames.SECRETARY, null));
        AppUser user = account(secretary, "Kp7m-Qx4w", true);
        when(users.findByEmployee_IdIn(List.of(20L))).thenReturn(List.of(user));

        Map<Long, AccountSummary> summaries = service.findAccounts(List.of(20L));

        AccountSummary summary = summaries.get(20L);
        assertEquals("flinan", summary.username());
        assertEquals(RoleNames.SECRETARY, summary.roleName());
        assertTrue(summary.enabled());
        assertTrue(summary.mustChangePassword());
    }

    // ---------- primer ingreso ----------

    @Test
    void firstLoginChangesTemporaryPasswordOnce() {
        AppUser user = loginAs(secretary, "Kp7m-Qx4w");

        service.changeOwnPassword("Kp7m-Qx4w", "Funeraria2026", "Funeraria2026");

        assertFalse(user.getMustChangePassword());
        assertTrue(encoder.matches("Funeraria2026", user.getPasswordHash()));
        verify(accessLog).record(user.getId(), "PASSWORD_CHANGED");
    }

    @Test
    void firstLoginRejectsWrongCurrentPassword() {
        AppUser user = loginAs(secretary, "Kp7m-Qx4w");

        assertThrows(InvalidDataException.class,
                () -> service.changeOwnPassword("otra", "Funeraria2026", "Funeraria2026"));
        assertTrue(user.getMustChangePassword());
    }

    @Test
    void firstLoginValidatesNewPassword() {
        loginAs(secretary, "Kp7m-Qx4w");

        assertThrows(InvalidDataException.class, () -> service.changeOwnPassword("Kp7m-Qx4w", "corta1", "corta1"));
        assertThrows(InvalidDataException.class,
                () -> service.changeOwnPassword("Kp7m-Qx4w", "solamenteletras", "solamenteletras"));
        assertThrows(InvalidDataException.class,
                () -> service.changeOwnPassword("Kp7m-Qx4w", "Funeraria2026", "Funeraria2027"));
        assertThrows(InvalidDataException.class, () -> service.changeOwnPassword("Kp7m-Qx4w", "Kp7m-Qx4w", "Kp7m-Qx4w"));
    }

    @Test
    void passwordChangeReleasesTheDetachedSessionOnlyAfterCommit() {
        AppUser sessionUser = loginAs(secretary, "Kp7m-Qx4w");
        AppUser managedUser = account(secretary, "Kp7m-Qx4w", true);
        when(users.findById(sessionUser.getId())).thenReturn(Optional.of(managedUser));
        TransactionSynchronizationManager.initSynchronization();

        service.changeOwnPassword("Kp7m-Qx4w", "Funeraria2026", "Funeraria2026");

        assertTrue(sessionUser.getMustChangePassword());
        assertFalse(managedUser.getMustChangePassword());
        TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCommit());
        assertFalse(sessionUser.getMustChangePassword());
    }

    @Test
    void rolledBackPasswordChangeKeepsTheDetachedSessionBlocked() {
        AppUser sessionUser = loginAs(secretary, "Kp7m-Qx4w");
        AppUser managedUser = account(secretary, "Kp7m-Qx4w", true);
        when(users.findById(sessionUser.getId())).thenReturn(Optional.of(managedUser));
        TransactionSynchronizationManager.initSynchronization();

        service.changeOwnPassword("Kp7m-Qx4w", "Funeraria2026", "Funeraria2026");
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertTrue(sessionUser.getMustChangePassword());
    }

    @Test
    void committedChangeDoesNotUnlockADifferentSession() {
        AppUser originalSession = loginAs(secretary, "Kp7m-Qx4w");
        AppUser managedUser = account(secretary, "Kp7m-Qx4w", true);
        when(users.findById(originalSession.getId())).thenReturn(Optional.of(managedUser));
        TransactionSynchronizationManager.initSynchronization();
        service.changeOwnPassword("Kp7m-Qx4w", "Funeraria2026", "Funeraria2026");
        AppUser replacementSession = account(secretary, "OtraClave2026", true);
        session.start(replacementSession);

        TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCommit());

        assertSame(replacementSession, session.getCurrentUser().orElseThrow());
        assertTrue(replacementSession.getMustChangePassword());
    }

    @Test
    void failedPasswordAuditKeepsTheSessionBlockedAndRollsBack() {
        AppUser user = loginAs(secretary, "Kp7m-Qx4w");
        String originalHash = user.getPasswordHash();
        doThrow(new IllegalStateException("Audit unavailable"))
                .when(accessLog).record(8L, AccessLogService.PASSWORD_CHANGED);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        SimpleTransactionStatus status = new SimpleTransactionStatus();
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(status);

        assertThrows(IllegalStateException.class,
                () -> transactionalProxy(transactions).changeOwnPassword(
                        "Kp7m-Qx4w", "Funeraria2026", "Funeraria2026"));

        assertTrue(user.getMustChangePassword());
        assertEquals(originalHash, user.getPasswordHash());
        verify(transactions).rollback(status);
        verify(transactions, never()).commit(any());
    }

    @Test
    void failedCreationAuditRollsBackAccountAndRoleAssignment() {
        doThrow(new IllegalStateException("Audit unavailable"))
                .when(accessLog).record(5L, AccessLogService.USER_ACCOUNT_CREATED + "flinan");
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        SimpleTransactionStatus status = new SimpleTransactionStatus();
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(status);

        assertThrows(IllegalStateException.class,
                () -> transactionalProxy(transactions).createAccount(20L, "flinan", RoleNames.SECRETARY));

        verify(transactions).rollback(status);
        verify(transactions, never()).commit(any());
    }

    @Test
    void disabledOrInactiveAccountCannotChangeItsPassword() {
        AppUser user = loginAs(secretary, "Kp7m-Qx4w");
        user.setIsEnabled(false);
        assertThrows(UnauthorizedActionException.class,
                () -> service.changeOwnPassword("Kp7m-Qx4w", "Funeraria2026", "Funeraria2026"));
        user.setIsEnabled(true);
        secretary.deactivate();
        assertThrows(UnauthorizedActionException.class,
                () -> service.changeOwnPassword("Kp7m-Qx4w", "Funeraria2026", "Funeraria2026"));
        assertTrue(user.getMustChangePassword());
        verifyNoInteractions(accessLog);
    }

    private UserAccountService transactionalProxy(PlatformTransactionManager transactions) {
        TransactionInterceptor advice = new TransactionInterceptor();
        advice.setTransactionManager(transactions);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(service);
        factory.addAdvice(advice);
        return (UserAccountService) factory.getProxy();
    }

    private AppUser loginAs(Employee employee, String password) {
        employee.setRole(new Role(4L, RoleNames.SECRETARY, null));
        AppUser user = account(employee, password, true);
        session.start(user);
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        return user;
    }

    private AppUser account(Employee employee, String password, boolean mustChange) {
        AppUser user = new AppUser();
        user.setId(8L);
        user.setEmployee(employee);
        user.setUsername("flinan");
        user.setPasswordHash(encoder.encode(password));
        user.setIsEnabled(true);
        user.setMustChangePassword(mustChange);
        return user;
    }
}
