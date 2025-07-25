package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.constant.RedisConstants;
import com.hmdp.model.dto.Result;
import com.hmdp.model.dto.UserDTO;
import com.hmdp.model.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.model.entity.User;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1. 获取当前登录用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        // 2. 判断是否关注了
        if(isFollow) {
            // 3. 关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            follow.setCreateTime(LocalDateTime.now());
            boolean res = this.save(follow);
            if(res) {
                // 将关注用户放入 Set 集合
                stringRedisTemplate.opsForSet().add(RedisConstants.COMMOIN_FOLLOW_KEY + userId, followUserId.toString());
            }
        } else {
            // 4. 取关，删除数据 delete from tb_follow where user_id = ? and follow_user_id = ?
            QueryWrapper<Follow> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("user_id", userId)
                    .eq("follow_user_id",followUserId);
            boolean res = this.remove(queryWrapper);
            if(res) {
                // 将关注用户移除
                stringRedisTemplate.opsForSet().remove(RedisConstants.COMMOIN_FOLLOW_KEY + userId, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        // 1. 获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2. 查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Integer count = this.query()
                .eq("user_id", userId)
                .eq("follow_user_id", followUserId)
                .count();
        return Result.ok(count > 0);
    }

    /**
     * 求共同关注
     */
    @Override
    public Result getCommonFollowUsers(Long targetUserId) {
        // 1. 获取登录用户关注 key
        Long userId = UserHolder.getUser().getId();
        String userKey = RedisConstants.COMMOIN_FOLLOW_KEY + userId;
        // 2. 获取目标用户关注 key
        String targetKey = RedisConstants.COMMOIN_FOLLOW_KEY + targetUserId;
        // 3. 求关注列表交集
        Set<String> strIds = stringRedisTemplate.opsForSet().intersect(userKey, targetKey);
        // 4. 返回共同关注的用户列表
        List<Long> userIds = strIds.stream().map(Long::valueOf).collect(Collectors.toList());
        List<User> userList = userService.listByIds(userIds);
        List<UserDTO> userDTOList = userList.stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOList);
    }
}
