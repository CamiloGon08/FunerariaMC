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

    @Test
    void supportsACustomSubjectWhilePreservingThePlainBody() {
        EmailNotificationSender sender = new EmailNotificationSender(mail, "sender@example.test");
        sender.send("recipient@example.test", "Verification code", "Your code is 123456");
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        assertEquals("Verification code", sent.getValue().getSubject());
        assertEquals("Your code is 123456", sent.getValue().getText());
    }

    @Test
    void htmlMailContainsPlainAndHtmlAlternativesAndAnInlineImage() throws Exception {
        var message = new jakarta.mail.internet.MimeMessage((jakarta.mail.Session) null);
        when(mail.createMimeMessage()).thenReturn(message);
        EmailNotificationSender sender = new EmailNotificationSender(mail, "sender@example.test");

        sender.sendHtml("recipient@example.test", "Welcome", "Plain body", "<b>Hello</b><img src='cid:brand'>",
                new org.springframework.core.io.ByteArrayResource(new byte[]{1, 2, 3}), "brand");

        verify(mail).send(message);
        assertEquals("Welcome", message.getSubject());
        assertEquals("recipient@example.test", message.getAllRecipients()[0].toString());
        message.saveChanges();
        var bytes = new java.io.ByteArrayOutputStream();
        message.writeTo(bytes);
        String mime = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(mime.contains("multipart/alternative"), mime);
        assertTrue(mime.contains("Plain body"), mime);
        assertTrue(mime.contains("<b>Hello</b>"), mime);
        assertTrue(mime.contains("Content-ID: <brand>"), mime);
    }

    @Test
    void malformedHtmlRecipientFailsBeforeSending() {
        when(mail.createMimeMessage()).thenReturn(new jakarta.mail.internet.MimeMessage((jakarta.mail.Session) null));
        EmailNotificationSender sender = new EmailNotificationSender(mail, "sender@example.test");
        assertThrows(MailSendException.class,
                () -> sender.sendHtml("not an address", "Subject", "Plain", "<p>Hello</p>", null, null));
        verify(mail, never()).send(any(jakarta.mail.internet.MimeMessage.class));
    }
}
