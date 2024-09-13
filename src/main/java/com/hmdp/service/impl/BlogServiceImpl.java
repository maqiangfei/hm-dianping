package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
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

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;
import static com.hmdp.utils.SystemConstants.DEFAULT_PAGE_SIZE;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    /**
     * 根据id查询blog
     * @param id 博客id
     * @return 博客
     */
    @Override
    public Result queryBlogById(Long id) {
        // 查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        // 设置用户信息
        queryBlogUser(blog);
        // 设置blog是否被点赞了
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 根据点赞数查询热门博客
     * @param current 页数
     * @return 博客集合
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 设置用户信息
        records.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 点赞｜取消点赞博客
     * @param id 博客id
     * @return 结果
     */
    @Override
    public Result likeBlog(Long id) {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + id;
        // 判断用户是否点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // 用户没点赞，点赞数+1
            boolean isSuccess = lambdaUpdate().setSql("liked = liked + 1")
                    .eq(Blog::getId, id).update();
            if (isSuccess) {
                // 将用户添加进缓存的bolg点赞set集合 zadd key value score
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 用户点赞了，点赞数-1
            boolean isSuccess = lambdaUpdate().setSql("liked = liked - 1")
                    .eq(Blog::getId, id).update();
            // 将用户从缓存的bolg点赞set集合移除
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        // 查询top5点赞用户 zrange key 0 4
        Set<String> userIds = stringRedisTemplate.opsForZSet().range(BLOG_LIKED_KEY + id, 0, 4);
        if (userIds == null || userIds.isEmpty()) {
            // 没有点赞
            return Result.ok(Collections.emptyList());
        }
        // 解析出其中的用户id
        List<Long> ids = userIds.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // 根据用户id查询用户 注意：in()不会根据里面的顺序返回，where id in (5, 1) order by FIELD(id, 5, 1)
        List<UserDTO> users = userService.lambdaQuery()
                .in(User::getId, ids).last("order by field(id, " + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 返回
        return Result.ok(users);
    }

    @Override
    public Result queryBlogByUserId(Long id, Integer current) {
        // 查询
        Page<Blog> page = lambdaQuery().eq(Blog::getUserId, id)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            // 保存笔记失败
            Result.fail("保存笔记失败");
        }
        // 查询所有粉丝 select * from tb_follow where follow_user_id = ?
        List<Follow> fans = followService.lambdaQuery().eq(Follow::getFollowUserId, user.getId()).list();
        for (Follow fan : fans) {
            Long userId = fan.getUserId();
            // 推送给粉丝
            stringRedisTemplate.opsForZSet().add(FEED_KEY + userId, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 查询feed收件箱 ZREVRANGEBYSCORE key max min WITHSCORES LIMIT offset count
        Set<ZSetOperations.TypedTuple<String>> entries = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(FEED_KEY + userId, 0, max, offset, DEFAULT_PAGE_SIZE);
        if (entries == null || entries.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 解析数据：blogId minTime offset
        List<Long> ids = new ArrayList<>(entries.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> entry : entries) {
            // 获取笔记id
            ids.add(Long.valueOf(entry.getValue()));
            // 获取最小分数
            long time = entry.getScore().longValue();
            if (time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }
        String idStr = StrUtil.join(",", ids);
        // 查询笔记
        List<Blog> blogs = lambdaQuery().in(Blog::getId, ids)
                .last("ORDER BY FIELD(id, " + idStr + ")").list();
        for (Blog blog : blogs) {
            // 查询用户信息
            queryBlogUser(blog);
            // 判断笔记是否被当前用户点赞
            isBlogLiked(blog);
        }
        // 封装结果返回
        ScrollResult scrollResult = new ScrollResult(blogs, minTime, os);
        return Result.ok(scrollResult);
    }

    /**
     * 给博客设置作者信息
     * @param blog 博客
     */
    private void queryBlogUser(Blog blog) {
        // 查询用户
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        // 设置用户信息
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * 给博客设置是否喜欢字段
     * @param blog 博客
     */
    private void isBlogLiked(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录
            return;
        }
        Long userId = user.getId();
        // 判断用户是否点赞
        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + blog.getId(), userId.toString());
        blog.setIsLike(score != null);
    }
}
