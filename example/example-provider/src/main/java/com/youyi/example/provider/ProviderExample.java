package com.youyi.example.provider;

import com.youyi.example.common.service.UserService;
import com.youyi.rpc.bootstrap.ProviderBootstrap;
import com.youyi.rpc.model.ServiceRegisterInfo;
import java.util.List;

/**
 * 简易服务提供者示例
 *
 * @author <a href="https://github.com/dingxinliang88">youyi</a>
 */
public class ProviderExample {

    public static void main(String[] args) {

        List<ServiceRegisterInfo<?>> serviceRegisterInfoList = List.of(
                new ServiceRegisterInfo<>(UserService.class.getName(),
                        UserServiceImpl.class));

        ProviderBootstrap.init(serviceRegisterInfoList);
    }

}
