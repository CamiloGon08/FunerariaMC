package com.example.funerariamc.service.bot;

import com.example.funerariamc.model.Client;
import com.example.funerariamc.persistence.ClientRepository;
import com.example.funerariamc.service.FuneralPlanService;
import com.example.funerariamc.service.FuneralPlanService.BeneficiaryInfo;
import com.example.funerariamc.service.FuneralPlanService.PaymentInfo;
import com.example.funerariamc.service.FuneralPlanService.PlanCard;
import com.example.funerariamc.service.FuneralPlanService.PlanDetail;
import com.example.funerariamc.service.SystemParameterService;
import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.utils.enums.BotTopic;
import com.example.funerariamc.utils.enums.PlanPaymentStatus;
import com.example.funerariamc.utils.enums.PlanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ChatbotServiceTest {

    private static final String CHAT = "555";
    private final ClientRepository clients = mock(ClientRepository.class);
    private final FuneralPlanService plans = mock(FuneralPlanService.class);
    private final BotVerificationService verification = mock(BotVerificationService.class);
    private final SystemParameterService parameters = mock(SystemParameterService.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-15T15:00:00Z"));
    private ChatbotService bot;
    private Client maria;

    @BeforeEach
    void setUp() {
        bot = new ChatbotService(clients, plans, verification, parameters, clock);
        maria = new Client();
        maria.setId(7L);
        maria.setName("María");
        maria.setLastname("Rojas");
        maria.setDocumentNumber("1065123456");
        maria.setEmail("mariarojas@gmail.com");
        when(clients.findByDocumentNumber("1065123456")).thenReturn(Optional.of(maria));
        when(clients.findById(7L)).thenReturn(Optional.of(maria));
        when(plans.holderPlan(7L)).thenReturn(Optional.of(detail()));
        when(verification.expirationMinutes()).thenReturn(10);
        when(parameters.getString(anyString())).thenThrow(new InvalidDataException("falta"));
    }

    @Test
    void greetsAndAsksForTheDocument() {
        ChatbotService.Answer answer = bot.handle(CHAT, "Hola");
        assertEquals(BotTopic.IDENTIFICATION, answer.replies().get(0).topic());
        assertTrue(answer.replies().get(0).text().contains("cédula"));
        verifyNoInteractions(clients);
    }

    @Test
    void unknownDocumentGetsTheSameAnswerAsAHolderWithoutPlan() {
        when(clients.findByDocumentNumber("1000000001")).thenReturn(Optional.empty());
        ChatbotService.Answer answer = bot.handle(CHAT, "1000000001");
        assertTrue(answer.replies().get(0).text().contains("no aparece como titular"));
        verify(verification, never()).sendCode(anyString(), any());
    }

    @Test
    void documentSendsTheCodeToTheMaskedEmail() {
        ChatbotService.Answer answer = bot.handle(CHAT, "1.065.123.456");
        verify(verification).sendCode(CHAT, maria);
        String text = answer.replies().get(0).text();
        assertTrue(text.contains("m***s@gmail.com"), text);
        assertEquals(ChatbotService.CODE_OPTIONS, answer.replies().get(0).options());
        assertEquals(7L, answer.clientId());
    }

    @Test
    void validCodeOpensTheMenuAndTheCodeIsNeverLogged() {
        bot.handle(CHAT, "1065123456");
        when(verification.verify(CHAT, "482913"))
                .thenReturn(new BotVerificationService.Check(BotVerificationService.Outcome.VALID, 7L, 0));
        ChatbotService.Answer answer = bot.handle(CHAT, "482913");
        assertFalse(answer.loggedText().contains("482913"));
        assertEquals(BotTopic.MENU, answer.replies().get(0).topic());
        assertEquals(ChatbotService.MENU_OPTIONS, answer.replies().get(0).options());
    }

    @Test
    void wrongCodeTellsTheAttemptsLeft() {
        bot.handle(CHAT, "1065123456");
        when(verification.verify(CHAT, "111111"))
                .thenReturn(new BotVerificationService.Check(BotVerificationService.Outcome.WRONG, null, 4));
        assertTrue(bot.handle(CHAT, "111111").replies().get(0).text().contains("4 intento"));
    }

    @Test
    void menuOptionsAnswerWithThePlanData() {
        openMenu();
        assertTrue(bot.handle(CHAT, "1 · Mi plan").replies().get(0).text().contains("Plan Normal (PL-0012)"));
        String beneficiaries = bot.handle(CHAT, "2").replies().get(0).text();
        assertTrue(beneficiaries.contains("Ana Rojas · Hija"), beneficiaries);
        assertTrue(beneficiaries.contains("periodo de espera hasta el 3 de febrero de 2027"), beneficiaries);
        String next = bot.handle(CHAT, "3").replies().get(0).text();
        assertTrue(next.contains("Próxima cuota: diciembre de 2026 ($ 35.000)"), next);
        String payments = bot.handle(CHAT, "4").replies().get(0).text();
        assertTrue(payments.contains("noviembre de 2026: $ 35.000"), payments);
    }

    @Test
    void contactNeverInventsTheOfficeData() {
        openMenu();
        String text = bot.handle(CHAT, "5").replies().get(0).text();
        assertTrue(text.contains("acérquese a la sede"), text);
        assertFalse(text.contains("Teléfono:"));
    }

    @Test
    void exitClosesTheSessionAndTheNextMessageAsksForTheDocumentAgain() {
        openMenu();
        assertEquals(BotTopic.EXIT, bot.handle(CHAT, "0").replies().get(0).topic());
        assertEquals(0, bot.openSessions());
        assertEquals(BotTopic.IDENTIFICATION, bot.handle(CHAT, "1").replies().get(0).topic());
    }

    @Test
    void idleSessionExpiresAfterTheCodeMinutes() {
        openMenu();
        clock.advanceMinutes(11);
        assertEquals(BotTopic.IDENTIFICATION, bot.handle(CHAT, "Hola").replies().get(0).topic());
    }

    @Test
    void moneyUsesColombianFormat() {
        assertEquals("$ 1.250.000", ChatbotService.money(new BigDecimal("1250000.00")));
    }


    @Test
    void verificationFailureNeverLogsTheTypedCode() {
        bot.handle(CHAT, "1065123456");
        when(verification.verify(CHAT, "482913")).thenThrow(new IllegalStateException("Database unavailable"));
        var answer = bot.handle(CHAT, "482913");
        assertFalse(answer.loggedText().contains("482913"));
        assertFalse(answer.replies().getFirst().text().contains("482913"));
        assertEquals(0, bot.openSessions());
    }

    @Test
    void sessionExpiresAtTheExactIdleBoundary() {
        openMenu();
        clock.advanceMinutes(10);
        assertEquals(BotTopic.IDENTIFICATION, bot.handle(CHAT, "1").replies().getFirst().topic());
    }

    private void openMenu() {
        bot.handle(CHAT, "1065123456");
        when(verification.verify(eq(CHAT), anyString()))
                .thenReturn(new BotVerificationService.Check(BotVerificationService.Outcome.VALID, 7L, 0));
        bot.handle(CHAT, "482913");
    }

    private static PlanDetail detail() {
        PlanCard card = new PlanCard(12L, "PL-0012", 7L, "María Rojas", "1065123456", false, "Normal",
                new BigDecimal("35000"), LocalDate.of(2026, 11, 3), PlanStatus.ACTIVE, 1, List.of("AR"),
                LocalDate.of(2027, 2, 3), 80, 62, 0, BigDecimal.ZERO, null, 0);
        BeneficiaryInfo ana = new BeneficiaryInfo(1L, "Ana Rojas", "1", null, "Hija", BigDecimal.ZERO, null, null, null);
        PaymentInfo paid = new PaymentInfo(3L, YearMonth.of(2026, 11), new BigDecimal("35000"),
                LocalDateTime.of(2026, 11, 3, 9, 0), PlanPaymentStatus.PAID, "Secretaria");
        return new PlanDetail(card, null, null, 7, List.of(ana), BigDecimal.ZERO, List.of(), List.of(paid),
                YearMonth.of(2026, 12), List.of(), false);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceMinutes(long minutes) {
            now = now.plusSeconds(minutes * 60);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("America/Bogota");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
