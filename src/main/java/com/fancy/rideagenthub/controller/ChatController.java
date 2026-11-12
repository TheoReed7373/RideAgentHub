package com.fancy.rideagenthub.controller;

import com.fancy.rideagenthub.agentbase.chatinfo.ChatManager;
import com.fancy.rideagenthub.agentbase.memory.MessageMemory;
import com.fancy.rideagenthub.agentbase.memory.MessageParser;
import com.fancy.rideagenthub.annotation.RequirePermission;
import com.fancy.rideagenthub.domain.dto.AgentEvent;
import com.fancy.rideagenthub.domain.dto.ChatParamDTO;
import com.fancy.rideagenthub.domain.response.Result;
import com.fancy.rideagenthub.domain.vo.RestoreChatVO;
import com.fancy.rideagenthub.security.UserTokenContext;
import com.fancy.rideagenthub.service.ChatService;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/chat/v2")
public class ChatController {
    @Resource
    private ChatService chatService;
    @Resource
    private ChatManager chatManager;
    @Resource
    private MessageMemory messageMemory;
    @Resource
    private MessageParser messageParser;
    @Resource
    private com.fancy.rideagenthub.service.base.ChatRequestRunner requestRunner;

    @RequirePermission
    @PostMapping("/c/{id}")
    public Flux<AgentEvent> chat(@PathVariable String id, @RequestBody ChatParamDTO chatParam) {
        String userId = UserTokenContext.getUserIdInString();
        return requestRunner.run(userId, id, chatParam, false);
    }

    @RequirePermission
    @PostMapping("/r/{id}")
    public Flux<AgentEvent> resume(@PathVariable String id, @RequestBody ChatParamDTO chatParam) {
        String userId = UserTokenContext.getUserIdInString();
        return requestRunner.run(userId, id, chatParam, true);
    }

    @RequirePermission
    @GetMapping("/restore")
    public Result getRestorableChat() {
        return Result.ok(chatManager.getRestorableChat(UserTokenContext.getUserIdInString()));
    }

    @RequirePermission
    @PostMapping("/restore")
    public Result restore() {
        String userId = UserTokenContext.getUserIdInString();
        RestoreChatVO vo = chatManager.restoreChat(userId);
        if (vo == null) {
            throw new com.fancy.rideagenthub.exception.BusinessException(404, "没有可恢复的对话");
        }
        List<Message> messages = messageMemory.get(userId, vo.getChatId(), 100);
        vo.setMessages(messageParser.simpleParse(messages));
        return Result.ok(vo);
    }

    @RequirePermission
    @PostMapping("/lock/{id}")
    public Result lockChat(@PathVariable String id) {
        chatManager.assertOwned(UserTokenContext.getUserIdInString(), id, false);
        chatManager.lockChat(id);
        return Result.ok();
    }

    @RequirePermission
    @PostMapping("/newuuid")
    public Result newUUID() {
        return Result.ok(chatManager.getUUID());
    }
}
