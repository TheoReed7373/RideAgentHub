package com.fancy.rideagenthub.agentbase.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.rideagenthub.domain.entity.ChatMessage;
import com.fancy.rideagenthub.mapper.ChatMessageMapper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class MysqlMemory {

    private final ChatMessageMapper chatMessageMapper;
    private final MessageParser messageParser;

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
        List<Message> desc = new ArrayList<>(rows.size());
        for (ChatMessage row : rows) {
            if (row == null || row.getMessageText() == null || row.getMessageText().isBlank()) {
                continue;
            }
            Message msg = messageParser.unparse(row.getMessageText());
            if (msg != null) {
                desc.add(msg);
            }
        }
        Collections.reverse(desc);
        return desc;
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
        List<Message> result = new ArrayList<>(rows.size());
        for (ChatMessage row : rows) {
            if (row == null || row.getMessageText() == null || row.getMessageText().isBlank()) {
                continue;
            }
            Message msg = messageParser.unparse(row.getMessageText());
            if (msg != null) {
                result.add(msg);
            }
        }
        return result;
    }

    public void append(String chatId, List<Message> messages) {
        if (chatId == null || messages == null || messages.isEmpty()) {
            return;
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
