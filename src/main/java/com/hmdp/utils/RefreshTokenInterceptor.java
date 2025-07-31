package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * ClassName: RefreshTokenInterceptor
 * Package: com.hmdp.utils
 */
@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.從 request header 中獲取 token
        String token = request.getHeader("authorization");
        if (token == null) {
            return true;
        }
        //2.根據 token 去 redis 獲取 user
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash()
                .entries(LOGIN_USER_KEY + token);

        //3.判斷用戶是否存在
        if (userMap.isEmpty()) {
            //4. 不存在，直接放行
            return true;
        }
        //5.將查詢到的user hashMap 轉為 userDTO Object
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        //6.存在，保存到 ThreadLocal 以便後續使用
        UserHolder.saveUser(userDTO);
        //7.刷新 token 有效時間
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.SECONDS);
        //8.放行
        return HandlerInterceptor.super.preHandle(request, response, handler);
    }
}
