package com.fancy.rideagenthub.domain.vo;

import com.fancy.rideagenthub.agentbase.memory.SimpleMessage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RestoreChatVO {
    private String chatId;
    private List<SimpleMessage> messages;
}
