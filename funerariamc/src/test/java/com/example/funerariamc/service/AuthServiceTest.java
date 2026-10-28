package com.example.funerariamc.service;

import com.example.funerariamc.exception.AuthenticationFailedException;
import com.example.funerariamc.exception.InactiveEmployeeException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Employee;
import com.example.funerariamc.model.Role;
import com.example.funerariamc.persistence.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AccessLogService audit = mock(AccessLogService.class);
    private final SessionContext session = new SessionContext();
    private AuthService service;
    private AppUser user;

    @BeforeEach
    void setUp() {
        service = new AuthService(users, encoder, audit, session);
        Employee employee = new Employee();
        employee.setRole(new Role(1L, RoleNames.SALESPERSON, "Vendedor"));
        user = new AppUser();
        user.setId(42L);
        user.setEmployee(employee);
        user.setPasswordHash("stored-hash");
    }

    @AfterEach
    void cleanTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void validLoginVerifiesTheHashRecordsSuccessAndSetsLastLogin() {
        validCredentials();
        LocalDateTime before = LocalDateTime.now();

        assertSame(user, service.login(" salesperson ", "secret"));

        verify(users).findByUsernameWithEmployeeAndRole("salesperson");
        verify(encoder).matches("secret", "stored-hash");
        verify(audit).record(42L, "LOGIN_SUCCESS");
        assertFalse(user.getLastLogin().isBefore(before));
        assertSame(user, session.getCurrentUser().orElseThrow());
    }

    @Test
    void unknownUserAndWrongPasswordHaveTheSamePublicMessage() {
        when(users.findByUsernameWithEmployeeAndRole("missing")).thenReturn(Optional.empty());
        String unknown = assertThrows(AuthenticationFailedException.class,
                () -> service.login("missing", "secret")).getMessage();
        when(users.findByUsernameWithEmployeeAndRole("salesperson")).thenReturn(Optional.of(user));

        String wrong = assertThrows(AuthenticationFailedException.class,
                () -> service.login("salesperson", "wrong")).getMessage();

        assertEquals("Usuario o contraseña incorrectos", unknown);
        assertEquals(unknown, wrong);
        verify(audit).record(42L, "LOGIN_FAILED: WRONG_PASSWORD");
        assertFalse(session.isAuthenticated());
        assertNull(user.getLastLogin());
    }

    @Test
    void blankCredentialsAreRejectedWithoutDatabaseAccess() {
        assertThrows(AuthenticationFailedException.class, () -> service.login(" ", "secret"));
        assertThrows(AuthenticationFailedException.class, () -> service.login("user", null));
        verifyNoInteractions(users, encoder, audit);
    }

    @Test
    void disabledAccountCannotStartASession() {
        validCredentials();
        user.setIsEnabled(false);

        assertThrows(AuthenticationFailedException.class, () -> service.login("salesperson", "secret"));

        verify(audit).record(42L, "LOGIN_FAILED: ACCOUNT_DISABLED");
        assertFalse(session.isAuthenticated());
    }

    @Test
    void inactiveEmployeeCannotStartASession() {
        validCredentials();
        user.getEmployee().deactivate();

        assertThrows(InactiveEmployeeException.class, () -> service.login("salesperson", "secret"));

        verify(audit).record(42L, "LOGIN_FAILED: INACTIVE_EMPLOYEE");
        assertFalse(session.isAuthenticated());
    }

    @Test
    void employeeWithoutARoleCannotStartASession() {
        validCredentials();
        user.getEmployee().setRole(null);

        assertThrows(AuthenticationFailedException.class, () -> service.login("salesperson", "secret"));

        verify(audit).record(42L, "LOGIN_FAILED: NO_ROLE");
        assertFalse(session.isAuthenticated());
    }

    @Test
    void failedAuditPreventsSessionCreation() {
        validCredentials();
        doThrow(new DataAccessResourceFailureException("Database unavailable"))
                .when(audit).record(42L, "LOGIN_SUCCESS");

        assertThrows(DataAccessResourceFailureException.class,
                () -> service.login("salesperson", "secret"));
        assertFalse(session.isAuthenticated());
    }

    @Test
    void transactionalLoginStartsTheSessionOnlyAfterCommit() {
        validCredentials();
        TransactionSynchronizationManager.initSynchronization();

        service.login("salesperson", "secret");
        assertFalse(session.isAuthenticated());
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCommit());

        assertTrue(session.isAuthenticated());
    }

    @Test
    void rolledBackLoginDoesNotLeaveAnAuthenticatedSession() {
        validCredentials();
        TransactionSynchronizationManager.initSynchronization();

        service.login("salesperson", "secret");
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertFalse(session.isAuthenticated());
    }

    @Test
    void visibleModulesComeFromTheCurrentRole() {
        session.start(user);

        assertEquals(Set.of(AppModule.CLIENTES, AppModule.PLANES, AppModule.CONTRATOS),
                Set.copyOf(service.visibleModules()));
    }

    @Test
    void deniedModuleIsAuditedAndRejected() {
        session.start(user);

        assertThrows(UnauthorizedActionException.class, () -> service.checkAccess(AppModule.BITACORA));
        verify(audit).record(42L, "ACCESS_DENIED: BITACORA");
    }

    @Test
    void allowedModuleIsAuditedBeforeOpening() {
        session.start(user);

        service.checkAccess(AppModule.CLIENTES);

        verify(audit).record(42L, "OPEN_MODULE: CLIENTES");
    }

    @Test
    void temporaryPasswordAllowsLoginButBlocksModulesUntilChanged() {
        validCredentials();
        user.setMustChangePassword(true);

        service.login("salesperson", "secret");

        assertSame(user, session.getCurrentUser().orElseThrow());
        assertTrue(service.visibleModules().isEmpty());
        assertFalse(service.canAccess(AppModule.CLIENTES));
        assertThrows(UnauthorizedActionException.class, () -> service.checkAccess(AppModule.CLIENTES));
        verify(audit).record(42L, "ACCESS_DENIED: CLIENTES");

        user.setMustChangePassword(false);
        assertTrue(service.canAccess(AppModule.CLIENTES));
        service.checkAccess(AppModule.CLIENTES);
        verify(audit).record(42L, "OPEN_MODULE: CLIENTES");
    }

    @Test
    void unauthenticatedUserHasNoModulesAndCannotOpenOne() {
        assertTrue(service.visibleModules().isEmpty());
        assertFalse(service.canAccess(AppModule.CLIENTES));
        assertThrows(UnauthorizedActionException.class, () -> service.checkAccess(AppModule.CLIENTES));
        verifyNoInteractions(audit);
    }

    @Test
    void logoutRecordsTheActionAndClearsTheSession() {
        session.start(user);

        service.logout();

        verify(audit).record(42L, "LOGOUT");
        assertFalse(session.isAuthenticated());
    }

    private void validCredentials() {
        when(users.findByUsernameWithEmployeeAndRole("salesperson")).thenReturn(Optional.of(user));
        when(encoder.matches("secret", "stored-hash")).thenReturn(true);
    }
}
