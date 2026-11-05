package com.fancy.rideagenthub.agentbase.memory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SimpleMessage {
    private String role;
    private String content;

    public static SimpleMessage user(String content){
        return new SimpleMessage("USER", content);
    }

    public static SimpleMessage assistant(String content){
        return new SimpleMessage("ASSISTANT", content);
    }
}
