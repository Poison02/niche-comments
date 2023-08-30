package com.zch.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zch.dto.Result;
import com.zch.entity.Follow;

/**
 * @author Zch
 */
public interface IFollowService extends IService<Follow> {

    Result follow(Long followUserId, Boolean isFollow);

    Result isFollow(Long followUserId);

    Result followCommons(Long id);
}
