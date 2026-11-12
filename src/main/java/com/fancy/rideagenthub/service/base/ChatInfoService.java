package com.fancy.rideagenthub.service.base;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.domain.entity.Chat;
import com.fancy.rideagenthub.domain.vo.RestoreChatVO;
import com.fancy.rideagenthub.mapper.ChatMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatInfoService {
    private final ChatMapper chatMapper;
    private final StringRedisTemplate stringRedisTemplate;

    public void assertOwned(String userId, String chatId, boolean allowNew) {
        findOwned(userId, chatId, allowNew);
    }

    public boolean isNewOwnedChat(String userId, String chatId) { return findOwned(userId, chatId, true) == null; }

    private Chat findOwned(String userId, String chatId, boolean allowNew) {
        if (userId == null || chatId == null || chatId.isBlank()) {
            throw new com.fancy.rideagenthub.exception.ForbiddenException("无效的会话身份");
        }
        Chat chat = chatMapper.selectOne(new LambdaQueryWrapper<Chat>().eq(Chat::getChatId, chatId));
        if (chat == null) {
            if (allowNew) return null;
            throw new com.fancy.rideagenthub.exception.BusinessException(404, "会话不存在");
        }
        if (!java.util.Objects.equals(String.valueOf(chat.getUserId()), userId)) {
            throw new com.fancy.rideagenthub.exception.ForbiddenException("无权操作该会话");
        }
        if (Boolean.TRUE.equals(chat.getHistoryDeleted())) {
            throw new com.fancy.rideagenthub.exception.BusinessException(410, "会话已清空，请新建对话");
        }
        return chat;
    }

    public void retireHistory(String chatId) {
        chatMapper.update(null, new LambdaUpdateWrapper<Chat>().eq(Chat::getChatId, chatId)
                .set(Chat::getHistoryDeleted, true).set(Chat::getLocked, true));
    }

    public Integer insertChat(String userId, String chatId, String title){
        assertOwned(userId, chatId, true);
        if (chatMapper.selectCount(new LambdaQueryWrapper<Chat>().eq(Chat::getChatId, chatId)) > 0) return 0;
        String normalizedTitle = title == null || title.isBlank() ? "新对话" : title;
        normalizedTitle = normalizedTitle.substring(0, normalizedTitle.offsetByCodePoints(0,
                Math.min(255, normalizedTitle.codePointCount(0, normalizedTitle.length()))));
        Chat newChat = Chat.builder()
                .chatId(chatId)
                .userId(Long.valueOf(userId))
                .title(normalizedTitle)
                .locked(false)
                .createdAt(com.fancy.rideagenthub.util.BusinessTime.now())
                .updatedAt(com.fancy.rideagenthub.util.BusinessTime.now())
                .build();
        return chatMapper.insert(newChat);
    }

    public void lockChat(String chatId){
        LambdaUpdateWrapper<Chat> updateWrapper = new LambdaUpdateWrapper<Chat>()
                .eq(Chat::getChatId, chatId)
                .set(Chat::getLocked, true);
        chatMapper.update(null, updateWrapper);
    }

    public boolean checkLock(String chatId){
        LambdaQueryWrapper<Chat> queryWrapper = new LambdaQueryWrapper<Chat>()
                .eq(Chat::getChatId, chatId)
                .eq(Chat::getLocked, true);
        return chatMapper.selectOne(queryWrapper) != null;
    }

    public void updateChatTime(String chatId){
        LambdaUpdateWrapper<Chat> updateWrapper = new LambdaUpdateWrapper<Chat>()
                .eq(Chat::getChatId, chatId)
                .set(Chat::getUpdatedAt, com.fancy.rideagenthub.util.BusinessTime.now());
        chatMapper.update(null, updateWrapper);
    }

    public String getLatestChatId(String userId){
        if (userId == null || userId.isBlank()) {
            return null;
        }
        Chat chat = chatMapper.selectOne(new LambdaQueryWrapper<Chat>()
                .eq(Chat::getUserId, Long.valueOf(userId))
                .eq(Chat::getHistoryDeleted, false)
                .orderByDesc(Chat::getUpdatedAt)
                .orderByDesc(Chat::getId)
                .last("limit 1"));
        return chat == null ? null : chat.getChatId();
    }

    public void unlockChat(String chatId){
        LambdaUpdateWrapper<Chat> updateWrapper = new LambdaUpdateWrapper<Chat>()
                .eq(Chat::getChatId, chatId)
                .set(Chat::getLocked, false);
        chatMapper.update(null, updateWrapper);
    }

    public RestoreChatVO restoreChat(String chatId) {
        //chatInfoService将指定chatId设置为lock:false
        //该chatId对应的redisinfokey有效期设为-1 lock:false
        //返回该UUID和对话记录。
        if (chatId == null || chatId.isBlank()) {
            return null;
        }
        unlockChat(chatId);
        String chatInfoKey = RedisKeyConstants.chatInfoKey(chatId);
        stringRedisTemplate.opsForHash().put(chatInfoKey, "locked", "false");
        stringRedisTemplate.persist(chatInfoKey);

        return new RestoreChatVO(chatId, null);
    }
}
