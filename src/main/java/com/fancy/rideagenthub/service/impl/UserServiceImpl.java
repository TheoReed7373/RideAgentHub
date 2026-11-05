package com.fancy.rideagenthub.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.domain.dto.AdminUserPageReqDTO;
import com.fancy.rideagenthub.domain.dto.AdminUserUpdateReqDTO;
import com.fancy.rideagenthub.domain.dto.SupportUserPageReqDTO;
import com.fancy.rideagenthub.domain.dto.UserCurrentUpdateReqDTO;
import com.fancy.rideagenthub.domain.dto.UserIdListReqDTO;
import com.fancy.rideagenthub.domain.dto.UserLocation;
import com.fancy.rideagenthub.domain.dto.UserPasswordResetReqDTO;
import com.fancy.rideagenthub.domain.entity.UserAuth;
import com.fancy.rideagenthub.domain.enums.UserRole;
import com.fancy.rideagenthub.domain.response.PageResult;
import com.fancy.rideagenthub.domain.vo.AdminUserVO;
import com.fancy.rideagenthub.domain.vo.SupportUserVO;
import com.fancy.rideagenthub.domain.vo.UserCurrentInfoVO;
import com.fancy.rideagenthub.exception.BusinessException;
import com.fancy.rideagenthub.mapper.UserAuthMapper;
import com.fancy.rideagenthub.security.UserTokenContext;
import com.fancy.rideagenthub.service.TokenService;
import com.fancy.rideagenthub.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserAuthMapper userAuthMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public PageResult<SupportUserVO> getSupportUserPage(SupportUserPageReqDTO req) {
	Page<UserAuth> page = new Page<>(req.getCurrent(), req.getSize());

	LambdaQueryWrapper<UserAuth> qw = new LambdaQueryWrapper<UserAuth>()
		.eq(UserAuth::getRole, UserRole.SUPPORT.name())
		.eq(UserAuth::getStatus, 1)
		.eq(UserAuth::getIsDeleted, 0);

	if (StringUtils.hasText(req.getKeyword())) {
	    qw.like(UserAuth::getUsername, req.getKeyword());
	}

	qw.orderByDesc(UserAuth::getUpdateTime);

	Page<UserAuth> result = userAuthMapper.selectPage(page, qw);

	List<SupportUserVO> records = result.getRecords().stream()
		.map(u -> SupportUserVO.<SupportUserVO>builder()
			.userId(u.getUserId() != null ? u.getUserId().toString() : null)
			.userName(u.getUsername())
			.build())
		.collect(Collectors.toList());

	return PageResult.<SupportUserVO>builder()
		.page((int) result.getCurrent())
		.size((int) result.getSize())
		.total(result.getTotal())
		.records(records)
		.build();
    }

	@Override
	public PageResult<AdminUserVO> getAdminUserPage(AdminUserPageReqDTO req) {
		if (req == null) {
			throw new BusinessException(400, "请求不能为空");
		}

		Page<UserAuth> page = new Page<>(req.getCurrent(), req.getSize());
		boolean queryDeleted = Boolean.TRUE.equals(req.getDeleted());
		LambdaQueryWrapper<UserAuth> qw = new LambdaQueryWrapper<UserAuth>()
				.eq(UserAuth::getIsDeleted, queryDeleted ? 1 : 0);

		if (StringUtils.hasText(req.getUsername())) {
			qw.like(UserAuth::getUsername, req.getUsername());
		}

		if (StringUtils.hasText(req.getRole())) {
			UserRole role = parseRole(req.getRole());
			qw.eq(UserAuth::getRole, role.name());
		}

		qw.orderByDesc(UserAuth::getUpdateTime);
		Page<UserAuth> result = userAuthMapper.selectPage(page, qw);

		List<AdminUserVO> records = result.getRecords().stream()
				.map(u -> AdminUserVO.builder()
						.userId(u.getUserId() != null ? u.getUserId().toString() : null)
						.userName(u.getUsername())
						.email(u.getEmail())
						.role(u.getRole())
						.status(u.getStatus())
						.createTime(u.getCreateTime())
						.build())
				.collect(Collectors.toList());

		return PageResult.<AdminUserVO>builder()
				.page((int) result.getCurrent())
				.size((int) result.getSize())
				.total(result.getTotal())
				.records(records)
				.build();
	}

	@Override
	public int deleteUsersByIds(UserIdListReqDTO req) {
		List<Long> userIds = requireUserIds(req);
		int affected = userAuthMapper.update(null,
				new LambdaUpdateWrapper<UserAuth>()
						.in(UserAuth::getUserId, userIds)
						.eq(UserAuth::getIsDeleted, 0)
						.set(UserAuth::getIsDeleted, 1)
						.set(UserAuth::getStatus, 0)
						.set(UserAuth::getUpdateTime, LocalDateTime.now()));
		if (affected > 0) {
			userIds.forEach(tokenService::deleteAllTokensByUserId);
		}
		return affected;
	}

	@Override
	public int disableUsersByIds(UserIdListReqDTO req) {
		List<Long> userIds = requireUserIds(req);
		int affected = userAuthMapper.update(null,
				new LambdaUpdateWrapper<UserAuth>()
						.in(UserAuth::getUserId, userIds)
						.eq(UserAuth::getIsDeleted, 0)
						.set(UserAuth::getStatus, 0)
						.set(UserAuth::getUpdateTime, LocalDateTime.now()));
		if (affected > 0) {
			userIds.forEach(tokenService::deleteAllTokensByUserId);
		}
		return affected;
	}

	@Override
	public int activateUsersByIds(UserIdListReqDTO req) {
		List<Long> userIds = requireUserIds(req);
		return userAuthMapper.update(null,
				new LambdaUpdateWrapper<UserAuth>()
						.in(UserAuth::getUserId, userIds)
						.set(UserAuth::getStatus, 1)
						.set(UserAuth::getIsDeleted, 0)
						.set(UserAuth::getUpdateTime, LocalDateTime.now()));
	}

	@Override
	public void updateUserByAdmin(AdminUserUpdateReqDTO req) {
		if (req == null) {
			throw new BusinessException(400, "请求不能为空");
		}
		String userIdStr = req.getUserId();
		if (!StringUtils.hasText(userIdStr)) {
			throw new BusinessException(400, "用户ID不能为空");
		}
		Long userId = parseUserId(userIdStr.trim());

		int updateFields = countUpdateFields(req);
		if (updateFields == 0) {
			throw new BusinessException(400, "至少传一个可修改字段");
		}

		UserAuth existing = getUserById(userId);
		if (existing == null) {
			throw new BusinessException(404, "用户不存在");
		}

		String targetRole = existing.getRole();
		boolean roleChanged = false;
		if (StringUtils.hasText(req.getRole())) {
			UserRole role = parseRole(req.getRole());
			targetRole = role.name();
			roleChanged = existing.getRole() == null || !targetRole.equalsIgnoreCase(existing.getRole());
		}

		String targetUsername = existing.getUsername();
		boolean usernameChanged = false;
		if (StringUtils.hasText(req.getUsername())) {
			String username = normalizeUsername(req.getUsername());
			targetUsername = username;
			usernameChanged = existing.getUsername() == null || !username.equals(existing.getUsername());
		}

		String targetEmail = existing.getEmail();
		boolean emailChanged = false;
		if (StringUtils.hasText(req.getEmail())) {
			String email = normalizeEmail(req.getEmail());
			targetEmail = email;
			String existingEmail = existing.getEmail() != null ? existing.getEmail().toLowerCase() : null;
			emailChanged = existingEmail == null || !email.equalsIgnoreCase(existingEmail);
		}

		boolean passwordChanged = false;
		if (StringUtils.hasText(req.getPassword())) {
			String rawPassword = req.getPassword();
			String existingPassword = existing.getPassword();
			passwordChanged = existingPassword == null || !passwordEncoder.matches(rawPassword, existingPassword);
		}

		boolean anyChanged = usernameChanged || emailChanged || passwordChanged || roleChanged;
		if (!anyChanged) {
			return;
		}

		if (usernameChanged || roleChanged) {
			UserAuth duplicate = userAuthMapper.selectOne(new LambdaQueryWrapper<UserAuth>()
					.eq(UserAuth::getUsername, targetUsername)
					.eq(UserAuth::getRole, targetRole)
					.eq(UserAuth::getIsDeleted, 0));
			if (duplicate != null && !duplicate.getUserId().equals(userId)) {
				throw new BusinessException(409, "该用户名已存在");
			}
		}

		if (emailChanged) {
			UserAuth duplicate = userAuthMapper.selectOne(new LambdaQueryWrapper<UserAuth>()
					.eq(UserAuth::getEmail, targetEmail)
					.eq(UserAuth::getIsDeleted, 0));
			if (duplicate != null && !duplicate.getUserId().equals(userId)) {
				throw new BusinessException(409, "该邮箱已存在");
			}
		}

		LambdaUpdateWrapper<UserAuth> update = new LambdaUpdateWrapper<UserAuth>()
				.eq(UserAuth::getUserId, userId)
				.eq(UserAuth::getIsDeleted, 0)
				.set(UserAuth::getUpdateTime, LocalDateTime.now());
		if (usernameChanged) {
			update.set(UserAuth::getUsername, targetUsername);
		}
		if (emailChanged) {
			update.set(UserAuth::getEmail, targetEmail);
		}
		if (passwordChanged) {
			update.set(UserAuth::getPassword, passwordEncoder.encode(req.getPassword()));
		}
		if (roleChanged) {
			update.set(UserAuth::getRole, targetRole);
		}

		userAuthMapper.update(null, update);

		if (passwordChanged || roleChanged) {
			tokenService.deleteAllTokensByUserId(userId);
		}
	}

	@Override
	public void saveUserLocation(String userId, UserLocation location) {
		String key = RedisKeyConstants.userLocKey(userId);
		stringRedisTemplate.opsForHash().put(key, "latitude", location.getLatitude());
		stringRedisTemplate.opsForHash().put(key, "longitude", location.getLongitude());
		stringRedisTemplate.opsForHash().put(key, "address", location.getAddress());
	}

	@Override
    public UserLocation getUserLocation(String userId) {
        String key = RedisKeyConstants.userLocKey(userId);
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
        if (entries.isEmpty()) {
            return null;
		}
		return UserLocation.builder()
				.latitude(entries.get("latitude") != null ? entries.get("latitude").toString() : null)
				.longitude(entries.get("longitude") != null ? entries.get("longitude").toString() : null)
                .address(entries.get("address") != null ? entries.get("address").toString() : null)
                .build();
    }

    @Override
    public UserCurrentInfoVO getCurrentUserInfo() {
        Long userId = UserTokenContext.getUserIdInLong();
        UserAuth user = getUserById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        return UserCurrentInfoVO.builder()
                .userId(user.getUserId() != null ? user.getUserId().toString() : null)
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole())
                .lastLoginTime(user.getLastLoginTime())
                .createTime(user.getCreateTime())
                .build();
    }

    @Override
    public void updateCurrentUserInfo(UserCurrentUpdateReqDTO req) {
        if (req == null) {
            throw new BusinessException(400, "请求不能为空");
        }

        boolean usernameProvided = StringUtils.hasText(req.getUsername());
        boolean emailProvided = StringUtils.hasText(req.getEmail());
        if (!usernameProvided && !emailProvided) {
            throw new BusinessException(400, "至少传一个可修改字段");
        }

        Long userId = UserTokenContext.getUserIdInLong();
        UserAuth existing = getUserById(userId);
        if (existing == null) {
            throw new BusinessException(404, "用户不存在");
        }

        String targetUsername = existing.getUsername();
        boolean usernameChanged = false;
        if (usernameProvided) {
            String username = normalizeUsername(req.getUsername());
            targetUsername = username;
            usernameChanged = existing.getUsername() == null || !username.equals(existing.getUsername());
        }

        String targetEmail = existing.getEmail();
        boolean emailChanged = false;
        if (emailProvided) {
            String email = normalizeEmail(req.getEmail());
            targetEmail = email;
            String existingEmail = existing.getEmail() != null ? existing.getEmail().toLowerCase() : null;
            emailChanged = existingEmail == null || !email.equalsIgnoreCase(existingEmail);
        }

        if (!usernameChanged && !emailChanged) {
            return;
        }

        if (usernameChanged) {
            UserAuth duplicate = userAuthMapper.selectOne(new LambdaQueryWrapper<UserAuth>()
                    .eq(UserAuth::getUsername, targetUsername)
                    .eq(UserAuth::getRole, existing.getRole())
                    .eq(UserAuth::getIsDeleted, 0));
            if (duplicate != null && !duplicate.getUserId().equals(userId)) {
                throw new BusinessException(409, "该用户名已存在");
            }
        }

        if (emailChanged) {
            UserAuth duplicate = userAuthMapper.selectOne(new LambdaQueryWrapper<UserAuth>()
                    .eq(UserAuth::getEmail, targetEmail)
                    .eq(UserAuth::getIsDeleted, 0));
            if (duplicate != null && !duplicate.getUserId().equals(userId)) {
                throw new BusinessException(409, "该邮箱已存在");
            }
        }

        LambdaUpdateWrapper<UserAuth> update = new LambdaUpdateWrapper<UserAuth>()
                .eq(UserAuth::getUserId, userId)
                .eq(UserAuth::getIsDeleted, 0)
                .set(UserAuth::getUpdateTime, LocalDateTime.now());
        if (usernameChanged) {
            update.set(UserAuth::getUsername, targetUsername);
        }
        if (emailChanged) {
            update.set(UserAuth::getEmail, targetEmail);
        }
        userAuthMapper.update(null, update);
    }

    @Override
    public void resetCurrentUserPassword(UserPasswordResetReqDTO req) {
        if (req == null) {
            throw new BusinessException(400, "请求不能为空");
        }
        if (!StringUtils.hasText(req.getPassword())) {
            throw new BusinessException(400, "密码不能为空");
        }

        Long userId = UserTokenContext.getUserIdInLong();
        UserAuth existing = getUserById(userId);
        if (existing == null) {
            throw new BusinessException(404, "用户不存在");
        }

        String rawPassword = req.getPassword();
        if (existing.getPassword() != null && passwordEncoder.matches(rawPassword, existing.getPassword())) {
            throw new BusinessException(400, "新密码不能与原密码一致");
        }

        userAuthMapper.update(null, new LambdaUpdateWrapper<UserAuth>()
                .eq(UserAuth::getUserId, userId)
                .eq(UserAuth::getIsDeleted, 0)
                .set(UserAuth::getPassword, passwordEncoder.encode(rawPassword))
                .set(UserAuth::getUpdateTime, LocalDateTime.now()));
    }

	private List<Long> requireUserIds(UserIdListReqDTO req) {
		if (req == null || req.getUserIds() == null || req.getUserIds().isEmpty()) {
			throw new BusinessException(400, "用户ID列表不能为空");
		}
		List<Long> userIds = req.getUserIds().stream()
				.filter(StringUtils::hasText)
				.map(String::trim)
				.map(this::parseUserId)
				.distinct()
				.collect(Collectors.toList());
		if (userIds.isEmpty()) {
			throw new BusinessException(400, "用户ID列表不能为空");
		}
		return userIds;
	}

	private UserAuth getUserById(Long userId) {
		return userAuthMapper.selectOne(new LambdaQueryWrapper<UserAuth>()
				.eq(UserAuth::getUserId, userId)
				.eq(UserAuth::getIsDeleted, 0));
	}

	private int countUpdateFields(AdminUserUpdateReqDTO req) {
		int count = 0;
		if (StringUtils.hasText(req.getUsername())) {
			count++;
		}
		if (StringUtils.hasText(req.getEmail())) {
			count++;
		}
		if (StringUtils.hasText(req.getPassword())) {
			count++;
		}
		if (StringUtils.hasText(req.getRole())) {
			count++;
		}
		return count;
	}

	private String normalizeUsername(String username) {
		if (!StringUtils.hasText(username)) {
			throw new BusinessException(400, "用户名不能为空");
		}
		String normalized = username.trim();
		if (normalized.isEmpty()) {
			throw new BusinessException(400, "用户名不能为空");
		}
		if (normalized.contains("@")) {
			throw new BusinessException(400, "用户名不能包含@符号");
		}
		return normalized;
	}

	private String normalizeEmail(String email) {
		if (!StringUtils.hasText(email)) {
			throw new BusinessException(400, "邮箱不能为空");
		}
		String normalized = email.trim().toLowerCase();
		if (!normalized.contains("@") || !normalized.contains(".")) {
			throw new BusinessException(400, "邮箱格式不正确");
		}
		return normalized;
	}

	private UserRole parseRole(String role) {
		try {
			return UserRole.fromString(role);
		} catch (IllegalArgumentException e) {
			throw new BusinessException(400, "角色不合法: " + role, e);
		}
	}

	private Long parseUserId(String userId) {
		try {
			return Long.parseLong(userId);
		} catch (NumberFormatException e) {
			throw new BusinessException(400, "用户ID不合法: " + userId, e);
		}
	}
}
