package com.hmdp.service;

import com.hmdp.model.dto.Result;
import com.hmdp.model.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IFollowService extends IService<Follow> {

    Result follow(Long followUserId, Boolean isFollow);

    Result isFollow(Long followUserId);

    Result getCommonFollowUsers(Long targetUserId);
}
