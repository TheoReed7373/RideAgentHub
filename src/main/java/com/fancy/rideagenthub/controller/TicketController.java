package com.fancy.rideagenthub.controller;

import com.fancy.rideagenthub.annotation.RequirePermission;
import com.fancy.rideagenthub.domain.dto.TicketAppendMessageReqDTO;
import com.fancy.rideagenthub.domain.dto.TicketCreateReqDTO;
import com.fancy.rideagenthub.domain.dto.TicketEscalateReqDTO;
import com.fancy.rideagenthub.domain.dto.TicketFeedbackReqDTO;
import com.fancy.rideagenthub.domain.dto.TicketHandleReqDTO;
import com.fancy.rideagenthub.domain.dto.TicketQueryReqDTO;
import com.fancy.rideagenthub.domain.enums.TicketSenderRole;
import com.fancy.rideagenthub.domain.response.PageResult;
import com.fancy.rideagenthub.domain.response.Result;
import com.fancy.rideagenthub.domain.vo.TicketChatVO;
import com.fancy.rideagenthub.domain.vo.TicketDataVO;
import com.fancy.rideagenthub.domain.vo.TicketDetailVO;
import com.fancy.rideagenthub.domain.vo.TicketSimpleVO;
import com.fancy.rideagenthub.domain.vo.TicketVO;
import com.fancy.rideagenthub.exception.ForbiddenException;
import com.fancy.rideagenthub.security.UserTokenContext;
import com.fancy.rideagenthub.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 工单管理控制器
 * <p>
 * C端：提交/查询/关闭/评价/补充信息/升级优先级
 * B端：工单池/认领/处理（回复、处理完成、转交、驳回）
 */
