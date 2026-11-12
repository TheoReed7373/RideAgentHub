package com.fancy.rideagenthub.agentbase.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.rideagenthub.domain.entity.ChatMessage;
import com.fancy.rideagenthub.mapper.ChatMessageMapper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class MysqlMemory {

    private final ChatMessageMapper chatMessageMapper;
    private final MessageParser messageParser;
    @jakarta.annotation.Resource
    private com.fancy.rideagenthub.mapper.ChatMapper chatMapper;

    public MysqlMemory(ChatMessageMapper chatMessageMapper, MessageParser messageParser) {
        this.chatMessageMapper = chatMessageMapper;
        this.messageParser = messageParser;
    }

    public List<Message> getLastN(String chatId, int lastN) {
        if (chatId == null || lastN <= 0) {
            return List.of();
        }
        List<ChatMessage> rows = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getChatId, chatId)
                        .orderByDesc(ChatMessage::getId)
                        .last("limit " + lastN)
        );
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<String> json = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) if (rows.get(i) != null) json.add(rows.get(i).getMessageText());
        return messageParser.unparseHistory(json);
    }

    public List<Message> getAll(String chatId) {
        if (chatId == null) {
            return List.of();
        }
        List<ChatMessage> rows = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getChatId, chatId)
                        .orderByAsc(ChatMessage::getId)
        );
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return messageParser.unparseHistory(rows.stream().filter(java.util.Objects::nonNull)
                .map(ChatMessage::getMessageText).toList());
    }

    @Transactional
    public void append(String chatId, List<Message> messages) {
        if (chatId == null || messages == null || messages.isEmpty()) {
            return;
        }
        var chat = chatMapper.selectOne(new LambdaQueryWrapper<com.fancy.rideagenthub.domain.entity.Chat>()
                .eq(com.fancy.rideagenthub.domain.entity.Chat::getChatId, chatId).last("FOR UPDATE"));
        if (chat == null || Boolean.TRUE.equals(chat.getHistoryDeleted())) {
            throw new com.fancy.rideagenthub.exception.BusinessException(410, "会话不存在或已清空");
        }
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            String json = messageParser.parse(message);
            if (json == null || json.isBlank()) {
                continue;
            }
            ChatMessage row = ChatMessage.builder()
                    .chatId(chatId)
                    .messageText(json)
                    .build();
            chatMessageMapper.insert(row);
        }
    }

    public void clear(String chatId) {
        if (chatId == null) {
            return;
        }
        chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getChatId, chatId));
    }
}
