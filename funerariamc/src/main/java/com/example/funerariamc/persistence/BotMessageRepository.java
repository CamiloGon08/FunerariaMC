package com.example.funerariamc.persistence;

import com.example.funerariamc.model.BotMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface BotMessageRepository extends JpaRepository<BotMessage, Long> {

    /** Una fila por conversación, la más reciente primero.*/
    interface ConversationRow {
        String getConversationKey();
        String getChatId();
        Long getClientId();
        LocalDateTime getLastAt();
        String getLastText();
        String getLastTopic();
        String getLastDirection();
        Long getTotal();
        Long getFailed();
        Long getEnrollments();
        Long getQueries();
    }

    @Query(value = """
            select m.conversation_key as conversationKey,
                   max(m.chat_id) as chatId,
                   (array_agg(m.client_id order by m.created_at desc) filter (where m.client_id is not null))[1] as clientId,
                   max(m.created_at) as lastAt,
                   (array_agg(m.message_text order by m.created_at desc, m.id desc))[1] as lastText,
                   (array_agg(m.topic order by m.created_at desc, m.id desc))[1] as lastTopic,
                   (array_agg(m.direction order by m.created_at desc, m.id desc))[1] as lastDirection,
                   count(*) as total,
                   count(*) filter (where m.status = 'FAILED') as failed,
                   count(*) filter (where m.topic = 'ENROLLMENT') as enrollments,
                   count(*) filter (where m.direction = 'OUTGOING'
                                    and m.topic in ('PLAN', 'BENEFICIARIES', 'NEXT_PAYMENT', 'PAYMENTS', 'CONTACT')) as queries
            from (select b.*, coalesce('T:' || b.chat_id, 'E:' || b.client_id) as conversation_key
                  from bot_message b where b.created_at >= :since) m
            group by m.conversation_key
            order by max(m.created_at) desc
            limit :maxRows
            """, nativeQuery = true)
    List<ConversationRow> findConversations(@Param("since") LocalDateTime since, @Param("maxRows") int maxRows);

    /** Mensajes de un chat de Telegram, del más nuevo al más viejo. */
    List<BotMessage> findByChatIdOrderByCreatedAtDescIdDesc(String chatId, Pageable page);

    /** Correos de un titular que no pasaron por Telegram. */
    List<BotMessage> findByClient_IdAndChatIdIsNullOrderByCreatedAtDescIdDesc(Long clientId, Pageable page);

    /** Indicadores de la banda en una sola consulta: hoy y el mes en curso. */
    interface KpiRow {
        Long getMessagesToday();
        Long getChatsToday();
        Long getQueriesToday();
        Long getEnrollmentsMonth();
        Long getFailedMonth();
    }

    @Query(value = """
            select count(*) filter (where b.created_at >= :dayStart) as messagesToday,
                   count(distinct b.chat_id) filter (where b.created_at >= :dayStart) as chatsToday,
                   count(*) filter (where b.created_at >= :dayStart and b.direction = 'OUTGOING'
                                    and b.topic in ('PLAN', 'BENEFICIARIES', 'NEXT_PAYMENT', 'PAYMENTS', 'CONTACT')) as queriesToday,
                   count(*) filter (where b.topic = 'ENROLLMENT' and b.status = 'SENT') as enrollmentsMonth,
                   count(*) filter (where b.status = 'FAILED') as failedMonth
            from bot_message b
            where b.created_at >= :monthStart
            """, nativeQuery = true)
    KpiRow kpis(@Param("dayStart") LocalDateTime dayStart, @Param("monthStart") LocalDateTime monthStart);
}
