package com.fancy.rideagenthub.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("sys_qa_sync_job")
public class QaSyncJob {
    @TableId(type = IdType.INPUT)
    private Long groupId;
    private Long version;
}
