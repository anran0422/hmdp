package com.hmdp.service;

import com.hmdp.model.dto.Result;
import com.hmdp.model.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IBlogService extends IService<Blog> {

    Result getBlogById(Long id);

    Result queryHotBlog(Integer current);

    Result doLikeBlog(Long id);

    /**
     * 获取帖子点赞 TopN
     */
    Result getLikeTopN(Long id);

    Result saveBlog(Blog blog);
}
