package com.example.funerariamc.service;

import com.example.funerariamc.exception.ResourceNotFoundException;
import com.example.funerariamc.model.Role;
import com.example.funerariamc.persistence.RoleRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoleServiceTest {

    private final RoleRepository roles = mock(RoleRepository.class);
    private final RoleService service = new RoleService(roles);

    @Test
    void catalogUsesPersistedRolesRatherThanAnInMemoryRoleList() {
        Role storedRole = new Role(42L, RoleNames.SALESPERSON, "Descripción persistida");
        when(roles.findAll()).thenReturn(List.of(storedRole));

        List<Role> catalog = service.findAll();

        assertEquals(1, catalog.size());
        assertSame(storedRole, catalog.get(0));
        assertEquals(42L, catalog.get(0).getId());
        verifyNoMoreInteractionsAfterCatalogRead();
    }

    @Test
    void emptyCatalogDoesNotCreateDefaultRoles() {
        when(roles.findAll()).thenReturn(List.of());

        assertTrue(service.findAll().isEmpty());

        verifyNoMoreInteractionsAfterCatalogRead();
    }

    @Test
    void existingIdentifierReturnsThePersistedRole() {
        Role storedRole = new Role(42L, RoleNames.SECRETARY, "Secretaría");
        when(roles.findById(42L)).thenReturn(Optional.of(storedRole));

        assertSame(storedRole, service.findById(42L));

        verify(roles).findById(42L);
        verifyNoMoreInteractions(roles);
    }

    @Test
    void missingIdentifierRaisesTheBusinessErrorWithTheRequestedId() {
        when(roles.findById(99L)).thenReturn(Optional.empty());

        ResourceNotFoundException failure = assertThrows(ResourceNotFoundException.class,
                () -> service.findById(99L));

        assertEquals("Rol con id 99 no existe.", failure.getMessage());
        verify(roles).findById(99L);
        verifyNoMoreInteractions(roles);
    }

    private void verifyNoMoreInteractionsAfterCatalogRead() {
        verify(roles).findAll();
        verifyNoMoreInteractions(roles);
    }
}
