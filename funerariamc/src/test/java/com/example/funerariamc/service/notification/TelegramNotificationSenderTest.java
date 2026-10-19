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
}
