package com.example.funerariamc.service;

import com.example.funerariamc.model.enums.TransactionStatus;
import com.example.funerariamc.model.enums.TransactionType;
import com.example.funerariamc.service.FinancialTransactionService.MovementRow;
import com.example.funerariamc.service.FinancialTransactionService.Origin;
import com.example.funerariamc.service.ReportService.Format;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReportServiceTest {

    private static final FinancePeriod OCTOBER = FinancePeriod.month(YearMonth.of(2026, 10));

    @Test
    void comparesWithThePreviousPeriod() {
        ReportService.Totals totals = new ReportService.Totals(new BigDecimal("1200"), new BigDecimal("500"),
                new BigDecimal("700"), new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("-200"));
        assertEquals(20.0, totals.incomeChange());
        assertNull(totals.expenseChange());
        assertEquals(450.0, totals.balanceChange());
    }

    @Test
    void suggestsAFileNameWithThePeriod() {
        assertEquals("Finanzas_2026-10-01_2026-10-31.pdf", ReportService.suggestedName(OCTOBER, Format.PDF));
        assertEquals("Finanzas_2026-10-01_2026-10-31.xlsx", ReportService.suggestedName(OCTOBER, Format.EXCEL));
    }

    @Test
    void writesAPdfAndAnExcelBook() throws Exception {
        ReportService.Summary summary = summary(List.of(new ReportService.SaleLine("0001",
                LocalDateTime.of(2026, 10, 4, 10, 0), "Particular", "Servicio de Juan Díaz", "Cliente Marta Díaz",
                new BigDecimal("3000000"), new BigDecimal("1000000"), new BigDecimal("2000000"), false)));
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        ReportService.writePdf(summary, pdf);
        assertTrue(new String(pdf.toByteArray(), 0, 5, StandardCharsets.US_ASCII).startsWith("%PDF"));

        ByteArrayOutputStream excel = new ByteArrayOutputStream();
        ReportService.writeExcel(summary, excel);
        try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(excel.toByteArray()))) {
            assertNotNull(book.getSheet("Balance"));
            assertNotNull(book.getSheet("Ventas"));
            assertNotNull(book.getSheet("Movimientos"));
            assertEquals(5, book.getNumberOfSheets());
            // El balance trae la sección de egresos por cuenta y los movimientos su columna de cuenta.
            boolean accounts = false;
            for (var row : book.getSheet("Balance")) {
                if (row.getCell(0) != null && "Egresos por cuenta".equals(row.getCell(0).getStringCellValue())) {
                    accounts = true;
                }
            }
            assertTrue(accounts);
            assertEquals("Cuenta", book.getSheet("Movimientos").getRow(0).getCell(5).getStringCellValue());
            assertEquals("Sin cuenta", book.getSheet("Movimientos").getRow(2).getCell(5).getStringCellValue());
            assertEquals("", book.getSheet("Movimientos").getRow(1).getCell(5).getStringCellValue());
        }
    }

    @Test
    void anEmptyPeriodStillProducesBothFiles() throws Exception {
        ReportService.Summary summary = summary(List.of());
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        ReportService.writePdf(summary, pdf);
        assertTrue(pdf.size() > 0);
        ByteArrayOutputStream excel = new ByteArrayOutputStream();
        ReportService.writeExcel(summary, excel);
        assertTrue(excel.size() > 0);
    }

    private static ReportService.Summary summary(List<ReportService.SaleLine> sales) {
        ReportService.Totals totals = new ReportService.Totals(new BigDecimal("1000000"), new BigDecimal("250000"),
                new BigDecimal("750000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        List<MovementRow> movements = sales.isEmpty() ? List.of() : List.of(
                new MovementRow(1L, "MV-0001", LocalDateTime.of(2026, 10, 4, 10, 5), TransactionType.INCOME,
                        Origin.SALE_PAYMENT, "Abono inicial", "0001", "Laura Pérez", new BigDecimal("1000000"),
                        TransactionStatus.ACTIVE, null),
                new MovementRow(2L, "MV-0002", LocalDateTime.of(2026, 10, 31, 18, 0), TransactionType.EXPENSE,
                        Origin.COMMISSION, "Comisión 2026-10 · Ana Ruiz · 1 venta al 5 %", null, "Laura Pérez",
                        new BigDecimal("250000"), TransactionStatus.ACTIVE, null));
        return new ReportService.Summary(OCTOBER, OCTOBER.label(), "septiembre 2026", totals,
                new BigDecimal("2000000"), sales.size(), true,
                List.of(new ReportService.FlowPoint("4", "4 de octubre", new BigDecimal("1000000"), BigDecimal.ZERO)),
                List.of(new ReportService.Slice("Particular", new BigDecimal("1000000"))),
                List.of(new ReportService.Slice("Sin cuenta", new BigDecimal("250000"))), 2026,
                List.of(new ReportService.MonthBalance("Oct", new BigDecimal("1000000"), new BigDecimal("250000"))),
                List.of(new ReportService.CommissionLine("Ana Ruiz", "1 venta al 5 %", new BigDecimal("250000"))),
                sales, movements, LocalDateTime.of(2026, 11, 8, 10, 0));
    }
}
