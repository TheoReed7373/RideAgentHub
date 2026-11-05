package com.fancy.rideagenthub.service.base;

import com.fancy.rideagenthub.constant.RedisKeyConstants;
import com.fancy.rideagenthub.mapper.UserAuthMapper;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserUsernameBloomFilterService {

    private static final long EXPECTED_INSERTIONS = 1000000L;
    private static final double FALSE_PROBABILITY = 0.01;

    private final RedissonClient redissonClient;
    private final UserAuthMapper userAuthMapper;

    public UserUsernameBloomFilterService(RedissonClient redissonClient, UserAuthMapper userAuthMapper) {
        this.redissonClient = redissonClient;
        this.userAuthMapper = userAuthMapper;
    }

    public boolean mightContain(String username) {
        if (!StringUtils.hasText(username)) {
            return false;
        }
        return getBloomFilter().contains(username);
    }

    public void addUsername(String username) {
        if (!StringUtils.hasText(username)) {
            return;
        }
        getBloomFilter().add(username);
    }

    public long rebuild() {
        RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter(RedisKeyConstants.userUsernameBloomKey());
        bloomFilter.delete();
        bloomFilter.tryInit(EXPECTED_INSERTIONS, FALSE_PROBABILITY);

        List<String> usernames = userAuthMapper.selectUsernamesForBloom();
        if (usernames == null) {
            return 0;
        }
        List<String> normalized = usernames.stream()
                .map(value -> value == null ? null : value.trim())
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.toList());

        for (String username : normalized) {
            bloomFilter.add(username);
        }

        return normalized.size();
    }

    private RBloomFilter<String> getBloomFilter() {
        RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter(RedisKeyConstants.userUsernameBloomKey());
        bloomFilter.tryInit(EXPECTED_INSERTIONS, FALSE_PROBABILITY);
        return bloomFilter;
    }
}
