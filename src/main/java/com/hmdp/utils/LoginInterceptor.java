package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * ClassName: LoginInterceptor
 * Package: com.hmdp.utils
 */
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.獲取 session
        HttpSession session = request.getSession();
        //2.獲取 session 中的用戶
        Object user = session.getAttribute("user");
        //3.判斷用戶是否存在
        if (user == null) {
            //4. 不存在，攔截
            response.setStatus(401);
            return false;
        }
        //5.存在，保存到 ThreadLocal 以便後續使用
        UserHolder.saveUser((UserDTO) user);
        //6.放行
        return HandlerInterceptor.super.preHandle(request, response, handler);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
        HandlerInterceptor.super.afterCompletion(request, response, handler, ex);
    }
}
