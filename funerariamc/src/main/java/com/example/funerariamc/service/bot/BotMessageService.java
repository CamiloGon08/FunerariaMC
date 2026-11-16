package com.example.funerariamc.service.bot;

import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.BotMessage;
import com.example.funerariamc.model.Client;
import com.example.funerariamc.persistence.BotMessageRepository;
import com.example.funerariamc.persistence.BotMessageRepository.ConversationRow;
import com.example.funerariamc.persistence.ClientRepository;
import com.example.funerariamc.service.AccessLogService;
import com.example.funerariamc.service.AppModule;
import com.example.funerariamc.service.ClientService;
import com.example.funerariamc.service.SessionContext;
import com.example.funerariamc.utils.enums.BotMessageDirection;
import com.example.funerariamc.utils.enums.BotMessageStatus;
import com.example.funerariamc.utils.enums.BotTopic;
import com.example.funerariamc.utils.enums.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registro de los mensajes del bot y de los correos de bienvenida (tabla bot_message), y lo que
 * muestra la pantalla "Mensajes del bot". Cada vez que se guarda un mensaje avisa a la pantalla
 * abierta para que se actualice sola.
 */
@Service
public class BotMessageService {

    /** Tope de conversaciones en la lista y de mensajes por conversación: la pantalla no se pone lenta. */
    public static final int MAX_CONVERSATIONS = 200;
    public static final int MAX_MESSAGES = 300;
    private static final Logger log = LoggerFactory.getLogger(BotMessageService.class);
    private static final LocalDateTime SINCE_ALWAYS = LocalDateTime.of(2000, 1, 1, 0, 0);
    private static final int MAX_TEXT = 2000;

    public record Kpis(long messagesToday, long chatsToday, long queriesToday, long enrollmentsMonth,
                       long failedMonth) {
    }

    /** Una conversación de la lista: un chat de Telegram o los correos de un titular. */
    public record ConversationView(String key, String chatId, Long clientId, String title, String document,
                                   LocalDateTime lastAt, String lastText, BotTopic lastTopic, boolean lastIncoming,
                                   long total, long failed, long enrollments, long queries) {

        public boolean emailOnly() {
            return chatId == null;
        }

        public boolean validated() {
            return clientId != null;
        }
    }

    public record MessageView(Long id, boolean incoming, NotificationChannel channel, BotTopic topic, String text,
                              BotMessageStatus status, LocalDateTime at) {

        public boolean failed() {
            return status == BotMessageStatus.FAILED;
        }
    }

    public record Overview(Kpis kpis, List<ConversationView> conversations) {
    }

    private final BotMessageRepository messageRepository;
    private final ClientRepository clientRepository;
    private final SessionContext sessionContext;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public BotMessageService(BotMessageRepository messageRepository, ClientRepository clientRepository,
                             SessionContext sessionContext) {
        this.messageRepository = messageRepository;
        this.clientRepository = clientRepository;
        this.sessionContext = sessionContext;
    }

    // ---------- registro ----------

