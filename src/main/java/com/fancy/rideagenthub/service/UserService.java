package com.fancy.rideagenthub.service;

import com.fancy.rideagenthub.domain.dto.AdminUserPageReqDTO;
import com.fancy.rideagenthub.domain.dto.AdminUserUpdateReqDTO;
import com.fancy.rideagenthub.domain.dto.SupportUserPageReqDTO;
import com.fancy.rideagenthub.domain.dto.UserCurrentUpdateReqDTO;
import com.fancy.rideagenthub.domain.dto.UserIdListReqDTO;
import com.fancy.rideagenthub.domain.dto.UserLocation;
import com.fancy.rideagenthub.domain.dto.UserPasswordResetReqDTO;
import com.fancy.rideagenthub.domain.response.PageResult;
import com.fancy.rideagenthub.domain.vo.AdminUserVO;
import com.fancy.rideagenthub.domain.vo.SupportUserVO;
import com.fancy.rideagenthub.domain.vo.UserCurrentInfoVO;

public interface UserService {

	/**
	 * 分页获取所有客服用户（支持 username 模糊搜索）
	 */
	PageResult<SupportUserVO> getSupportUserPage(SupportUserPageReqDTO req);

	/**
	 * 分页获取所有类型用户信息
	 */
	PageResult<AdminUserVO> getAdminUserPage(AdminUserPageReqDTO req);

	/**
	 * 批量删除用户
	 */
	int deleteUsersByIds(UserIdListReqDTO req);

	/**
	 * 批量禁用用户
	 */
	int disableUsersByIds(UserIdListReqDTO req);

	/**
	 * 批量激活用户
	 */
	int activateUsersByIds(UserIdListReqDTO req);

	/**
	 * 修改用户信息
	 */
	void updateUserByAdmin(AdminUserUpdateReqDTO req);

	/**
	 * 保存用户定位信息
	 */
	void saveUserLocation(String userId, UserLocation location);

	/**
	 * 获取用户定位信息
	 */
	UserLocation getUserLocation(String userId);

	/**
	 * 获取当前登录用户信息
	 */
	UserCurrentInfoVO getCurrentUserInfo();

	/**
	 * 修改当前登录用户信息
	 */
	void updateCurrentUserInfo(UserCurrentUpdateReqDTO req);

	/**
	 * 当前用户重置密码
	 */
	void resetCurrentUserPassword(UserPasswordResetReqDTO req);
}
