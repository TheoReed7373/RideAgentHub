package com.fancy.rideagenthub.agentbase.tool;

import com.fancy.rideagenthub.constant.ToolContextKeyConstants;
import com.fancy.rideagenthub.domain.dto.TicketCreateReqDTO;
import com.fancy.rideagenthub.domain.vo.TicketChatVO;
import com.fancy.rideagenthub.domain.vo.TicketDetailVO;
import com.fancy.rideagenthub.domain.vo.TicketSimpleVO;
import com.fancy.rideagenthub.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class TicketTool {
    private final TicketService ticketService;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Tool(description = "创建工单")
    public String createTicket(@ToolParam(required = false, description = "关联订单ID") String orderId,
            @ToolParam(required = true, description = "工单标题") String title,
            @ToolParam(required = true, description = "工单内容") String content,
            @ToolParam(required = false, description = "工单优先级：1-普通, 2-紧急, 3-特急") Integer priority,
            @ToolParam(required = true, description = "工单类型: 1-物品遗失, 2-费用争议, 3-服务投诉, 4-安全问题, 5-其他") Integer type,
            ToolContext toolContext){
        ToolNotifySupport.notifyToolListener(toolContext, "创建工单 (createTicket)");
        String userId = toolContext.getContext().get(ToolContextKeyConstants.USER_ID).toString();
        Long linkedOrder = null;
        if (orderId != null && !orderId.isBlank()) {
            try { linkedOrder = Long.valueOf(orderId.trim()); }
            catch (NumberFormatException e) { return "关联订单号必须是数字，请核对订单编号"; }
        }
        TicketCreateReqDTO newTicket = TicketCreateReqDTO.builder()
                .userId(userId)
                .userType(1)
                .title(title)
                .content(content)
                .priority(priority == null ? 1 : priority)
                .ticketType(type)
                .orderId(linkedOrder)
                .build();
        return "工单已创建，工单编号：" + ticketService.submitTicket(newTicket);
    }

    @Tool(description = "查询工单进度")
    public String queryTicketProgress(@ToolParam(required = false, description = "工单编号，如果未指定，默认查询最近的一条未完结工单。") String ticketId,
                                      ToolContext toolContext){
        ToolNotifySupport.notifyToolListener(toolContext, "查询工单进度 (queryTicketProgress)");
        String userId = toolContext.getContext().get(ToolContextKeyConstants.USER_ID).toString();
        Long userIdLong = Long.valueOf(userId);
        
        TicketDetailVO detail;
        if (ticketId == null || ticketId.isBlank()) {
            // 未指定工单ID，查询最近的未完结工单
            detail = ticketService.getLatestUnfinishedTicket(userIdLong);
            if (detail == null) {
                return "您当前没有未完结的工单。";
            }
        } else {
            // 查询指定工单
            detail = ticketService.getTicketDetail(ticketId);
            if (detail == null) {
                return "工单不存在。";
            }
            // 校验工单归属
            if (detail.getUserId() == null || !detail.getUserId().equals(String.valueOf(userIdLong))) {
                return "您无权查看此工单。";
            }
        }
        
        // 构建返回信息
        StringBuilder sb = new StringBuilder();
        sb.append("【工单信息】\n");
        sb.append("工单编号：").append(detail.getTicketId()).append("\n");
        sb.append("工单标题：").append(detail.getTitle()).append("\n");
        sb.append("工单类型：").append(detail.getTicketTypeDesc()).append("\n");
        sb.append("优先级：").append(detail.getPriorityDesc()).append("\n");
        sb.append("当前状态：").append(detail.getTicketStatusDesc()).append("\n");
        sb.append("创建时间：").append(detail.getCreatedAt().format(DATE_TIME_FORMATTER)).append("\n");
        sb.append("更新时间：").append(detail.getUpdatedAt().format(DATE_TIME_FORMATTER)).append("\n");
        sb.append("工单内容：").append(detail.getContent()).append("\n");
        
        if (detail.getProcessResult() != null && !detail.getProcessResult().isBlank()) {
            sb.append("处理结果：").append(detail.getProcessResult()).append("\n");
        }
        
        // 如果有沟通记录，展示最近的消息
        List<TicketChatVO> chatHistory = detail.getChatHistory();
        if (chatHistory != null && !chatHistory.isEmpty()) {
            sb.append("\n【沟通记录】\n");
            // 获取最近5条消息
            int startIndex = Math.max(0, chatHistory.size() - 5);
            for (int i = startIndex; i < chatHistory.size(); i++) {
                TicketChatVO chat = chatHistory.get(i);
                sb.append("[").append(chat.getCreatedAt().format(DATE_TIME_FORMATTER)).append("] ");
                sb.append(chat.getSenderRoleDesc()).append("：").append(chat.getContent()).append("\n");
            }
        }
        
        return sb.toString();
    }

    @Tool(description = "获取最近未完结工单列表")
    public String queryUnfinishedTickets(@ToolParam(required = false, description = "查询数量") Integer limit,
                                        ToolContext toolContext){
        String userId = toolContext.getContext().get(ToolContextKeyConstants.USER_ID).toString();
        Long userIdLong = Long.valueOf(userId);
        
        List<TicketSimpleVO> tickets = ticketService.getUnfinishedTickets(userIdLong, limit);
        
        if (tickets == null || tickets.isEmpty()) {
            return "您当前没有未完结的工单。";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("【未完结工单列表】共").append(tickets.size()).append("条\n\n");
        
        for (int i = 0; i < tickets.size(); i++) {
            TicketSimpleVO ticket = tickets.get(i);
            sb.append(i + 1).append(". 工单编号：").append(ticket.getTicketId()).append("\n");
            sb.append("   标题：").append(ticket.getTitle()).append("\n");
            sb.append("   类型：").append(ticket.getTicketTypeDesc()).append("\n");
            sb.append("   状态：").append(ticket.getTicketStatusDesc()).append("\n");
            sb.append("   创建时间：").append(ticket.getCreatedAt().format(DATE_TIME_FORMATTER)).append("\n");
            if (i < tickets.size() - 1) {
                sb.append("\n");
            }
        }
        
        return sb.toString();
    }

    @Tool(description = "当用户愤怒、提及人身安全、报警等话题时，升级工单")
    public String escalateTicket(@ToolParam(description = "工单编号") String ticketId,
                                @ToolParam(description = "升级原因") String reason,
                                @ToolParam(description = "目标级别：2-紧急, 3-特急") Integer targetLevel,
                                ToolContext toolContext){
        String userId = toolContext.getContext().get(ToolContextKeyConstants.USER_ID).toString();
        Long userIdLong = Long.valueOf(userId);
        
        // 参数校验
        if (ticketId == null || ticketId.isBlank()) {
            return "请提供要升级的工单编号。";
        }
        if (reason == null || reason.isBlank()) {
            return "请提供升级原因。";
        }
        if (targetLevel == null || (targetLevel != 2 && targetLevel != 3)) {
            return "目标级别无效，仅支持2-紧急或3-特急。";
        }
        
        try {
            boolean success = ticketService.escalateTicket(ticketId, targetLevel, reason, userIdLong);
            if (success) {
                String priorityDesc = targetLevel == 2 ? "紧急" : "特急";
                ToolNotifySupport.notifyToolListener(toolContext, "工单已升级为" + priorityDesc + "级别");
                return "工单已成功升级为【" + priorityDesc + "】级别，客服将优先处理您的问题。";
            } else {
                return "工单升级失败，请稍后重试。";
            }
        } catch (IllegalArgumentException e) {
            return "升级失败：" + e.getMessage();
        } catch (Exception e) {
            log.error("工单升级异常: ticketId={}, error={}", ticketId, e.getMessage(), e);
            return "系统异常，请稍后重试。";
        }
    }

    @Tool(description = "补充工单信息")
    public String appendUserMessage(@ToolParam(description = "用户需要补充的内容")String content,
                                    @ToolParam(description = "工单ID") String ticketId,
                                    ToolContext toolContext){
        String userId = toolContext.getContext().get(ToolContextKeyConstants.USER_ID).toString();
        Long userIdLong = Long.valueOf(userId);
        
        // 参数校验
        if (ticketId == null || ticketId.isBlank()) {
            return "请提供工单编号。";
        }
        if (content == null || content.isBlank()) {
            return "请提供需要补充的内容。";
        }
        
        try {
            boolean success = ticketService.appendUserMessage(ticketId, content, userIdLong);
            if (success) {
                return "您的补充信息已提交，客服会尽快查看并处理。";
            } else {
                return "信息补充失败，请稍后重试。";
            }
        } catch (IllegalArgumentException e) {
            return "补充失败：" + e.getMessage();
        } catch (Exception e) {
            log.error("补充工单信息异常: ticketId={}, error={}", ticketId, e.getMessage(), e);
            return "系统异常，请稍后重试。";
        }
    }

}



