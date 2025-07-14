package com.hmdp.interceptor;

import com.hmdp.utils.UserHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {

        // 3. 用户是否存在
        if (UserHolder.getUser() == null) {
            // 4. 不存在，拦截，返回 401
            response.setStatus(401);
            return false;
        }

        // 8. 放行
         return true;
    }
}
