package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 手机号格式错误
            return Result.fail("手机号格式错误！");
        }

        // 生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 保存验证码到 Session
        session.setAttribute("code", code);

        // 发送验证码
        log.debug("发送验证码：{}", code);

        // 返回 ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 手机号格式错误
            return Result.fail("手机号格式错误！");
        }

        // 校验验证码
        Object cacheCode = session.getAttribute("code");
        String loginCode = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(loginCode)) {
            // 验证码错误
            return Result.fail("验证码错误");
        }

        // 根据手机号查询用户 select * from tb_user where phone = ?
        User user = lambdaQuery().eq(User::getPhone, phone).one();

        if (user == null) {
            // 用户不存在，创建用户
            user = createUserWithPhone(phone);
        }

        // 保存用户信息到 session
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

        return Result.ok("1");
    }

    private User createUserWithPhone(String phone) {
        // 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 保存用户
        save(user);
        return user;
    }
}
