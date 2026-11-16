package com.example.funerariamc.service.bot;

import com.example.funerariamc.config.TelegramProperties;
import com.example.funerariamc.model.Client;
import com.example.funerariamc.model.FuneralPlan;
import com.example.funerariamc.persistence.FuneralPlanRepository;
import com.example.funerariamc.service.BrandingService;
import com.example.funerariamc.service.PlanParameters;
import com.example.funerariamc.service.PlanRegisteredEvent;
import com.example.funerariamc.service.notification.EmailNotificationSender;
import com.example.funerariamc.utils.enums.BotTopic;
import com.example.funerariamc.utils.enums.NotificationChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlanNotificationServiceTest {
    private final FuneralPlanRepository plans = mock(FuneralPlanRepository.class);
    private final PlanParameters parameters = mock(PlanParameters.class);
    private final EmailNotificationSender email = mock(EmailNotificationSender.class);
    private final BotMessageService messages = mock(BotMessageService.class);
    private final BrandingService branding = mock(BrandingService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private PlanNotificationService service;
    private FuneralPlan plan;

    @BeforeEach
    void setUp() {
        service = new PlanNotificationService(plans, parameters, email, messages,
                new TelegramProperties("", "https://t.me/example_bot", false), transactions, branding);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(branding.fullName()).thenReturn("Funeraria Paz & Jardines");
        when(branding.current()).thenReturn(new BrandingService.BrandView("Paz & Jardines", null, null, null,
                "#1B2A3D", "#3D5A80", "#C9A96E", null, null));
        when(parameters.waitingDays()).thenReturn(62);
        Client holder = new Client();
        holder.setId(7L);
        holder.setName("Ana <script>");
        holder.setEmail("ana@example.test");
        plan = new FuneralPlan();
        plan.setId(12L);
        plan.setClient(holder);
        plan.setPlanName("Normal & Plus");
        plan.setStartDate(LocalDate.of(2026, 11, 1));
        plan.setMonthlyFee(new BigDecimal("60000"));
        when(plans.findDetailById(12L)).thenReturn(Optional.of(plan));
    }

    @AfterEach
    void tearDown() { service.shutdown(); }

    @Test
    void welcomeUsesTheBrandEscapesHtmlAndIncludesTheBotLink() {
        var welcome = service.compose(plan);
        assertTrue(welcome.subject().contains("Funeraria Paz & Jardines"));
        assertTrue(welcome.text().contains("https://t.me/example_bot"));
        assertTrue(welcome.html().contains("Ana &lt;script&gt;"));
        assertTrue(welcome.html().contains("Funeraria Paz &amp; Jardines"));
        assertTrue(welcome.html().contains("Normal &amp; Plus"));
        assertTrue(welcome.html().contains("#1B2A3D"));
        assertFalse(welcome.html().contains("Ana <script>"));
    }

    @Test
    void welcomeRespectsTheRestartedWaitingPeriod() {
        plan.setWaitingRestartDate(LocalDate.of(2026, 11, 10));
        var welcome = service.compose(plan);
        assertTrue(welcome.text().contains("11 de enero de 2027"), welcome.text());
    }

    @Test
    void welcomeWithoutABotLinkOmitsTelegramInstructions() {
        service.shutdown();
        service = new PlanNotificationService(plans, parameters, email, messages,
                new TelegramProperties(""), transactions, branding);
        assertFalse(service.compose(plan).text().contains("Telegram:"));
        assertFalse(service.compose(plan).html().contains("href="));
    }

    @Test
    void sentWelcomeIsRecorded() {
        service.sendWelcome(12L);
        verify(email).sendHtml(eq("ana@example.test"), contains("Funeraria Paz & Jardines"),
                anyString(), anyString(), any(), eq(PlanNotificationService.QR_ID));
        verify(messages).outgoing(isNull(), eq(7L), eq(NotificationChannel.EMAIL),
                anyString(), eq(BotTopic.ENROLLMENT), eq(true));
    }

    @Test
    void mailFailureIsRecordedWithoutClaimingSuccess() {
        doThrow(new IllegalStateException("smtp")).when(email).sendHtml(anyString(), anyString(),
                anyString(), anyString(), any(), anyString());
        service.sendWelcome(12L);
        verify(messages).outgoing(isNull(), eq(7L), eq(NotificationChannel.EMAIL),
                anyString(), eq(BotTopic.ENROLLMENT), eq(false));
    }

    @Test
    void missingEmailRecordsFailureAndSendsNothing() {
        plan.getClient().setEmail(null);
        service.sendWelcome(12L);
        verifyNoInteractions(email);
        verify(messages).outgoing(isNull(), eq(7L), eq(NotificationChannel.EMAIL),
                contains("no tiene correo"), eq(BotTopic.ENROLLMENT), eq(false));
    }

    @Test
    void missingPlanSendsAndRecordsNothing() {
        when(plans.findDetailById(12L)).thenReturn(Optional.empty());
        service.sendWelcome(12L);
        verifyNoInteractions(email, messages);
    }

    @Test
    void eventIsDeliveredOnlyAfterACommitAndNeverAfterRollback() throws Exception {
        CountDownLatch sent = new CountDownLatch(1);
        doAnswer(invocation -> { sent.countDown(); return null; }).when(email).sendHtml(
                anyString(), anyString(), anyString(), anyString(), any(), anyString());
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(TransactionalEventListenerFactory.class);
            context.registerBean(PlanNotificationService.class, () -> service);
            context.refresh();
            TransactionTemplate tx = new TransactionTemplate(new TestTransactions());
            tx.executeWithoutResult(status -> {
                context.publishEvent(new PlanRegisteredEvent(12L));
                verifyNoInteractions(email);
                status.setRollbackOnly();
            });
            verify(plans, never()).findDetailById(anyLong());
            tx.executeWithoutResult(status -> {
                context.publishEvent(new PlanRegisteredEvent(12L));
                verifyNoInteractions(email);
            });
            assertTrue(sent.await(5, TimeUnit.SECONDS));
        }
    }

    private static class TestTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
