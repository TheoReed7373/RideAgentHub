package com.fancy.rideagenthub.agentbase.memory;

import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class HeapMemory {

    private final Map<String, List<Message>> heapCache = new ConcurrentHashMap<>();

    public List<Message> getAll(String chatId) {
        if (chatId == null) {
            return List.of();
        }
        List<Message> list = heapCache.get(chatId);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    public void append(String chatId, List<Message> messages) {
        if (chatId == null || messages == null || messages.isEmpty()) {
            return;
        }
        List<Message> list = heapCache.computeIfAbsent(chatId, k -> Collections.synchronizedList(new ArrayList<>()));
        list.addAll(messages);
    }

    public void overwrite(String chatId, List<Message> messages) {
        if (chatId == null) {
            return;
        }
        if (messages == null) {
            heapCache.remove(chatId);
            return;
        }
        heapCache.put(chatId, Collections.synchronizedList(new ArrayList<>(messages)));
    }

    public void clear(String chatId) {
        if (chatId == null) {
            return;
        }
        heapCache.remove(chatId);
    }
}
