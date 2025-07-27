package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.constant.RedisConstants;
import com.hmdp.constant.SystemConstants;
import com.hmdp.model.dto.Result;
import com.hmdp.model.dto.ScrollResult;
import com.hmdp.model.dto.UserDTO;
import com.hmdp.model.entity.Blog;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.model.entity.Follow;
import com.hmdp.model.entity.User;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result getBlogById(Long id) {
        // 1. 查询 Blog
        Blog blog = this.getById(id);
        if(blog == null) {
            return Result.fail("Blog 不存在");
        }
        // 2. 设置用户信息
        this.fillUserInfo(blog);
        // 3. 点赞信息查询
        this.isLike(blog);
        return Result.ok(blog);
    }
    

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 填充用户信息
        // 点赞信息查询
        records.forEach(blog -> {
            this.fillUserInfo(blog);
            this.isLike(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result doLikeBlog(Long id) {
        // 1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2 查询当前用户是否对该 blog 点赞
        Blog blog = this.getById(id);
        this.isLike(blog);

        // 3. 如果未点赞，可以点赞
        if(!blog.getIsLike()) {
            // 3.1 数据库点赞 + 1
            boolean success = this.update()
                    .setSql("liked = liked + 1").eq("id", id).update();
            // 3.2 保存用户 id 到 Redis 的 ZSet 集合 当前时间戳作为事件
            if(success) {
                stringRedisTemplate.opsForZSet().add(RedisConstants.BLOG_LIKED_KEY + id,
                        userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 4. 已经点赞，取消点赞
            // 4.1 数据库点赞 + 1
            boolean success = this.update()
                    .setSql("liked = liked - 1").eq("id", id).update();
            // 4.2 用户 id 从 Set 集合移除
            if(success) {
                stringRedisTemplate.opsForZSet().remove(RedisConstants.BLOG_LIKED_KEY + id, userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 获取帖子点赞 TopN
     */
    @Override
    public Result getLikeTopN(Long id) {
        // 1. 查询 top5 的点赞用户 ZRANGE key start end
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(RedisConstants.BLOG_LIKED_KEY + id, 0, 4);
        // 2. 解析出用户 id
        List<Long> userIds = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        if(userIds.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 3. 根据 id 查询用户
        // where id IN (1010,1) order by field(id, 1010, 1)
        String idStr = StrUtil.join("," , userIds);
        List<User> userList = userService.query()
                .in("id",userIds).last("order by field(id, +" + idStr + ")").list();
        // 4. 返回脱敏后的用户信息
        List<UserDTO> userDTOList = userList.stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOList);
    }

    /**
     * 发布 Blog
     * 推送给粉丝
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 1. 获取当前登录用户（发布的肯定是当前用户）
        Long userId = UserHolder.getUser().getId();
        // 2. 发布 Blog（保存到数据库）
        blog.setUserId(userId);
        boolean save = this.save(blog);

        if(!save) {
            return Result.fail("发送Blog失败，请重试");
        }
        // 3. 查询关注当前的所有用户
        List<Follow> followList = followService.query()
                .eq("follow_user_id", userId).list();
        // 4. 建立收件箱，推送 BlogId 给粉丝
        for (Follow follow : followList) {
            // 获取粉丝 Id
            Long fanId = follow.getUserId();
            // 推送 BlogId
            stringRedisTemplate.opsForZSet().add(RedisConstants.FEED_KEY + fanId,
                    blog.getId().toString(), System.currentTimeMillis());
        }
        // 5. 返回 BlogId
        return Result.ok(blog.getId());
    }

    /**
     * 被关注的人 Blog 查询
     */
    @Override
    public Result getBlogOfFollow(Long minTime, Integer offset) {
        // 1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2. 拿到收件箱的 key
        String key = RedisConstants.FEED_KEY + userId;
            //a. 查询收件箱，执行指令 ZREVRANGEBYSCORE z1 6 0 WITHSCORES LIMIT 1 3
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, minTime, offset, 3);
            //b. 非空判断
        if(typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 3. 解析数据：blogId score(时间戳 minTime) offset
        List<Long> blogIdList = new ArrayList<>(typedTuples.size());
        long minTimeValue = 0;
        int cntMinTime = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            blogIdList.add(Long.valueOf(tuple.getValue()));
            if(tuple.getScore().longValue() == minTimeValue) {
                cntMinTime++;
            } else {
                minTimeValue = tuple.getScore().longValue();
                cntMinTime = 1;
            }
        }
        // 4. 根据 blogId 拿到 Blog
            // a. 要保证有序，listByIds用的是 Mysql 的IN无序，我们是Order By Field
        String idStr = StrUtil.join(",", blogIdList);
        List<Blog> blogList = this.query()
                .in("id", blogIdList).last("order by field(id, " + idStr + ")").list();
        // b. 查询博客，还要附带它的 用户信息，以及当前用户是否点赞
        for (Blog blog : blogList) {
            fillUserInfo(blog);
            isLike(blog);
        }
        // 5. 封装并且返回
        ScrollResult result = new ScrollResult();
        result.setList(blogList);
        result.setMinTime(minTimeValue);
        result.setOffset(cntMinTime);
        return Result.ok(result);
    }

    /**
     * 当前用户是否点赞查询
     */
    private void isLike(Blog blog) {
        // 1. 获取登录用户
        UserDTO user = UserHolder.getUser();
        if(user == null) {
            return;
        }
        Long userId = UserHolder.getUser().getId();
        // 2. 判断当前登录用户是否已经点赞
        String blogKey = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(blogKey, userId.toString());
        // 设置是否点赞
        blog.setIsLike(score != null);
    }

    /**
     * 填充 blog 中的用户信息
     * @param blog
     */
    private void fillUserInfo(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
