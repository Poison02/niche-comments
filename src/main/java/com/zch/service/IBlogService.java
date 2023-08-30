package com.zch.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zch.dto.Result;
import com.zch.entity.Blog;

/**
 * @author Zch
 */
public interface IBlogService extends IService<Blog> {

    Result queryHotBlog(Integer current);

    Result queryBlogById(Long id);

    Result likeBlog(Long id);

    Result queryBlogLikes(Long id);

    Result saveBlog(Blog blog);

    Result queryBlogOfFollow(Long max, Integer offset);
}
