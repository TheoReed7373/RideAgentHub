package com.fancy.rideagenthub.agentbase.memory;

import com.fancy.rideagenthub.agentbase.chatinfo.ChatManager;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MessageMemory {

    private static final Duration FOCUS_CHAT_TTL = Duration.ofHours(24);
    private static final Duration NON_FOCUS_CHAT_TTL = Duration.ofMinutes(30);

    // Key: userId, Value: current chatId
    private final Map<String, String> userCurrentChat = new ConcurrentHashMap<>();

    private final HeapMemory heapMemory;
    private final RedisMemory redisMemory;
    private final MysqlMemory mysqlMemory;
    private final ChatManager chatManager;

    public MessageMemory(HeapMemory heapMemory, RedisMemory redisMemory, MysqlMemory mysqlMemory, ChatManager chatManager) {
        this.heapMemory = heapMemory;
        this.redisMemory = redisMemory;
        this.mysqlMemory = mysqlMemory;
        this.chatManager = chatManager;
    }

    /**
     * 获取当前 chatId 的最近 lastN 条上下文（按时间正序）。
     */
    public List<Message> get(String userId, String chatId, int lastN) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId不能为空");
        }
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("chatId不能为空");
        }
        if (lastN <= 0) {
            return List.of();
        }

        switchFocusChatIfNeeded(userId, chatId);

        List<Message> heapAll = heapMemory.getAll(chatId);
        if (!heapAll.isEmpty()) {
            return tail(heapAll, lastN);
        }

        List<Message> redisLastN = redisMemory.getLastN(chatId, lastN);
        if (!redisLastN.isEmpty()) {
            heapMemory.overwrite(chatId, redisLastN);
            return redisLastN;
        }

        List<Message> mysqlLastN = mysqlMemory.getLastN(chatId, lastN);
        if (!mysqlLastN.isEmpty()) {
            redisMemory.overwrite(chatId, mysqlLastN);
            redisMemory.expire(chatId, FOCUS_CHAT_TTL);
            heapMemory.overwrite(chatId, mysqlLastN);
            return mysqlLastN;
        }

        return List.of();
    }

    /**
     * 保存新消息（同步写入 L1/L2/L3）。
     */
    public void save(String userId, String chatId, List<Message> newMessages) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId不能为空");
        }
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("chatId不能为空");
        }
        if (newMessages == null || newMessages.isEmpty()) {
            return;
        }

        switchFocusChatIfNeeded(userId, chatId);

        heapMemory.append(chatId, newMessages);
        redisMemory.append(chatId, newMessages);
        redisMemory.expire(chatId, FOCUS_CHAT_TTL);
        mysqlMemory.append(chatId, newMessages);
        chatManager.updateChatTime(chatId);
    }

    /**
     * 获取当前 chatId 下所有 UserMessage（按时间正序）。
     */
    public List<UserMessage> getUserMessage(String userId, String chatId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId不能为空");
        }
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("chatId不能为空");
        }

        switchFocusChatIfNeeded(userId, chatId);

        List<Message> allMessages = heapMemory.getAll(chatId);
        if (allMessages.isEmpty()) {
            List<Message> redisAll = redisMemory.getAll(chatId);
            if (!redisAll.isEmpty()) {
                heapMemory.overwrite(chatId, redisAll);
                allMessages = redisAll;
            } else {
                List<Message> mysqlAll = mysqlMemory.getAll(chatId);
                if (!mysqlAll.isEmpty()) {
                    redisMemory.overwrite(chatId, mysqlAll);
                    redisMemory.expire(chatId, FOCUS_CHAT_TTL);
                    heapMemory.overwrite(chatId, mysqlAll);
                    allMessages = mysqlAll;
                } else {
                    return List.of();
                }
            }
        }

        List<UserMessage> userMessages = new ArrayList<>();
        for (Message message : allMessages) {
            if (message instanceof UserMessage userMessage) {
                userMessages.add(userMessage);
            }
        }
        return Collections.unmodifiableList(userMessages);
    }

    public void clearChat(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return;
        }
        heapMemory.clear(chatId);
        redisMemory.clear(chatId);
        mysqlMemory.clear(chatId);
    }

    private void switchFocusChatIfNeeded(String userId, String newChatId) {
        String prevChatId = userCurrentChat.put(userId, newChatId);
        if (prevChatId == null || prevChatId.equals(newChatId)) {
            redisMemory.expire(newChatId, FOCUS_CHAT_TTL);
            return;
        }

        // 用户切换对话：清 L1，L2 设置短过期，锁住之前对话
        heapMemory.clear(prevChatId);
        redisMemory.expire(prevChatId, NON_FOCUS_CHAT_TTL);
        redisMemory.expire(newChatId, FOCUS_CHAT_TTL);
        chatManager.lockChat(prevChatId);
    }

    private List<Message> tail(List<Message> messages, int lastN) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        if (lastN <= 0) {
            return List.of();
        }
        if (messages.size() <= lastN) {
            return Collections.unmodifiableList(new ArrayList<>(messages));
        }
        return Collections.unmodifiableList(new ArrayList<>(messages.subList(messages.size() - lastN, messages.size())));
    }
}
