package com.example.funerariamc.service.bot;

import com.example.funerariamc.config.TelegramProperties;
import com.example.funerariamc.service.notification.TelegramNotificationSender;
import com.example.funerariamc.utils.enums.BotTopic;
import com.example.funerariamc.utils.enums.NotificationChannel;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramBotPollerTest {
    private final TelegramProperties properties = mock(TelegramProperties.class);
    private final TelegramNotificationSender telegram = mock(TelegramNotificationSender.class);
    private final ChatbotService chatbot = mock(ChatbotService.class);
    private final BotMessageService messages = mock(BotMessageService.class);
    private final TelegramBotPoller poller = new TelegramBotPoller(properties, telegram, chatbot, messages);

    private void response() {
        when(chatbot.handle("555", "1")).thenReturn(new ChatbotService.Answer("1", BotTopic.MENU, 7L,
                List.of(new ChatbotService.Reply("Mi plan", BotTopic.PLAN, null))));
    }

    @Test
    void incomingLogFailureDoesNotPreventTheAnswer() {
        response();
        doThrow(new IllegalStateException("commit")).when(messages).incoming("555", 7L, "1", BotTopic.MENU);
        assertDoesNotThrow(() -> poller.attend(new TelegramNotificationSender.Update(1L, "555", "1")));
        verify(telegram).sendWithOptions("555", "Mi plan", null);
        verify(messages).outgoing("555", 7L, NotificationChannel.TELEGRAM, "Mi plan", BotTopic.PLAN, true);
    }

    @Test
    void failedTelegramDeliveryIsLoggedAsFailed() {
        response();
        doThrow(new IllegalStateException("transport")).when(telegram).sendWithOptions("555", "Mi plan", null);
        poller.attend(new TelegramNotificationSender.Update(1L, "555", "1"));
        verify(messages).outgoing("555", 7L, NotificationChannel.TELEGRAM, "Mi plan", BotTopic.PLAN, false);
    }

    @Test
    void outgoingCommitFailureDoesNotStopMessageProcessing() {
        response();
        doThrow(new IllegalStateException("commit")).when(messages)
                .outgoing("555", 7L, NotificationChannel.TELEGRAM, "Mi plan", BotTopic.PLAN, true);
        assertDoesNotThrow(() -> poller.attend(new TelegramNotificationSender.Update(1L, "555", "1")));
    }

    @Test
    void noTokenIsReportedAsUnconfigured() {
        assertEquals(TelegramBotPoller.State.NOT_CONFIGURED, poller.status().state());
        verifyNoInteractions(telegram, chatbot, messages);
    }
}
