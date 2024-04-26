package com.youyi.example;

import com.youyi.example.common.model.User;
import com.youyi.example.common.service.UserService;
import com.youyi.rpc.starter.annotation.RpcService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * @author <a href="https://github.com/dingxinliang88">youyi</a>
 */
@Slf4j
@Service
@RpcService
public class UserServiceImpl implements UserService {

    @Override
    public User getUser(String name) {
        log.info("username: {}", name);
        return new User(name + "-proxy");
    }
}
