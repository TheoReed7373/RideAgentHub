package com.fancy.rideagenthub.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_chat")
public class Chat implements Serializable {

	private static final long serialVersionUID = 1L;

	@TableId(value = "id", type = IdType.AUTO)
	private Long id;

	@TableField("chat_id")
	private String chatId;

	@TableField("user_id")
	private Long userId;

	@TableField("title")
	private String title;

	@TableField("locked")
	private Boolean locked;
    @TableField("history_deleted")
    private Boolean historyDeleted;

	@TableField("created_at")
	private LocalDateTime createdAt;

	@TableField("updated_at")
	private LocalDateTime updatedAt;
}
