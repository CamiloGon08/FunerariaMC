package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * Período de Finanzas en días de Colombia: desde y hasta (ambos incluidos).
 * Las fechas de la BD están en UTC, por eso las consultas usan dbFrom y dbTo.
 */
public record FinancePeriod(LocalDate from, LocalDate to) {

    private static final Locale ES = Locale.forLanguageTag("es-CO");
    /** Más de dos meses de días no caben en la gráfica por día: se agrupa por mes. */
    public static final int MAX_DAYS_BY_DAY = 62;

    public FinancePeriod {
        if (from == null || to == null) {
            throw new InvalidDataException("Elija la fecha inicial y la final.");
        }
        if (from.isAfter(to)) {
            throw new InvalidDataException("La fecha inicial no puede ser posterior a la fecha final.");
        }
    }

    public static FinancePeriod week(LocalDate day) {
        LocalDate monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new FinancePeriod(monday, monday.plusDays(6));
    }

    public static FinancePeriod month(YearMonth month) {
        return new FinancePeriod(month.atDay(1), month.atEndOfMonth());
    }

    public static FinancePeriod quarter(LocalDate day) {
        int firstMonth = (day.getMonthValue() - 1) / 3 * 3 + 1;
        LocalDate start = LocalDate.of(day.getYear(), firstMonth, 1);
        return new FinancePeriod(start, start.plusMonths(3).minusDays(1));
    }

    public static FinancePeriod year(int year) {
        return new FinancePeriod(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }

    public long days() {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }

    /** Período inmediatamente anterior y de la misma cantidad de días, para comparar. */
    public FinancePeriod previous() {
        if (from.getDayOfMonth() == 1 && to.equals(YearMonth.from(to).atEndOfMonth())) {
            long months = ChronoUnit.MONTHS.between(YearMonth.from(from), YearMonth.from(to)) + 1;
            YearMonth start = YearMonth.from(from).minusMonths(months);
            return new FinancePeriod(start.atDay(1), YearMonth.from(from).minusMonths(1).atEndOfMonth());
        }
        return new FinancePeriod(from.minusDays(days()), from.minusDays(1));
    }

    public boolean byDay() {
        return days() <= MAX_DAYS_BY_DAY;
    }

    public boolean contains(LocalDate day) {
        return !day.isBefore(from) && !day.isAfter(to);
    }

    /** Medianoche del primer día en Colombia, en la hora de la BD. */
    public LocalDateTime dbFrom() {
        return toDb(from.atStartOfDay());
    }

    /** Medianoche del día siguiente al último, en la hora de la BD (límite excluido). */
    public LocalDateTime dbTo() {
        return toDb(to.plusDays(1).atStartOfDay());
    }

    /** Límites en hora de Colombia, para columnas que la app llena con su reloj. */
    public LocalDateTime appFrom() {
        return from.atStartOfDay();
    }

    public LocalDateTime appTo() {
        return to.plusDays(1).atStartOfDay();
    }

    /** "octubre 2026", "11 – 24 oct 2026", "2026"... */
    public String label() {
        if (from.getDayOfYear() == 1 && to.equals(LocalDate.of(from.getYear(), 12, 31))) {
            return String.valueOf(from.getYear());
        }
        if (from.getDayOfMonth() == 1 && to.equals(YearMonth.from(from).atEndOfMonth())) {
            return monthName(YearMonth.from(from)) + " " + from.getYear();
        }
        DateTimeFormatter day = DateTimeFormatter.ofPattern("d MMM", ES);
        DateTimeFormatter dayYear = DateTimeFormatter.ofPattern("d MMM yyyy", ES);
        return clean(from.format(from.getYear() == to.getYear() ? day : dayYear)) + " – " + clean(to.format(dayYear));
    }

    public static String monthName(YearMonth month) {
        return month.getMonth().getDisplayName(TextStyle.FULL, ES).toLowerCase(Locale.ROOT);
    }

    private static String clean(String text) {
        return text.replace(".", "");
    }

    private static LocalDateTime toDb(LocalDateTime appTime) {
        return appTime.atZone(AccessLogService.APP_ZONE).withZoneSameInstant(AccessLogService.DB_ZONE).toLocalDateTime();
    }
}