@RestController
@RequestMapping("/ticket")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    // ========================= C端接口 =========================

    /**
     * 提交新工单
     */
    @RequirePermission
    @PostMapping("/submit")
    public Result submit(@RequestBody TicketCreateReqDTO req) {
        Long userId = UserTokenContext.getUserIdInLong();
        req.setUserId(String.valueOf(userId));
        String ticketId = ticketService.submitTicket(req);
        return Result.ok(ticketId);
    }

    /**
     * 取消/关闭工单（用户主动撤销）
     */
    @RequirePermission
    @PostMapping("/cancel/{ticketId}")
    public Result cancel(@PathVariable String ticketId) {
        Long userId = UserTokenContext.getUserIdInLong();
        boolean ok = ticketService.cancelTicket(ticketId, userId);
        return Result.ok(ok);
    }

    /**
     * 用户确认结单并评价
     */
    @RequirePermission
    @PostMapping("/feedback")
    public Result feedback(@RequestBody TicketFeedbackReqDTO req) {
        Long userId = UserTokenContext.getUserIdInLong();
        req.setUserId(String.valueOf(userId));
        boolean ok = ticketService.confirmAndRate(req);
        return Result.ok(ok);
    }

    /**
     * 分页查询我的工单
     */
    @RequirePermission
    @PostMapping("/my/page")
    public Result myPage(@RequestBody TicketQueryReqDTO req) {
        Long userId = UserTokenContext.getUserIdInLong();
        req.setUserId(String.valueOf(userId));
        PageResult<TicketVO> page = ticketService.getUserTicketPage(req);
        return Result.ok(page);
    }

    /**
     * 获取工单详情（包含沟通记录）
     */
    @RequirePermission
    @GetMapping("/detail/{ticketId}")
    public Result detail(@PathVariable String ticketId) {
        TicketDetailVO detail = ticketService.getTicketDetail(ticketId);
        assertTicketReadable(detail);
        return Result.ok(detail);
    }

    /**
     * 获取某工单的聊天记录
     */
    @RequirePermission
    @GetMapping("/{ticketId}/chat")
    public Result chatHistory(@PathVariable String ticketId) {
        TicketDetailVO detail = ticketService.getTicketDetail(ticketId);
        assertTicketReadable(detail);
        List<TicketChatVO> chats = detail.getChatHistory();
        return Result.ok(chats);
    }

    /**
     * 获取用户最近未完结工单列表
     */
    @RequirePermission
    @GetMapping("/my/unfinished")
    public Result unfinished(@RequestParam(required = false) Integer limit) {
        Long userId = UserTokenContext.getUserIdInLong();
        List<TicketSimpleVO> list = ticketService.getUnfinishedTickets(userId, limit);
        return Result.ok(list);
    }

    /**
     * 获取用户最近一条未完结工单（含聊天记录）
     */
    @RequirePermission
    @GetMapping("/my/latest-unfinished")
    public Result latestUnfinished() {
        Long userId = UserTokenContext.getUserIdInLong();
        TicketDetailVO detail = ticketService.getLatestUnfinishedTicket(userId);
        return Result.ok(detail);
    }

    /**
     * 用户补充工单信息（追加消息）
     */
    @RequirePermission
    @PostMapping("/my/append")
    public Result append(@RequestBody TicketAppendMessageReqDTO req) {
        Long userId = UserTokenContext.getUserIdInLong();
        boolean ok = ticketService.appendUserMessage(req.getTicketId(), req.getContent(), userId);
        return Result.ok(ok);
    }

    /**
     * 用户升级工单优先级
     */
    @RequirePermission
    @PostMapping("/my/escalate")
    public Result escalate(@RequestBody TicketEscalateReqDTO req) {
        Long userId = UserTokenContext.getUserIdInLong();
        boolean ok = ticketService.escalateTicket(req.getTicketId(), req.getTargetLevel(), req.getReason(), userId);
        return Result.ok(ok);
    }

    // ========================= B端接口（客服/管理员） =========================

    /**
     * 工单池分页查询（待分配/处理中等）
     */
    @RequirePermission({"ADMIN", "SUPPORT"})
    @PostMapping("/admin/page")
    public Result adminPage(@RequestBody TicketQueryReqDTO req) {
        PageResult<TicketVO> page = ticketService.getAdminTicketPage(req);
        return Result.ok(page);
    }

    /**
     * 认领工单
     * - handlerId 不传时，默认认领到当前登录客服
     */
    @RequirePermission({"ADMIN", "SUPPORT"})
    @PostMapping("/admin/assign/{ticketId}")
    public Result assign(@PathVariable String ticketId, @RequestParam(required = false) Long handlerId) {
        Long currentUserId = UserTokenContext.getUserIdInLong();
        Long targetHandlerId = handlerId != null ? handlerId : currentUserId;
        boolean ok = ticketService.assignTicket(ticketId, targetHandlerId);
        return Result.ok(ok);
    }

    /**
     * 转交/强制再分配工单
     * - handlerId 不传时，默认转交给当前登录管理员
     */
    @RequirePermission({"ADMIN"})
    @PostMapping("/admin/reassign/{ticketId}")
    public Result reassign(@PathVariable String ticketId, @RequestParam(required = false) Long handlerId) {
        Long currentUserId = UserTokenContext.getUserIdInLong();
        Long targetHandlerId = handlerId != null ? handlerId : currentUserId;
        boolean ok = ticketService.reassignTicket(ticketId, targetHandlerId);
        return Result.ok(ok);
    }

    /**
     * 客服处理工单（回复/处理完成/转交/驳回）
     */
    @RequirePermission({"ADMIN", "SUPPORT"})
    @PostMapping("/admin/process")
    public Result process(@RequestBody TicketHandleReqDTO req) {
        Long operatorId = UserTokenContext.getUserIdInLong();
        req.setOperatorId(String.valueOf(operatorId));
        req.setOperatorRole(TicketSenderRole.CUSTOMER_SERVICE.getCode());
        boolean ok = ticketService.processTicket(req);
        return Result.ok(ok);
    }

    /**
     * 客服发送消息（不改变状态）
     * 说明：等价于 actionType=REPLY
     */
    @RequirePermission({"ADMIN", "SUPPORT"})
    @PostMapping("/admin/reply")
    public Result reply(@RequestBody TicketHandleReqDTO req) {
        req.setActionType(TicketHandleReqDTO.ACTION_REPLY);
        Long operatorId = UserTokenContext.getUserIdInLong();
        req.setOperatorId(String.valueOf(operatorId));
        req.setOperatorRole(TicketSenderRole.CUSTOMER_SERVICE.getCode());
        boolean ok = ticketService.processTicket(req);
        return Result.ok(ok);
    }

    /**
     * 工单统计数据（仪表盘）
     */
    @RequirePermission({"ADMIN", "SUPPORT"})
    @GetMapping("/admin/statistics")
    public Result statistics() {
        TicketDataVO data = ticketService.getTicketStatistics();
        return Result.ok(data);
    }

    /**
     * 客服/管理员升级工单优先级
     */
    @RequirePermission({"ADMIN", "SUPPORT"})
    @PostMapping("/admin/escalate")
    public Result escalateAdmin(@RequestBody TicketEscalateReqDTO req) {
        Long operatorId = UserTokenContext.getUserIdInLong();
        boolean ok = ticketService.escalateTicketByAdmin(req.getTicketId(), req.getTargetLevel(), req.getReason(), operatorId);
        return Result.ok(ok);
    }

    // ========================= 权限辅助 =========================

    private void assertTicketReadable(TicketDetailVO detail) {
        if (detail == null) {
            return;
        }

        if (UserTokenContext.hasAnyRole("ADMIN", "SUPPORT")) {
            return;
        }

        Long currentUserId = UserTokenContext.getUserIdInLong();
        if (detail.getUserId() == null || !String.valueOf(currentUserId).equals(detail.getUserId())) {
            throw new ForbiddenException("无权访问该工单");
        }
    }
}
