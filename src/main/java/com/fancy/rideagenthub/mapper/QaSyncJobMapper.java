package com.fancy.rideagenthub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fancy.rideagenthub.domain.entity.QaSyncJob;
import org.apache.ibatis.annotations.*;

@Mapper
public interface QaSyncJobMapper extends BaseMapper<QaSyncJob> {
    @Insert("INSERT INTO sys_qa_sync_job(group_id, version) VALUES(#{groupId}, 1) " +
            "ON DUPLICATE KEY UPDATE version = version + 1, next_attempt_at = CURRENT_TIMESTAMP")
    void enqueue(@Param("groupId") Long groupId);
    @Select("SELECT group_id, version FROM sys_qa_sync_job WHERE next_attempt_at <= CURRENT_TIMESTAMP " +
            "ORDER BY next_attempt_at, group_id LIMIT 5 FOR UPDATE SKIP LOCKED")
    java.util.List<QaSyncJob> claimBatch();
    @Update("UPDATE sys_qa_sync_job SET next_attempt_at = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 30 SECOND) WHERE group_id = #{groupId}")
    void defer(@Param("groupId") Long groupId);
}
