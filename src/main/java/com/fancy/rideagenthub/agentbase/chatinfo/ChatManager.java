package com.fancy.rideagenthub.agentbase.chatinfo;

import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.domain.vo.RestoreChatVO;
import com.fancy.rideagenthub.service.base.ChatInfoService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
@Slf4j
public class ChatManager {
    @Resource
    private ChatInfoService chatInfoService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public static Duration expireLock = Duration.ofMinutes(60);

    public void lockChat(String chatId){
        chatInfoService.lockChat(chatId);
        String chatInfoKey = RedisKeyConstants.chatInfoKey(chatId);
        stringRedisTemplate.opsForHash().put(chatInfoKey, "locked", "true");
        stringRedisTemplate.expire(chatInfoKey, expireLock);
    }

    public void updateChatTime(String chatId){
        chatInfoService.updateChatTime(chatId);
    }

    public boolean isLocked(String chatId){
        Object locked = stringRedisTemplate.opsForHash().get(RedisKeyConstants.chatInfoKey(chatId), "locked");
        //如果redis查不到有两种可能，一种是新对话，一种是已经过期了。
        if(locked == null){
            return chatInfoService.checkLock(chatId);
        }else{
            return "true".equals(locked); //其实这里始终为真
        }
    }

    public void initChat(String userId, String chatId, String title){
        chatInfoService.insertChat(userId, chatId, title);
    }

    public boolean getRestorableChat(String userId){
        String chatId = getRestorableChatId(userId);
        if (chatId == null) {
            return false;
        }
        stringRedisTemplate.expire(RedisKeyConstants.chatInfoKey(chatId), expireLock);
        return true;
    }

    public RestoreChatVO restoreChat(String userId){
        String chatId = getRestorableChatId(userId);
        if (chatId == null) {
            return null;
        }
        return chatInfoService.restoreChat(chatId);
    }

    public String getUUID(){
        return UUID.randomUUID().toString();
    }

    private String getRestorableChatId(String userId) {
        String chatId = chatInfoService.getLatestChatId(userId);
        if (chatId == null || chatId.isBlank()) {
            return null;
        }

        String chatInfoKey = RedisKeyConstants.chatInfoKey(chatId);
        Object classification = stringRedisTemplate.opsForHash().get(chatInfoKey, "classification");
        if (classification == null || !"ORDER".equals(classification.toString())) {
            return null;
        }

        if (stringRedisTemplate.opsForHash().get(chatInfoKey, "OrderId") != null) {
            return null;
        }
        return chatId;
    }
}
