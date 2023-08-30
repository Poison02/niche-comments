package com.zch.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zch.dto.LoginFormDTO;
import com.zch.dto.Result;
import com.zch.dto.UserDTO;
import com.zch.entity.User;
import com.zch.mapper.UserMapper;
import com.zch.service.IUserService;
import com.zch.utils.RegexUtils;
import com.zch.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.zch.utils.RedisConstants.*;
import static com.zch.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * @author Zch
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 接收phone进行校验
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2. 不合法直接返回错误
            return Result.fail("手机号不合法！");
        }
        // 3. 合法需要生成验证码
        String code = RandomUtil.randomNumbers(6);
        /*// 4. 保存到session
        session.setAttribute("code", code);*/
        // 4. 保存到redis中，并设置过期时间为2分钟
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 5. 发送验证码
        log.debug("验证码生成成功：{}", code);
        // 6. 返回
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2. 不合法直接返回错误
            return Result.fail("手机号不合法！");
        }
        /*// 2. 校验验证码（与session中的验证码进行校验）
        Object cacheCode = session.getAttribute("code");
        String code = loginForm.getCode();
        if (cacheCode == null || ! cacheCode.toString().equals(code)) {
            // 3. 不一致则报错
            return Result.fail("验证码错误！");
        }*/
        // 2. 校验验证码，与redis中的进行校验
        String code = loginForm.getCode();
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (cacheCode == null || ! cacheCode.equals(code)) {
            // 3. 不一致则报错
            return Result.fail("验证码错误！");
        }
        // 4. 根据手机号查询用户
        User user = query().eq("phone", phone).one();
        // 5. 用户不存在则创建新用户
        if (user == null) {
            user = createUserByPhone(phone);
        }
        /*// 6. 用户存在则保存session，我们在存储的时候存UserDTO，类似脱敏策略
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));*/
        // 6. 保存在redis中
        // 6.1 生成随机token
        String token = UUID.randomUUID().toString(true);
        // 6.2 将UserDTO转为map对象
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 这里转为map对象有一个坑，如果只用一个参数的beanToMap，那么UserDTO里面的
        // Long类型就不会转为String，所以要用下面这个操作
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        // 6.3 设置过期时间
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.SECONDS);
        // 7. 返回
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 6.循环遍历
        int count = 0;
        while (true) {
            // 6.1.让这个数字与1做与运算，得到数字的最后一个bit位  // 判断这个bit位是否为0
            if ((num & 1) == 0) {
                // 如果为0，说明未签到，结束
                break;
            }else {
                // 如果不为0，说明已签到，计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private User createUserByPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 保存数据库
        save(user);
        return user;
    }
}