    /** Guarda lo que escribió el titular; si la BD falla, el bot sigue respondiendo. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void incoming(String chatId, Long clientId, String text, BotTopic topic) {
        save(chatId, clientId, BotMessageDirection.INCOMING, NotificationChannel.TELEGRAM, text, topic,
                BotMessageStatus.RECEIVED);
    }

    /** Guarda lo que el bot o el correo le enviaron al titular, con su resultado. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void outgoing(String chatId, Long clientId, NotificationChannel channel, String text, BotTopic topic,
                         boolean sent) {
        save(chatId, clientId, BotMessageDirection.OUTGOING, channel, text, topic,
                sent ? BotMessageStatus.SENT : BotMessageStatus.FAILED);
    }

    private void save(String chatId, Long clientId, BotMessageDirection direction, NotificationChannel channel,
                      String text, BotTopic topic, BotMessageStatus status) {
        try {
            BotMessage message = new BotMessage();
            message.setChatId(chatId);
            message.setClient(clientId == null ? null : clientRepository.getReferenceById(clientId));
            message.setDirection(direction);
            message.setChannel(channel);
            message.setTopic(topic == null ? BotTopic.UNKNOWN : topic);
            message.setMessageText(trim(text));
            message.setStatus(status);
            message.setCreatedAt(LocalDateTime.now(AccessLogService.APP_ZONE));
            messageRepository.save(message);
        } catch (RuntimeException failure) {
            log.warn("No se pudo guardar el mensaje del bot ({}).", failure.getClass().getSimpleName());
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notifyListeners();
                }
            });
        } else {
            notifyListeners();
        }
    }

    // ---------- pantalla ----------

    /** Indicadores y conversaciones en dos consultas. Administrador Maestro y Secretaría. */
    @Transactional(readOnly = true)
    public Overview overview() {
        requireAccess();
        LocalDate today = LocalDate.now(AccessLogService.APP_ZONE);
        BotMessageRepository.KpiRow row = messageRepository.kpis(today.atStartOfDay(),
                today.withDayOfMonth(1).atStartOfDay());
        Kpis kpis = new Kpis(value(row == null ? null : row.getMessagesToday()),
                value(row == null ? null : row.getChatsToday()), value(row == null ? null : row.getQueriesToday()),
                value(row == null ? null : row.getEnrollmentsMonth()), value(row == null ? null : row.getFailedMonth()));

        List<ConversationRow> rows = messageRepository.findConversations(SINCE_ALWAYS, MAX_CONVERSATIONS);
        List<Long> clientIds = rows.stream().map(ConversationRow::getClientId).filter(Objects::nonNull).distinct().toList();
        Map<Long, Client> clients = new HashMap<>();
        for (Client client : clientRepository.findAllById(clientIds)) {
            clients.put(client.getId(), client);
        }
        List<ConversationView> conversations = new ArrayList<>();
        for (ConversationRow r : rows) {
            Client client = r.getClientId() == null ? null : clients.get(r.getClientId());
            String title = client != null ? ClientService.displayName(client)
                    : "Chat sin validar · " + shortChat(r.getChatId());
            conversations.add(new ConversationView(r.getConversationKey(), r.getChatId(), r.getClientId(), title,
                    client == null ? null : client.getDocumentNumber(), r.getLastAt(), r.getLastText(),
                    topic(r.getLastTopic()), "INCOMING".equals(r.getLastDirection()), value(r.getTotal()),
                    value(r.getFailed()), value(r.getEnrollments()), value(r.getQueries())));
        }
        return new Overview(kpis, conversations);
    }

    /** Mensajes de una conversación, del más viejo al más nuevo (los últimos MAX_MESSAGES). */
    @Transactional(readOnly = true)
    public List<MessageView> thread(ConversationView conversation) {
        requireAccess();
        if (conversation == null) {
            return List.of();
        }
        PageRequest page = PageRequest.of(0, MAX_MESSAGES);
        List<BotMessage> found = conversation.emailOnly()
                ? messageRepository.findByClient_IdAndChatIdIsNullOrderByCreatedAtDescIdDesc(conversation.clientId(), page)
                : messageRepository.findByChatIdOrderByCreatedAtDescIdDesc(conversation.chatId(), page);
        List<MessageView> views = new ArrayList<>(found.stream()
                .map(m -> new MessageView(m.getId(), m.getDirection() == BotMessageDirection.INCOMING, m.getChannel(),
                        m.getTopic(), m.getMessageText(), m.getStatus(), m.getCreatedAt()))
                .toList());
        Collections.reverse(views);
        return views;
    }

    // ---------- avisos a la pantalla ----------

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException failure) {
                log.warn("Falló el aviso a la pantalla de mensajes ({}).", failure.getClass().getSimpleName());
            }
        }
    }

    // ---------- apoyo ----------

    private void requireAccess() {
        boolean allowed = sessionContext.getCurrentUser().isPresent() && sessionContext.getRoleName()
                .map(AppModule.MENSAJES_BOT::isAllowedFor).orElse(false);
        if (!allowed) {
            throw new UnauthorizedActionException();
        }
    }

    static String trim(String text) {
        String clean = text == null || text.isBlank() ? "(sin texto)" : text;
        return clean.length() <= MAX_TEXT ? clean : clean.substring(0, MAX_TEXT - 1) + "…";
    }

    private static String shortChat(String chatId) {
        if (chatId == null) {
            return "";
        }
        return chatId.length() <= 4 ? chatId : "…" + chatId.substring(chatId.length() - 4);
    }

    private static BotTopic topic(String name) {
        try {
            return name == null ? BotTopic.UNKNOWN : BotTopic.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return BotTopic.UNKNOWN;
        }
    }

    private static long value(Long number) {
        return number == null ? 0 : number;
    }
}
