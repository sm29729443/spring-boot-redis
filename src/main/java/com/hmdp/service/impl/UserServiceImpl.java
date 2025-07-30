package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexPatterns;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static com.hmdp.utils.RedisConstants.*;

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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校驗手機號，這裡的校驗是指是否滿足手機號碼格式
        // RegexUtils 驗證的是中國手機號格式
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回錯誤訊息
            return Result.fail("手機號格式不正確");
        }
        // 3.如果符合，生成驗證碼
        String code = RandomUtil.randomNumbers(6);
        // 4.保存驗證碼到redis，SET KEY VALUE EX 2 MINUTES
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 5.發送驗證碼給user
        // 這邊照理說要接 google mail 等第三方 API，因為不是重點故跳過，只用 log 紀錄
        // 一般發送驗證碼，在公司都會有個獨立的服務去做，只要調用那個服務即可
        log.debug("發送驗證碼成功, 驗證碼:{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校驗手機號
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手機號格式不正確");
        }
        // 2. 從 redis 獲取驗證碼並校驗驗證碼
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + loginForm.getPhone());
        if ((code == null || !code.equals(loginForm.getCode()))) {
            // 3. 驗證碼不一致，返回錯誤訊息
            return Result.fail("驗證碼錯誤");
        }
        // 4. 驗證碼一致，根據手機號查詢用戶:SELECT * FROM tb_user WHERE phone = ?
        User user = query().eq("phone", loginForm.getPhone()).one();
        // 5. 判斷用戶是否存在
        if (user == null) {
            // 6. 用戶不存在，創建新用戶並保存
            user = createUserWithPhone(loginForm.getPhone());
        }
        // 7. 保存用戶到 redis 中
        // 7.1 隨機生成 token，做為登入令牌
        String token = UUID.randomUUID().toString(true);
        // 7.2 將 user 物件轉為 hashMap 儲存
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 7.3 儲存
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        // 7.4 設定 token 有效時間
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.SECONDS);
        // 8. 返回 token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        // 1. 創建用戶
        User user = new User();
        user.setPhone(phone);
        // 隨機創建一個名稱
        user.setNickName(RandomUtil.randomString(10));
        // 2. 保存用戶
        save(user);
        return user;
    }

}
