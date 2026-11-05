package com.fancy.rideagenthub.service;

import com.fancy.rideagenthub.domain.dto.AgentEvent;
import com.fancy.rideagenthub.domain.dto.ChatParamDTO;
import reactor.core.publisher.Sinks;

public interface ChatService {
    public void chat(String id, ChatParamDTO chatParam, Sinks.Many<AgentEvent> sink, String userId);

    public void resume(String id, ChatParamDTO chatParam, Sinks.Many<AgentEvent> sink, String userId);
}
