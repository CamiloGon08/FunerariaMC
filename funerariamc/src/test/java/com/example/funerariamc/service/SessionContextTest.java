package com.example.funerariamc.service;

import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Employee;
import com.example.funerariamc.model.Role;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionContextTest {

    private final SessionContext session = new SessionContext();

    @Test
    void newSessionContainsNoUserOrRole() {
        assertEmptySession();
    }

    @Test
    void startExposesTheEmployeeRoleAndDisplayName() {
        AppUser user = userWithRole(RoleNames.SALESPERSON);
        session.start(user);

        assertAll(
                () -> assertTrue(session.isAuthenticated()),
                () -> assertSame(user, session.getCurrentUser().orElseThrow()),
                () -> assertEquals(RoleNames.SALESPERSON, session.getRoleName().orElseThrow()),
                () -> assertEquals("Persona Prueba", session.getDisplayName())
        );
    }

    @Test
    void clearRemovesThePreviousUserAndRole() {
        session.start(userWithRole(RoleNames.MASTER_ADMIN));
        session.clear();

        assertEmptySession();
    }

    @Test
    void startingAnotherSessionReplacesThePreviousIdentity() {
        session.start(userWithRole(RoleNames.MASTER_ADMIN));
        AppUser nextUser = userWithRole(RoleNames.SECRETARY);
        session.start(nextUser);

        assertSame(nextUser, session.getCurrentUser().orElseThrow());
        assertEquals(RoleNames.SECRETARY, session.getRoleName().orElseThrow());
    }

    @Test
    void userWithoutAnEmployeeDoesNotInventARoleOrDisplayName() {
        session.start(new AppUser());

        assertTrue(session.getRoleName().isEmpty());
        assertEquals("", session.getDisplayName());
    }

    @Test
    void employeeWithoutARoleDoesNotAcquirePermissions() {
        AppUser user = userWithRole(RoleNames.SALESPERSON);
        user.getEmployee().setRole(null);
        session.start(user);

        assertTrue(session.getRoleName().isEmpty());
    }

    @Test
    void nullStartDoesNotEraseAnExistingSession() {
        AppUser user = userWithRole(RoleNames.SALESPERSON);
        session.start(user);

        assertThrows(NullPointerException.class, () -> session.start(null));
        assertSame(user, session.getCurrentUser().orElseThrow());
    }

    private void assertEmptySession() {
        assertAll(
                () -> assertFalse(session.isAuthenticated()),
                () -> assertTrue(session.getCurrentUser().isEmpty()),
                () -> assertTrue(session.getRoleName().isEmpty()),
                () -> assertEquals("", session.getDisplayName())
        );
    }

    private static AppUser userWithRole(String roleName) {
        Employee employee = new Employee();
        employee.setName("Persona");
        employee.setLastname("Prueba");
        employee.setRole(new Role(1L, roleName, "Rol de prueba"));
        AppUser user = new AppUser();
        user.setEmployee(employee);
        return user;
    }
}
