package com.example.funerariamc.service;

import com.example.funerariamc.service.BrandingService.BrandView;
import com.example.funerariamc.service.PlanContractService.Person;
import com.example.funerariamc.service.PlanContractService.PlanContract;
import com.example.funerariamc.utils.enums.PlanStatus;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanContractServiceTest {

    private static final BrandView EDEN = BrandingService.defaults();

    private static PlanContract sample() {
        return new PlanContract(12L, "PLAN-0012", LocalDate.of(2026, 11, 24), "Ana Pérez Gómez", "CC 1065123456",
                "3001234567", "ana@gmail.com", false, "Premium", new BigDecimal("35000"), LocalDate.of(2026, 11, 24),
                LocalDate.of(2027, 2, 22), 90, PlanStatus.ACTIVE,
                List.of(new Person("Ana Pérez Gómez", "1065123456", "Titular", null, null, false),
                        new Person("Luis Pérez", "1065999888", "Hijo(a)", LocalDate.of(2010, 5, 3),
                                new BigDecimal("50000"), false)),
                new BigDecimal("50000"), List.of("Carroza y un bus para el acompañamiento."),
                new PlanContractService.PlanTerms(true, new BigDecimal("3500000"), null, 7, new BigDecimal("150000"),
                        new BigDecimal("200000"), 3, List.of(30, 60, 90)));
    }

    @Test
    void pdfCarriesTheContractTitleHolderPeopleAndIncludes() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PlanContractService.writePdf(sample(), out, EDEN, EDEN.fullName());
        try (PdfReader reader = new PdfReader(out.toByteArray())) {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertTrue(text.contains("CONTRATO DE AFILIACIÓN AL PLAN PREMIUM"), text);
            assertTrue(text.contains("PLAN-0012"), text);
            assertTrue(text.contains("Ana Pérez Gómez"), text);
            assertTrue(text.contains("Luis Pérez"), text);
            assertTrue(text.contains("carroza fúnebre y un bus"), text);
        }
    }

    /** El caso más largo: titular con 7 beneficiarios, recargos y todo lo que incluye el plan premium. */
    private static PlanContract fullPlan() {
        List<Person> people = new java.util.ArrayList<>();
        people.add(new Person("María Alejandra Quintero Pacheco", "1065888001", "Titular", LocalDate.of(1960, 3, 2), null, false));
        for (int i = 1; i <= 7; i++) {
            people.add(new Person("Beneficiario Número " + i + " Quintero Pacheco", "10658880" + (10 + i), "Hijo(a)",
                    LocalDate.of(1950 + i, 1, 1), i % 2 == 0 ? new BigDecimal("200000") : new BigDecimal("150000"), false));
        }
        return new PlanContract(18L, "PLAN-0018", LocalDate.of(2026, 11, 24), "María Alejandra Quintero Pacheco",
                "CC 1065888001", "3001234567", "maria.quintero.pacheco@gmail.com", false, "Plan Premium",
                new BigDecimal("55000"), LocalDate.of(2026, 11, 24), LocalDate.of(2027, 1, 25), 62, PlanStatus.ACTIVE,
                people, new BigDecimal("1200000"),
                List.of("Cofre de lujo en madera.", "Sala de velación por 24 horas.",
                        "Carroza y un bus para el acompañamiento.", "Preparación y tanatopraxia del cuerpo.",
                        "Trámites ante la notaría y el cementerio.", "Arreglo floral y libro de condolencias."),
                new PlanContractService.PlanTerms(true, new BigDecimal("3500000"), new BigDecimal("300000"), 7,
                        new BigDecimal("150000"), new BigDecimal("200000"), 3, List.of(30, 60, 90)));
    }

    @Test
    void fullPlanContractFitsInOnePage() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PlanContractService.writePdf(fullPlan(), out, EDEN, EDEN.fullName());
        try (PdfReader reader = new PdfReader(out.toByteArray())) {
            assertEquals(1, reader.getNumberOfPages());
        }
    }

    @Test
    void planTitleDoesNotRepeatTheWordPlan() {
        assertEquals("Plan Premium", PlanContractService.planTitle("Plan Premium"));
        assertEquals("plan Normal", PlanContractService.planTitle("Normal"));
    }

    @Test
    void suggestedNameUsesThePlanCode() {
        assertEquals("Contrato_PLAN-0012.pdf", PlanContractService.suggestedName(sample()));
    }

    @Test
    void statusLabelIsInSpanish() {
        assertEquals("Suspendido", PlanContractService.statusLabel(PlanStatus.SUSPENDED));
        assertEquals("Activo", PlanContractService.statusLabel(null));
    }
}
