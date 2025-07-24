package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.constant.RedisConstants;
import com.hmdp.constant.SystemConstants;
import com.hmdp.model.dto.Result;
import com.hmdp.model.dto.UserDTO;
import com.hmdp.model.entity.Blog;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.model.entity.User;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;


@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

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
        Boolean isLiked = this.isLike(blog);

        // 3. 如果未点赞，可以点赞
        if(!isLiked) {
            // 3.1 数据库点赞 + 1
            boolean success = this.update()
                    .setSql("liked = liked + 1").eq("id", id).update();
            // 3.2 保存用户 id 到 Redis 的 Set 集合
            if(success) {
                stringRedisTemplate.opsForSet().add(RedisConstants.BLOG_LIKED_KEY + id, userId.toString());
            }
        } else {
            // 4. 已经点赞，取消点赞
            // 4.1 数据库点赞 + 1
            boolean success = this.update()
                    .setSql("liked = liked - 1").eq("id", id).update();
            // 4.2 用户 id 从 Set 集合移除
            if(success) {
                stringRedisTemplate.opsForSet().remove(RedisConstants.BLOG_LIKED_KEY + id, userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 点赞信息查询
     */
    private Boolean isLike(Blog blog) {
        // 1. 获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2. 判断当前登录用户是否已经点赞
        String blogKey = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Boolean isLiked = stringRedisTemplate.opsForSet().isMember(blogKey, userId.toString());
        return Boolean.TRUE.equals(isLiked);
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
