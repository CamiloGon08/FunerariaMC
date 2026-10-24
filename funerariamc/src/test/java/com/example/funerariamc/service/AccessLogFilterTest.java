package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.persistence.AccessLogRepository;
import com.example.funerariamc.persistence.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** RF-ACC-08: filtros de la bitácora y conversión de hora UTC → Colombia. */
class AccessLogFilterTest {

    private final AccessLogRepository logs = mock(AccessLogRepository.class);
    private final AccessLogService service = new AccessLogService(logs, mock(AppUserRepository.class));

    @Test
    void toAppTimeConvertsUtcToBogota() {
        assertEquals(LocalDateTime.of(2026, 10, 22, 20, 15, 12),
                AccessLogService.toAppTime(LocalDateTime.of(2026, 10, 23, 1, 15, 12)));
        assertNull(AccessLogService.toAppTime(null));
    }

    @Test
    void dateRangeCoversWholeColombianDaysInUtc() {
        when(logs.findPageFiltered(any(), any(), any(), any(Pageable.class))).thenReturn(Page.empty());

        service.findPage(0, 50, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 22), "  Admin ");

        verify(logs).findPageFiltered(
                eq(LocalDateTime.of(2026, 10, 20, 5, 0)),
                eq(LocalDateTime.of(2026, 10, 23, 5, 0)),
                eq("%admin%"),
                any(Pageable.class));
    }

    @Test
    void emptyFiltersBringEverything() {
        when(logs.findPageFiltered(any(), any(), any(), any(Pageable.class))).thenReturn(Page.empty());

        service.findPage(0, 50, null, null, " ");

        verify(logs).findPageFiltered(any(LocalDateTime.class), any(LocalDateTime.class), eq("%"), any(Pageable.class));
    }

    @Test
    void singleDateIncludesItsWholeDayAndCapsPageSize() {
        Page<com.example.funerariamc.model.AccessLog> expected = Page.empty();
        when(logs.findPageFiltered(any(), any(), any(), any(Pageable.class))).thenReturn(expected);

        Page<?> result = service.findPage(-1, 500, LocalDate.of(2026, 10, 22),
                LocalDate.of(2026, 10, 22), null);

        assertSame(expected, result);
        verify(logs).findPageFiltered(
                LocalDateTime.of(2026, 10, 22, 5, 0),
                LocalDateTime.of(2026, 10, 23, 5, 0),
                "%", PageRequest.of(0, AccessLogService.MAX_PAGE_SIZE));
    }

    @Test
    void openDateRangesKeepTheProvidedBoundary() {
        service.findPage(1, 0, LocalDate.of(2026, 10, 22), null, null);
        service.findPage(2, 10, null, LocalDate.of(2026, 10, 22), "ANA");

        verify(logs).findPageFiltered(eq(LocalDateTime.of(2026, 10, 22, 5, 0)), any(),
                eq("%"), eq(PageRequest.of(1, 1)));
        verify(logs).findPageFiltered(any(), eq(LocalDateTime.of(2026, 10, 23, 5, 0)),
                eq("%ana%"), eq(PageRequest.of(2, 10)));
    }

    @Test
    void rejectsFromAfterTo() {
        assertThrows(InvalidDataException.class,
                () -> service.findPage(0, 50, LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 20), null));
        verifyNoInteractions(logs);
    }
}
