package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 获取用户
        UserDTO user = UserHolder.getUser();

        if (user == null) {
            // 没有登录
            response.setStatus(401);
            return false;
        }

        // 放行
        return true;
    }
}
