package com.example.funerariamc.service.notification;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmailNotificationSenderTest {

    private final JavaMailSender mail = mock(JavaMailSender.class);

    @Test
    void sendsTheRecipientMessageAndConfiguredSender() {
        EmailNotificationSender sender = new EmailNotificationSender(mail, "sender@example.test");

        sender.send("recipient@example.test", "Mensaje de prueba");

        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(message.capture());
        assertAll(
                () -> assertArrayEquals(new String[]{"recipient@example.test"}, message.getValue().getTo()),
                () -> assertEquals("sender@example.test", message.getValue().getFrom()),
                () -> assertEquals("Funeraria MC", message.getValue().getSubject()),
                () -> assertEquals("Mensaje de prueba", message.getValue().getText())
        );
    }

    @Test
    void transportFailureIsPropagatedForFallback() {
        EmailNotificationSender sender = new EmailNotificationSender(mail, "sender@example.test");
        doThrow(new MailSendException("Transport unavailable")).when(mail).send(any(SimpleMailMessage.class));

        assertThrows(MailSendException.class, () -> sender.send("recipient@example.test", "Mensaje"));
    }

    @Test
    void emptySenderLetsTheMailTransportChooseItsDefault() {
        EmailNotificationSender sender = new EmailNotificationSender(mail, "");

        sender.send("recipient@example.test", "Mensaje");

        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(message.capture());
        assertNull(message.getValue().getFrom());
    }
}
