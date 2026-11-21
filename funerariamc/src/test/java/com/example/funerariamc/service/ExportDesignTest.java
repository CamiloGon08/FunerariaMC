package com.example.funerariamc.service;

import com.example.funerariamc.service.BrandingService.BrandView;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportDesignTest {

    private static final BrandView PAZ = new BrandView("Jardines de Paz", "Siempre con usted", null, null,
            "#2B3A55", "#4A6FA5", "#C9A227", null, null);

    @Test
    void pdfCarriesTheBrandNameKpisAndTables() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportDesign.PdfReport pdf = ExportDesign.pdf(PAZ, PAZ.fullName(), out, "Resumen financiero");
        pdf.hero("Resumen financiero", "Período: noviembre 2026",
                List.of(new ExportDesign.Kpi("INGRESOS", "$ 1.000", "+10 % vs. octubre 2026", ExportDesign.Tone.UP)), true);
        pdf.table("Balance del período", null, new float[]{2, 1}, "Concepto", "Valor")
                .row("Ingresos", "$ 1.000").total("Saldo", "$ 1.000").end();
        pdf.close();
        try (PdfReader reader = new PdfReader(out.toByteArray())) {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertTrue(text.contains("Jardines de Paz"), text);
            assertTrue(text.contains("INGRESOS"), text);
            assertTrue(text.contains("Balance del período"), text);
        }
    }

    @Test
    void excelBannerAndHeaderUseThePrimaryColor() throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            ExportDesign.XlsxReport x = ExportDesign.xlsx(PAZ, PAZ.fullName(), book, "Resumen financiero");
            var sheet = book.createSheet("Balance");
            int row = x.banner(sheet, "Resumen financiero", "Período: noviembre 2026", 3);
            ExportDesign.XlsxTable table = x.table(sheet, row, 0, "Concepto", "Valor", "%");
            table.row().text("Ingresos").money(java.math.BigDecimal.TEN).percent(0.5);
            table.end(true);
            byte[] primary = {(byte) 0x2B, (byte) 0x3A, (byte) 0x55};
            XSSFCellStyle band = (XSSFCellStyle) sheet.getRow(0).getCell(0).getCellStyle();
            assertArrayEquals(primary, band.getFillForegroundXSSFColor().getRGB());
            XSSFCellStyle head = (XSSFCellStyle) sheet.getRow(row).getCell(0).getCellStyle();
            assertArrayEquals(primary, head.getFillForegroundXSSFColor().getRGB());
            assertEquals("Jardines de Paz", sheet.getRow(2).getCell(0).getStringCellValue());
            assertEquals("CONCEPTO", sheet.getRow(row).getCell(0).getStringCellValue());
        }
    }
}
