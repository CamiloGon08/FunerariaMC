package com.example.funerariamc.service.bot;

import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.BotMessage;
import com.example.funerariamc.persistence.BotMessageRepository;
import com.example.funerariamc.persistence.ClientRepository;
import com.example.funerariamc.service.SessionContext;
import com.example.funerariamc.utils.enums.BotMessageDirection;
import com.example.funerariamc.utils.enums.BotMessageStatus;
import com.example.funerariamc.utils.enums.BotTopic;
import com.example.funerariamc.utils.enums.NotificationChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotMessageServiceTest {
    private final BotMessageRepository messages = mock(BotMessageRepository.class);
    private final BotMessageService service = new BotMessageService(messages, mock(ClientRepository.class), new SessionContext());

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void incomingIsRecordedWithItsDirectionAndStatus() {
        service.incoming("555", null, "Hola", BotTopic.IDENTIFICATION);
        var stored = ArgumentCaptor.forClass(BotMessage.class);
        verify(messages).save(stored.capture());
        assertEquals(BotMessageDirection.INCOMING, stored.getValue().getDirection());
        assertEquals(BotMessageStatus.RECEIVED, stored.getValue().getStatus());
        assertEquals("Hola", stored.getValue().getMessageText());
        assertNotNull(stored.getValue().getCreatedAt());
    }

    @Test
    void failedDeliveryIsKeptInHistory() {
        service.outgoing("555", null, NotificationChannel.TELEGRAM, "Respuesta", BotTopic.MENU, false);
        var stored = ArgumentCaptor.forClass(BotMessage.class);
        verify(messages).save(stored.capture());
        assertEquals(BotMessageStatus.FAILED, stored.getValue().getStatus());
        assertEquals(BotMessageDirection.OUTGOING, stored.getValue().getDirection());
    }

    @Test
    void listenersRunOnlyAfterCommit() {
        Runnable listener = mock(Runnable.class);
        service.addListener(listener);
        TransactionSynchronizationManager.initSynchronization();
        service.incoming("555", null, "Hola", BotTopic.IDENTIFICATION);
        verifyNoInteractions(listener);
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
        verify(listener).run();
    }

    @Test
    void rollbackDoesNotNotifyTheScreen() {
        Runnable listener = mock(Runnable.class);
        service.addListener(listener);
        TransactionSynchronizationManager.initSynchronization();
        service.incoming("555", null, "Hola", BotTopic.IDENTIFICATION);
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        verifyNoInteractions(listener);
    }

    @Test
    void databaseFailureDoesNotNotifyListeners() {
        Runnable listener = mock(Runnable.class);
        service.addListener(listener);
        when(messages.save(any(BotMessage.class))).thenThrow(new IllegalStateException("database"));
        assertDoesNotThrow(() -> service.incoming("555", null, "Hola", BotTopic.IDENTIFICATION));
        verifyNoInteractions(listener);
    }

    @Test
    void listenerFailureDoesNotStopOtherListeners() {
        Runnable failing = mock(Runnable.class);
        Runnable healthy = mock(Runnable.class);
        doThrow(new IllegalStateException("screen")).when(failing).run();
        service.addListener(failing);
        service.addListener(healthy);
        assertDoesNotThrow(() -> service.incoming("555", null, "Hola", BotTopic.IDENTIFICATION));
        verify(healthy).run();
    }

    @Test
    void messageHistoryRequiresAnEmployeeSession() {
        assertThrows(UnauthorizedActionException.class, service::overview);
        assertThrows(UnauthorizedActionException.class, () -> service.thread(null));
        verifyNoInteractions(messages);
    }
}
