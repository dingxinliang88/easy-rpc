package com.youyi.example.provider;

import com.youyi.example.common.model.User;
import com.youyi.example.common.service.UserService;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户服务实现类
 *
 * @author <a href="https://github.com/dingxinliang88">youyi</a>
 */
@Slf4j
public class UserServiceImpl implements UserService {
    @Override
    public User getUser(User user) {
        log.info("user name: {}", user.getName());
        return user;
    }
}
