package com.example.funerariamc.service;

import com.example.funerariamc.model.AccessLog;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.persistence.AccessLogRepository;
import com.example.funerariamc.persistence.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccessLogServiceTest {

    private final AccessLogRepository logs = mock(AccessLogRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private AccessLogService service;

    @BeforeEach
    void setUp() {
        service = new AccessLogService(logs, users);
    }

    @Test
    void recordsTheUserActionAndAnIpValue() {
        AppUser user = new AppUser();
        user.setId(42L);
        when(users.getReferenceById(42L)).thenReturn(user);

        service.record(42L, AccessLogService.LOGIN_SUCCESS);

        ArgumentCaptor<AccessLog> entry = ArgumentCaptor.forClass(AccessLog.class);
        verify(logs).save(entry.capture());
        assertAll(
                () -> assertSame(user, entry.getValue().getAppUser()),
                () -> assertEquals("LOGIN_SUCCESS", entry.getValue().getAction()),
                () -> assertFalse(entry.getValue().getIpAddress().isBlank())
        );
    }

    @Test
    void auditWriteFailureIsPropagatedToTheCallingOperation() {
        when(users.getReferenceById(42L)).thenReturn(new AppUser());
        when(logs.save(any(AccessLog.class)))
                .thenThrow(new DataAccessResourceFailureException("Database unavailable"));

        assertThrows(DataAccessResourceFailureException.class,
                () -> service.record(42L, AccessLogService.LOGIN_SUCCESS));
    }

    @Test
    void recentEntriesRespectTheRequestedLimit() {
        List<AccessLog> expected = List.of(new AccessLog());
        when(logs.findRecent(any(Pageable.class))).thenReturn(expected);

        assertSame(expected, service.findRecent(200));

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(logs).findRecent(page.capture());
        assertEquals(0, page.getValue().getPageNumber());
        assertEquals(200, page.getValue().getPageSize());
    }

    @Test
    void invalidAuditEntriesAreRejectedBeforePersistence() {
        assertThrows(NullPointerException.class, () -> service.record(null, "LOGIN_SUCCESS"));
        assertThrows(IllegalArgumentException.class, () -> service.record(42L, " "));
        assertThrows(IllegalArgumentException.class, () -> service.findRecent(0));
        verifyNoInteractions(logs, users);
    }
}
