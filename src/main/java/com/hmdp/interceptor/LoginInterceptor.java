package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.utils.UserHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 1. 获取 session
        HttpSession session = request.getSession();

        // 2. 获取 session 中用户
        UserDTO user = (UserDTO) session.getAttribute("user");

        // 3. 用户是否存在
        if (user == null) {
            // 4. 不存在，拦截，返回 401
            response.setStatus(401);
            return false;
        }
        // 5. 存在，保存用户信息到 ThreadLocal
        UserHolder.saveUser(BeanUtil.copyProperties(user, UserDTO.class));

        // 6. 放行
         return true;
    }
}
