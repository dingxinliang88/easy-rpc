package com.youyi.rpc.starter.bootstrap;

import com.youyi.rpc.proxy.ServiceProxyFactory;
import com.youyi.rpc.starter.annotation.RpcReference;
import java.lang.reflect.Field;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * RPC 服务消费者
 * <p>
 * 获取到包含 @RpcReference 注解的类，为其注入属性
 *
 * @author <a href="https://github.com/dingxinliang88">youyi</a>
 */
@Slf4j
public class RpcConsumerBootStrap implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName)
            throws BeansException {
        Class<?> beanClazz = bean.getClass();
        // 遍历对象的所有属性
        Field[] declaredFields = beanClazz.getDeclaredFields();
        for (Field field : declaredFields) {
            RpcReference rpcReference = field.getAnnotation(RpcReference.class);
            if (rpcReference == null) {
                continue;
            }
            // 为属性生成代理对象
            Class<?> interfaceClass = rpcReference.interfaceClass();
            if (interfaceClass == void.class) {
                interfaceClass = field.getType();
            }

            field.setAccessible(true);

            Object proxy = ServiceProxyFactory.getProxy(interfaceClass);

            try {
                field.set(bean, proxy);
                field.setAccessible(false);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("failed to set rpc proxy in field: " + field.getName(),
                        e);
            }
        }
        return BeanPostProcessor.super.postProcessAfterInitialization(bean, beanName);
    }
}
