package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FOLLOWS_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        String key = FOLLOWS_KEY + userId;
        if (isFollow) {
            // 关注
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            // 保存数据库
            boolean isSuccess = save(follow);
            if (isSuccess) {
                // 保存到Redis缓存
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            // 取关，delete from tb_follow where userId = ? and follow_user_id = ?
            boolean isSuccess = remove(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getFollowUserId, followUserId)
                    .eq(Follow::getUserId, userId));
            if (isSuccess) {
                // 从Redis缓存中移除
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 查询
        Long count = lambdaQuery().eq(Follow::getFollowUserId, followUserId)
                .eq(Follow::getUserId, userId).count();
        // 判断
        return Result.ok(count > 0);
    }

    @Override
    public Result togetherFollow(Long id) {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 查询Redis
        Set<String> userIds = stringRedisTemplate.opsForSet().intersect(FOLLOWS_KEY + id, FOLLOWS_KEY + userId);
        if (userIds == null || userIds.isEmpty()) {
            // 没有共同关注
            return Result.ok(Collections.emptyList());
        }
        // 解析用户id
        List<Long> ids = userIds.stream().map(Long::valueOf).collect(Collectors.toList());
        // 根据id查询用户
        List<UserDTO> users = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
