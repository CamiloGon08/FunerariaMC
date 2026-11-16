package com.example.funerariamc.service.notification;

import com.example.funerariamc.config.TelegramProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TelegramNotificationSenderTest {

    private MockRestServiceServer server;
    private TelegramNotificationSender sender;

    @BeforeEach
    void setUp() {
        RestClient.Builder client = RestClient.builder();
        server = MockRestServiceServer.bindTo(client).build();
        sender = new TelegramNotificationSender(new TelegramProperties("test-token"), client.build());
    }

    @Test
    void sendsTheChatAndTextToTheConfiguredBot() {
        server.expect(requestTo("https://api.telegram.org/bottest-token/sendMessage"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"chat_id\":\"123\",\"text\":\"Mensaje\"}"))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        sender.send("123", "Mensaje");

        server.verify();
    }

    @Test
    void missingTokenFailsBeforeAnyHttpRequest() {
        sender = new TelegramNotificationSender(new TelegramProperties(""), RestClient.create());

        assertFalse(sender.isConfigured());
        assertThrows(IllegalStateException.class, () -> sender.send("123", "Mensaje"));
        server.verify();
    }

    @Test
    void providerRejectionIsNotReportedAsSuccessfulDelivery() {
        server.expect(requestTo("https://api.telegram.org/bottest-token/sendMessage"))
                .andRespond(withSuccess("{\"ok\":false}", MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class, () -> sender.send("123", "Mensaje"));
        server.verify();
    }

    @Test
    void httpFailureIsPropagatedForFallback() {
        server.expect(requestTo("https://api.telegram.org/bottest-token/sendMessage"))
                .andRespond(withServerError());

        assertThrows(RestClientException.class, () -> sender.send("123", "Mensaje"));
        server.verify();
    }

    @Test
    void sendsReplyKeyboardButtons() {
        server.expect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.reply_markup.keyboard[0][0].text").value("Mi plan"))
                .andExpect(jsonPath("$.reply_markup.resize_keyboard").value(true))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        sender.sendWithOptions("555", "Choose", java.util.List.of(java.util.List.of("Mi plan")));
        server.verify();
    }

    @Test
    void emptyOptionsRemoveTheKeyboard() {
        server.expect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.reply_markup.remove_keyboard").value(true))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        sender.sendWithOptions("555", "Goodbye", java.util.List.of());
        server.verify();
    }

    @Test
    void noOptionsLeaveTheKeyboardUntouched() {
        server.expect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.reply_markup").doesNotExist())
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        sender.sendWithOptions("555", "Message", null);
        server.verify();
    }

    @Test
    void telegramApplicationRejectionIsReported() {
        server.expect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"ok\":false}", MediaType.APPLICATION_JSON));
        assertThrows(IllegalStateException.class, () -> sender.sendWithOptions("555", "Message", null));
        server.verify();
    }

    @Test
    void parsesATextUpdateWithALongChatIdentifier() {
        var update = TelegramNotificationSender.parse(java.util.Map.of("update_id", 91L, "message",
                java.util.Map.of("chat", java.util.Map.of("id", 4_000_000_001L), "text", "Hola")));
        assertEquals(91L, update.updateId());
        assertEquals("4000000001", update.chatId());
        assertEquals("Hola", update.text());
    }

    @Test
    void nonTextMessagesKeepTheChatWithoutInventingText() {
        var update = TelegramNotificationSender.parse(java.util.Map.of("update_id", 92L, "message",
                java.util.Map.of("chat", java.util.Map.of("id", 555L), "sticker", java.util.Map.of())));
        assertEquals("555", update.chatId());
        assertEquals("", update.text());
    }
}
