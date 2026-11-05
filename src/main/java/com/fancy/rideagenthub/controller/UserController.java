package com.fancy.rideagenthub.controller;

import com.fancy.rideagenthub.annotation.RequirePermission;
import com.fancy.rideagenthub.domain.dto.AccountInfoDTO;
import com.fancy.rideagenthub.domain.dto.AdminUserPageReqDTO;
import com.fancy.rideagenthub.domain.dto.AdminUserUpdateReqDTO;
import com.fancy.rideagenthub.domain.dto.UserCurrentUpdateReqDTO;
import com.fancy.rideagenthub.domain.dto.UserPasswordResetReqDTO;
import com.fancy.rideagenthub.domain.dto.SupportUserPageReqDTO;
import com.fancy.rideagenthub.domain.dto.UserIdListReqDTO;
import com.fancy.rideagenthub.domain.dto.UserLocation;
import com.fancy.rideagenthub.domain.response.PageResult;
import com.fancy.rideagenthub.domain.response.Result;
import com.fancy.rideagenthub.domain.vo.AdminUserVO;
import com.fancy.rideagenthub.domain.vo.SupportUserVO;
import com.fancy.rideagenthub.security.UserTokenContext;
import com.fancy.rideagenthub.service.AuthService;
import com.fancy.rideagenthub.service.UserService;
import com.fancy.rideagenthub.service.base.UserUsernameBloomFilterService;
import com.fancy.rideagenthub.domain.vo.UserCurrentInfoVO;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user")
public class UserController {
    private final UserService userService;
    private final AuthService authService;
    private final UserUsernameBloomFilterService usernameBloomFilterService;

    public UserController(UserService userService,
                          AuthService authService,
                          UserUsernameBloomFilterService usernameBloomFilterService) {
        this.userService = userService;
        this.authService = authService;
        this.usernameBloomFilterService = usernameBloomFilterService;
    }
    /**
     * [C端] 用户定位数据输入和获取
     */
    @RequirePermission({"USER"})
    @GetMapping("/loc")
    public Result getUserLoc() {
        Long userId = UserTokenContext.getUserIdInLong();
        UserLocation location = userService.getUserLocation(userId.toString());
        if (location == null) {
            return Result.fail(404, "用户定位数据不存在");
        }
        return Result.ok(location);
    }

    @RequirePermission({"USER"})
    @PostMapping("/loc")
    public Result saveUserLoc(@RequestBody UserLocation userLocation) {
        Long userId = UserTokenContext.getUserIdInLong();
        userService.saveUserLocation(userId.toString(), userLocation);
        return Result.ok();
    }

    /**
     * 获取当前用户信息
     */
    @RequirePermission
    @GetMapping("/current")
    public Result getCurrentUserInfo() {
        UserCurrentInfoVO info = userService.getCurrentUserInfo();
        return Result.ok(info);
    }

    /**
     * 修改当前用户信息
     */
    @RequirePermission
    @PostMapping("/current/update")
    public Result updateCurrentUserInfo(@RequestBody UserCurrentUpdateReqDTO req) {
        userService.updateCurrentUserInfo(req);
        return Result.ok();
    }

    /**
     * 当前用户重置密码
     */
    @RequirePermission
    @PostMapping("/current/password/reset")
    public Result resetCurrentUserPassword(@RequestBody UserPasswordResetReqDTO req) {
        userService.resetCurrentUserPassword(req);
        return Result.ok();
    }

    /**
     * [B端] 分页获取所有客服（支持按 username 模糊搜索）
     */
    @RequirePermission({"ADMIN"})
    @PostMapping("/admin/support/page")
    public Result supportPage(@RequestBody SupportUserPageReqDTO req) {
        PageResult<SupportUserVO> page = userService.getSupportUserPage(req);
        return Result.ok(page);
    }

    /**
     * [B端] 创建账户
     */
    @RequirePermission({"ADMIN"})
    @PostMapping("/admin/create")
    public Result createAccount(@RequestBody AccountInfoDTO accountInfoDTO) {
        Long userId = authService.createAccount(accountInfoDTO);
        return Result.ok(userId.toString());
    }

    /**
     * [B端] 批量删除用户
     */
    @RequirePermission({"ADMIN"})
    @PostMapping("/admin/delete")
    public Result deleteUsers(@RequestBody UserIdListReqDTO req) {
        int affected = userService.deleteUsersByIds(req);
        return Result.ok(affected);
    }

    /**
     * [B端] 批量禁用用户
     */
    @RequirePermission({"ADMIN"})
    @PostMapping("/admin/disable")
    public Result disableUsers(@RequestBody UserIdListReqDTO req) {
        int affected = userService.disableUsersByIds(req);
        return Result.ok(affected);
    }

    /**
     * [B端] 批量激活用户
     */
    @RequirePermission({"ADMIN"})
    @PostMapping("/admin/activate")
    public Result activateUsers(@RequestBody UserIdListReqDTO req) {
        int affected = userService.activateUsersByIds(req);
        return Result.ok(affected);
    }

    /**
     * [B端] 分页获取所有类型用户信息
     */
    @RequirePermission({"ADMIN"})
    @PostMapping("/admin/page")
    public Result adminUserPage(@RequestBody AdminUserPageReqDTO req) {
        PageResult<AdminUserVO> page = userService.getAdminUserPage(req);
        return Result.ok(page);
    }

    /**
     * [B端] 修改用户信息
     */
    @RequirePermission({"ADMIN"})
    @PostMapping("/admin/update")
    public Result updateUser(@RequestBody AdminUserUpdateReqDTO req) {
        userService.updateUserByAdmin(req);
        return Result.ok();
    }

    /**
     * 重建用户名布隆过滤器
     */
    @RequirePermission({"ADMIN"})
    @PostMapping("/rebuild")
    public Result rebuildUsernameBloom() {
        long count = usernameBloomFilterService.rebuild();
        return Result.ok(count);
    }
}
