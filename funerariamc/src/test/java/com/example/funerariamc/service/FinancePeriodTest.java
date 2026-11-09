package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.*;

class FinancePeriodTest {

    @Test
    void buildsWeekMonthQuarterAndYear() {
        FinancePeriod week = FinancePeriod.week(LocalDate.of(2026, 11, 8));
        assertEquals(LocalDate.of(2026, 11, 2), week.from());
        assertEquals(LocalDate.of(2026, 11, 8), week.to());
        assertEquals(7, week.days());

        FinancePeriod february = FinancePeriod.month(YearMonth.of(2028, 2));
        assertEquals(LocalDate.of(2028, 2, 29), february.to());

        FinancePeriod quarter = FinancePeriod.quarter(LocalDate.of(2026, 11, 8));
        assertEquals(LocalDate.of(2026, 10, 1), quarter.from());
        assertEquals(LocalDate.of(2026, 12, 31), quarter.to());

        FinancePeriod year = FinancePeriod.year(2026);
        assertEquals(365, year.days());
        assertFalse(year.byDay());
        assertTrue(quarter.contains(LocalDate.of(2026, 12, 31)));
        assertFalse(quarter.contains(LocalDate.of(2027, 1, 1)));
    }

    @Test
    void previousKeepsWholeMonthsOrTheSameNumberOfDays() {
        assertEquals(FinancePeriod.month(YearMonth.of(2026, 9)), FinancePeriod.month(YearMonth.of(2026, 10)).previous());
        assertEquals(FinancePeriod.month(YearMonth.of(2026, 2)), FinancePeriod.month(YearMonth.of(2026, 3)).previous());
        assertEquals(new FinancePeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30)),
                FinancePeriod.quarter(LocalDate.of(2026, 10, 5)).previous());
        assertEquals(new FinancePeriod(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1)),
                FinancePeriod.week(LocalDate.of(2026, 11, 4)).previous());
    }

    @Test
    void databaseLimitsAreColombianMidnightInUtc() {
        FinancePeriod october = FinancePeriod.month(YearMonth.of(2026, 10));
        assertEquals(LocalDateTime.of(2026, 10, 1, 5, 0), october.dbFrom());
        assertEquals(LocalDateTime.of(2026, 11, 1, 5, 0), october.dbTo());
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0), october.appFrom());
        assertEquals(LocalDateTime.of(2026, 11, 1, 0, 0), october.appTo());
    }

    @Test
    void labelsAreInSpanish() {
        assertEquals("octubre 2026", FinancePeriod.month(YearMonth.of(2026, 10)).label());
        assertEquals("2026", FinancePeriod.year(2026).label());
        assertEquals("2 nov – 8 nov 2026", FinancePeriod.week(LocalDate.of(2026, 11, 8)).label());
    }

    @Test
    void rejectsMissingOrReversedDates() {
        assertThrows(InvalidDataException.class, () -> new FinancePeriod(null, LocalDate.now()));
        assertThrows(InvalidDataException.class,
                () -> new FinancePeriod(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1)));
    }
}
