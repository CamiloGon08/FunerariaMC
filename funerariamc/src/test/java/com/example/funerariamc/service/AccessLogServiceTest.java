package com.example.funerariamc.service;

import com.example.funerariamc.model.AccessLog;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.persistence.AccessLogRepository;
import com.example.funerariamc.persistence.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

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

    @Test
    void pageContainsTheRequestedEntriesAndTheFullCatalogTotals() {
        AccessLog fifth = new AccessLog();
        fifth.setId(5L);
        AccessLog sixth = new AccessLog();
        sixth.setId(6L);
        List<AccessLog> entries = List.of(fifth, sixth);
        Page<AccessLog> expected = new PageImpl<>(entries, PageRequest.of(2, 2), 7L);
        when(logs.findPage(any(Pageable.class))).thenReturn(expected);

        Page<AccessLog> page = service.findPage(2, 2);

        assertSame(expected, page);
        assertAll(
                () -> assertEquals(entries, page.getContent()),
                () -> assertEquals(2, page.getNumber()),
                () -> assertEquals(2, page.getSize()),
                () -> assertEquals(7L, page.getTotalElements()),
                () -> assertEquals(4, page.getTotalPages()),
                () -> assertTrue(page.hasPrevious()),
                () -> assertTrue(page.hasNext())
        );
        ArgumentCaptor<Pageable> request = ArgumentCaptor.forClass(Pageable.class);
        verify(logs).findPage(request.capture());
        assertEquals(4L, request.getValue().getOffset());
    }

    @Test
    void partialLastPageRetainsTheTotalAndHasNoNextPage() {
        Page<AccessLog> expected = new PageImpl<>(List.of(new AccessLog()), PageRequest.of(3, 2), 7L);
        when(logs.findPage(any(Pageable.class))).thenReturn(expected);

        Page<AccessLog> page = service.findPage(3, 2);

        assertSame(expected, page);
        assertAll(
                () -> assertEquals(1, page.getNumberOfElements()),
                () -> assertEquals(7L, page.getTotalElements()),
                () -> assertEquals(4, page.getTotalPages()),
                () -> assertTrue(page.isLast()),
                () -> assertFalse(page.hasNext())
        );
    }

    @Test
    void emptyCatalogProducesAnEmptyPageWithZeroTotals() {
        Page<AccessLog> expected = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L);
        when(logs.findPage(any(Pageable.class))).thenReturn(expected);

        Page<AccessLog> page = service.findPage(0, 20);

        assertSame(expected, page);
        assertTrue(page.isEmpty());
        assertEquals(0L, page.getTotalElements());
        assertEquals(0, page.getTotalPages());
    }

    @Test
    void pageBeyondTheLastEntryKeepsTheActualTotal() {
        Page<AccessLog> expected = new PageImpl<>(List.of(), PageRequest.of(10, 2), 7L);
        when(logs.findPage(any(Pageable.class))).thenReturn(expected);

        Page<AccessLog> page = service.findPage(10, 2);

        assertSame(expected, page);
        assertTrue(page.isEmpty());
        assertEquals(10, page.getNumber());
        assertEquals(7L, page.getTotalElements());
        assertEquals(4, page.getTotalPages());
    }

    @ParameterizedTest
    @CsvSource({
            "-3, -2, 0, 1",
            "-1, 0, 0, 1",
            "2, 500, 2, 100",
            "1, 100, 1, 100",
            "0, 1, 0, 1",
            "2, 50, 2, 50"
    })
    void paginationBoundsMatchTheControllerContract(int page, int size, int expectedPage, int expectedSize) {
        when(logs.findPage(any(Pageable.class))).thenAnswer(invocation ->
                new PageImpl<AccessLog>(List.of(), invocation.getArgument(0, Pageable.class), 0L));

        Page<AccessLog> result = service.findPage(page, size);

        ArgumentCaptor<Pageable> request = ArgumentCaptor.forClass(Pageable.class);
        verify(logs).findPage(request.capture());
        assertAll(
                () -> assertEquals(expectedPage, request.getValue().getPageNumber()),
                () -> assertEquals(expectedSize, request.getValue().getPageSize()),
                () -> assertEquals(expectedPage, result.getNumber()),
                () -> assertEquals(expectedSize, result.getSize())
        );
    }

    @Test
    void paginatedLookupPropagatesPersistenceFailure() {
        when(logs.findPage(any(Pageable.class)))
                .thenThrow(new DataAccessResourceFailureException("Database unavailable"));

        assertThrows(DataAccessResourceFailureException.class, () -> service.findPage(0, 20));

        verify(logs, never()).count();
        verify(logs, never()).findRecent(any(Pageable.class));
        verifyNoInteractions(users);
    }
}
