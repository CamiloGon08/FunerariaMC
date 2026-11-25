package com.example.funerariamc.service;

import com.example.funerariamc.service.BrandingService.BrandView;
import com.example.funerariamc.service.ContractService.ContractInfo;
import com.example.funerariamc.service.ContractService.Line;
import com.example.funerariamc.service.ContractService.Payment;
import com.example.funerariamc.service.ContractService.PaymentState;
import com.example.funerariamc.service.ContractService.SaleKind;
import com.example.funerariamc.service.ContractService.SaleRow;
import com.example.funerariamc.service.ContractService.SaleView;
import com.example.funerariamc.service.SaleContractService.SaleContract;
import com.example.funerariamc.utils.enums.ContractStatus;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaleContractServiceTest {

    private static final BrandView EDEN = BrandingService.defaults();
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

    private static SaleView service() {
        SaleRow row = new SaleRow(148L, "0148", SaleKind.PRIVATE, PaymentState.WITH_BALANCE,
                LocalDateTime.of(2026, 11, 24, 10, 0), new BigDecimal("4500000"), new BigDecimal("1500000"),
                new BigDecimal("3000000"), 31L, "CT-2026-0031", "Rafael Montero", "Ana Montero", "");
        ContractInfo contract = new ContractInfo(31L, "CT-2026-0031", TODAY, ContractStatus.ACTIVE, "Rafael Montero",
                null, "Sala 2", LocalDateTime.of(2026, 11, 24, 15, 0), null, null, null, null);
        return new SaleView(row, "Ana Montero", "CC 1065123456", "3001234567", "Leonel Garrido", contract,
                List.of(new Line("Servicio funerario completo", 1, new BigDecimal("4500000"), new BigDecimal("4500000"), null)),
                List.of(new Payment(1L, LocalDateTime.of(2026, 11, 24, 10, 5), new BigDecimal("1500000"), "Abono inicial", "Leonel", false),
                        new Payment(2L, LocalDateTime.of(2026, 11, 24, 11, 0), new BigDecimal("200000"), "Abono errado", "Leonel", true)),
                false);
    }

    private static SaleView products() {
        SaleRow row = new SaleRow(149L, "0149", SaleKind.PRODUCT, PaymentState.PAID, LocalDateTime.of(2026, 11, 24, 9, 0),
                new BigDecimal("80000"), new BigDecimal("80000"), BigDecimal.ZERO, null, null, "Arreglo floral", "Luz Daza", "");
        return new SaleView(row, "Luz Daza", "CC 49777111", null, null, null,
                List.of(new Line("Arreglo floral", 2, new BigDecimal("40000"), new BigDecimal("80000"), 5L)),
                List.of(new Payment(3L, LocalDateTime.of(2026, 11, 24, 9, 0), new BigDecimal("80000"), "Pago de contado", "Mariana", false)),
                false);
    }

    @Test
    void serviceContractUsesTheContractNumberAndSkipsVoidedPayments() {
        SaleContract k = SaleContractService.of(service(), TODAY);
        assertEquals("CT-2026-0031", k.code());
        assertEquals("Contrato de servicio funerario particular", k.title());
        assertEquals(1, k.payments().size());
        assertTrue(k.isService());
        assertEquals("Contrato_CT-2026-0031.pdf", SaleContractService.suggestedName(k));
    }

    @Test
    void productSaleIsASaleContractWithoutService() {
        SaleContract k = SaleContractService.of(products(), TODAY);
        assertEquals("Venta 0149", k.code());
        assertEquals("Contrato de venta", k.title());
        assertFalse(k.isService());
        assertEquals("Contrato_Venta_0149.pdf", SaleContractService.suggestedName(k));
        ContractDocument sheet = SaleContractService.document(k);
        assertEquals("0149", sheet.number());
        assertFalse(sheet.sections().stream().anyMatch(s -> s.title().equals("SERVICIO")));
    }

    @Test
    void pdfCarriesClientDeceasedConceptsAndBalance() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SaleContractService.writePdf(SaleContractService.of(service(), TODAY), out, EDEN, EDEN.fullName());
        try (PdfReader reader = new PdfReader(out.toByteArray())) {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertTrue(text.contains("CONTRATO DE SERVICIO FUNERARIO PARTICULAR"), text);
            assertTrue(text.contains("CT-2026-0031"), text);
            assertTrue(text.contains("Ana Montero"), text);
            assertTrue(text.contains("Rafael Montero"), text);
            assertTrue(text.contains("Servicio funerario completo"), text);
            assertFalse(text.contains("Abono errado"), text);
        }
    }

    /** Servicio con varios conceptos, abonos, sala, tanatopraxia y funeraria aliada. */
    private static SaleView longService() {
        SaleRow row = new SaleRow(150L, "0150", SaleKind.PRIVATE, PaymentState.WITH_BALANCE,
                LocalDateTime.of(2026, 11, 24, 10, 0), new BigDecimal("9800000"), new BigDecimal("5000000"),
                new BigDecimal("4800000"), 32L, "CT-2026-0032", "Rafael Antonio Montero Daza", "Ana Lucía Montero Daza", "");
        ContractInfo contract = new ContractInfo(32L, "CT-2026-0032", TODAY, ContractStatus.ACTIVE,
                "Rafael Antonio Montero Daza", null, "Sala 2", LocalDateTime.of(2026, 11, 24, 15, 0),
                LocalDateTime.of(2026, 11, 25, 15, 0), "Jesús Domínguez", LocalDateTime.of(2026, 11, 24, 13, 0),
                "Exequiales Los Ángeles");
        List<Line> lines = List.of(
                new Line("Servicio funerario completo", 1, new BigDecimal("4500000"), new BigDecimal("4500000"), null),
                new Line("Cofre de lujo en madera", 1, new BigDecimal("2500000"), new BigDecimal("2500000"), 1L),
                new Line("Cremación del cuerpo", 1, new BigDecimal("1800000"), new BigDecimal("1800000"), null),
                new Line("Arreglo floral grande", 4, new BigDecimal("150000"), new BigDecimal("600000"), 5L),
                new Line("Libro de condolencias", 1, new BigDecimal("100000"), new BigDecimal("100000"), 6L),
                new Line("Bus adicional para el acompañamiento", 1, new BigDecimal("300000"), new BigDecimal("300000"), null));
        List<Payment> payments = List.of(
                new Payment(4L, LocalDateTime.of(2026, 11, 24, 10, 5), new BigDecimal("2000000"), "Abono inicial", "Leonel", false),
                new Payment(5L, LocalDateTime.of(2026, 11, 24, 12, 0), new BigDecimal("1500000"), "Segundo abono", "Leonel", false),
                new Payment(6L, LocalDateTime.of(2026, 11, 24, 16, 0), new BigDecimal("1500000"), "Tercer abono", "Mariana", false));
        return new SaleView(row, "Ana Lucía Montero Daza", "CC 1065123456", "3001234567", "Leonel Garrido", contract,
                lines, payments, false);
    }

    @Test
    void longServiceContractFitsInOnePage() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SaleContractService.writePdf(SaleContractService.of(longService(), TODAY), out, EDEN, EDEN.fullName());
        try (PdfReader reader = new PdfReader(out.toByteArray())) {
            assertEquals(1, reader.getNumberOfPages());
        }
    }

    @Test
    void titleFollowsTheKindOfSale() {
        assertEquals("Contrato de servicio funerario con plan exequial", SaleContractService.title(SaleKind.PLAN, true));
        assertEquals("Contrato de servicio funerario por pensión", SaleContractService.title(SaleKind.PENSION, true));
        assertEquals("Contrato de venta", SaleContractService.title(SaleKind.PRODUCT, false));
    }
}
