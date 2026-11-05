package com.fancy.rideagenthub.service;

import com.fancy.rideagenthub.domain.dto.TicketCreateReqDTO;
import com.fancy.rideagenthub.domain.dto.TicketFeedbackReqDTO;
import com.fancy.rideagenthub.domain.dto.TicketHandleReqDTO;
import com.fancy.rideagenthub.domain.dto.TicketQueryReqDTO;
import com.fancy.rideagenthub.domain.response.PageResult;
import com.fancy.rideagenthub.domain.vo.TicketChatVO;
import com.fancy.rideagenthub.domain.vo.TicketDataVO;
import com.fancy.rideagenthub.domain.vo.TicketDetailVO;
import com.fancy.rideagenthub.domain.vo.TicketSimpleVO;
import com.fancy.rideagenthub.domain.vo.TicketVO;

import java.util.List;

public interface TicketService {
    /**
     * [C端] 提交新工单
     * 逻辑: 校验订单归属 -> 生成唯一TicketNo -> 保存工单 -> 异步通知客服系统
     * 
     * @return 工单编号 (ticket_no)
     */
    String submitTicket(TicketCreateReqDTO req);

    /**
     * [C端] 关闭工单
     * 场景: 用户觉得问题解决了，自行撤销
     * 条件: 状态必须是非"已关闭"
     */
    boolean cancelTicket(String ticketId, Long userId);

    /**
     * [C端] 确认结单
     * 场景: 客服处理完成后，用户点击"满意/确认解决"
     * 状态流转: 待用户确认 -> 已完成
     */
    boolean confirmAndRate(TicketFeedbackReqDTO req);

    /**
     * [C端] 分页查询我的工单
     */
    PageResult<TicketVO> getUserTicketPage(TicketQueryReqDTO req);

    /**
     * [B端] 工单池查询 (待分配/处理中)
     */
    PageResult<TicketVO> getAdminTicketPage(TicketQueryReqDTO req);

    /**
     * [B端] 认领/分配工单
     * 逻辑: 乐观锁更新 handler_id, 状态变为"处理中"
     */
    boolean assignTicket(String ticketId, Long handlerId);

    /**
     * [B端] 转交/强制再分配工单
     * - handlerId 不传时，默认转交给当前登录管理员
     */
    boolean reassignTicket(String ticketId, Long handlerId);

    /**
     * [B端] 客服处理工单 (核心状态机逻辑)
     * 场景: 客服回复消息、标记已解决、驳回等
     * 
     * @param req actionType决定了状态如何流转
     */
    boolean processTicket(TicketHandleReqDTO req);

    /**
     * [通用] 获取工单详情 (包含 基础信息 + 沟通记录列表)
     */
    TicketDetailVO getTicketDetail(String ticketId);

    /**
     * TicketChat-发送消息
     * 逻辑:
     * 1. 插入 chat 表
     * 2. 更新 ticket 主表的 updated_at (让工单顶起来)
     * 3. (可选) 如果是用户发消息，工单状态若为"待用户确认"，自动回调为"处理中"
     */
    boolean sendMessage(TicketHandleReqDTO req);

    /**
     * 获取某工单的聊天记录
     */
    List<TicketChatVO> getChatHistory(String ticketId);

    /**
     * [C端] 获取用户最近未完结工单列表
     * @param userId 用户ID
     * @param limit 查询数量限制
     * @return 未完结工单简要信息列表
     */
    List<TicketSimpleVO> getUnfinishedTickets(Long userId, Integer limit);

    /**
     * [C端] 升级工单优先级
     * @param ticketId 工单编号
     * @param targetLevel 目标优先级
     * @param reason 升级原因
     * @param userId 操作用户ID
     * @return 是否成功
     */
    boolean escalateTicket(String ticketId, Integer targetLevel, String reason, Long userId);

    /**
     * [B端] 客服/管理员升级工单优先级
     * @param ticketId 工单编号
     * @param targetLevel 目标优先级 (2-紧急, 3-特急)
     * @param reason 升级原因
     * @param operatorId 操作员ID
     * @return 是否成功
     */
    boolean escalateTicketByAdmin(String ticketId, Integer targetLevel, String reason, Long operatorId);

    /**
     * [Tool] 补充工单信息（用户追加消息）
     * @param ticketId 工单编号
     * @param content 补充内容
     * @param userId 用户ID
     * @return 是否成功
     */
    boolean appendUserMessage(String ticketId, String content, Long userId);

    /**
     * [Tool] 获取用户最近的一条未完结工单
     * @param userId 用户ID
     * @return 工单详情，若无则返回null
     */
    TicketDetailVO getLatestUnfinishedTicket(Long userId);

    /**
     * [B端] 获取工单统计数据（仪表盘）
     * 包含：待分配数量、处理中数量、今日创建数量、今日完成数量
     */
    TicketDataVO getTicketStatistics();
}
