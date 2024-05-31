# 迭代优化

## 一、动态配置

### 需求背景

在 RPC 框架运行过程中，会涉及到很多配置信息，比如注册中心的地址、序列化方式、网络端口号等。再加上 RPC 框架是需要被其他项目作为服务提供者或者服务消费者引入的，我们应当允许框架的项目通过编写配置文件来**自定义配置**。一般情况下，服务提供者和服务消费者需要编写相同的 RPC 配置。

因此，我们需要维护一套全局配置加载功能，能够让 RPC 框架从配置文件中读取配置，并且维护一个全局配置对象，便于框架快速获取到一样的配置。

### 方案设计

#### 配置项梳理

开始时一切从简：

```properties
rpc.name=easy rpc test
rpc.host=localhost
rpc.port=8888
rpc.version=1.0.0
```

后期可以扩展，配置项参考：[Dubbo API 配置](https://cn.dubbo.apache.org/zh-cn/overview/mannual/java-sdk/reference-manual/config/api/)

#### 读取配置文件

1）配置文件类型：`properties`

2）使用 `Hutool#Props`

3）支持环境隔离

4）配置文件优先级：`conf/conf.properties` > `conf.properties`（相对于 classpath）

```java
/**
  * 加载配置
  * <p>
  * 优先加载 conf/conf.properties, 找不到再加载 conf.properties
  *
  * @param clazz  clazz
  * @param prefix properties common prefix
  * @param env    environment
  * @param <T>    T
  * @return props
  */
public static <T> T load(Class<T> clazz, String prefix, String env) {
    T props;
    try {
        props = loadProperties(clazz, "conf/conf", prefix, env);
    } catch (NoResourceException e) {
        log.warn("Not exists conf file in [conf/], will load file from classpath");
        props = loadProperties(clazz, "conf", prefix, env);
    }
    return props;
}
```



## 二、Mock 接口

### 需求背景

为什么需要？RPC 框架的核心功能是调用其他远程服务。但是在实际开发和测试过程中，有时可能无法直接访问真实的远程服务，或者访问真实的远程服务可能会产生不可控的影响，比如网络延迟、服务不稳定等。在这种情况下，就需要使用 Mock 服务来模拟远程服务的行为，以便进行接口的测试、开发和调试。

### 方案设计

使用动态代理，创建一个**调用方法返回固定值**的对象。

```properties
rpc.mock=false
```

## 三、序列化

### 需求背景

序列化器的作用：无论是请求还是响应，都会涉及到参数的传输。而 Java 对象是存活在  JVM 虚拟机中的，如果想在其他位置存储并访问、或者在网络中传输，就需要进行序列化和反序列化。

目前已经实现了基于 Java 原生序列化的序列化器，但是对于一个完善的 RPC 框架，还需要回答几个问题：

1. 有没有更好的序列化器实现方式？
2. 如何让使用框架的开发者指定使用序列化器？
3. 如何让使用框架的开发者自己定制序列化器？

### 方案设计

#### 常见的序列化实现方式

1）JSON

优点：

- 可读性好
- 语言支持广泛

缺点：

- 序列化后数据量较大
- 不能够很好的处理复杂的数据结构和循环引用，可能导致性能下降或者序列化失败

2）Hessian

优点：

- 二进制序列化，序列化后的数据量小，传输效率高
- 跨语言，适合在分布式系统中进行服务调用

缺点：

- 性能较 JSON 略低，因为需要将对象转换成二进制格式
- 对象必须要实现 Serializable 接口，限制了可序列化的对象范围

3）Kryo

优点：

- 高性能，序列化和反序列化速度快
- 支持循环引用和自定义序列化器，适用于复杂的对象结构
- 无需实现 Serializable 接口，可以序列化任意对象

缺点：

- 仅支持 Java
- 对象的序列化格式可读性差

4）Protobuf

优点：

- 高效、二进制序列化，序列化后的数据量小
- 跨语言支持
- 支持版本化和向前 / 向后兼容性

缺点：

- 配置相对复杂，需要先定义数据结构和消息格式
- 对象的序列化格式不易读懂，不便于调试

#### 动态使用序列化器

在使用序列化器时，根据配置来获取不同的序列化器。

#### 自定义序列化器

思路：RPC 框架能够读取到用户自定义的类路径，然后加载这个类，作为 Serializer 序列化接口的实现即可。

实现核心步骤：[Java SPI](https://pdai.tech/md/java/advanced/java-advanced-spi.html)

#### 自定义实现 SPI

1）指定 SPI 配置目录

- 系统内置 SPI: `META-INF/rpc/system`
- 用户自定义 SPI: `META-INF/rpc/custom`

2）自定义加载器

- 支持 `key=value`
- 用户目录优先级更高

3）支持懒加载

```java
public final class SerializerFactory {

    /**
     * 获取 Serializer
     *
     * @param key key
     * @return serializer
     */
    public static Serializer getSerializer(String key) {
        Serializer serializer;
        try {
            serializer = SpiLoader.getInstance(Serializer.class, key);
        } catch (NoSuchLoadClassException e) {
            init();
            serializer = SpiLoader.getInstance(Serializer.class, key);
        }
        return serializer;
    }

    public synchronized static void init() {
        SpiLoader.load(Serializer.class);
    }
}

// SpiLoader
public static <T> T getInstance(Class<T> loadClazz, String key) {
    String loadClazzName = loadClazz.getName();
    Map<String, Class<?>> keyImplClassMap = LOADER_MAP.get(loadClazzName);
    if (Objects.isNull(keyImplClassMap)) {
        throw new NoSuchLoadClassException(
            String.format("SpiLoader don't load %s,", loadClazz));
    }
    if (!keyImplClassMap.containskey(key)) {
        throw new NoSuchkeyException(
            String.format("%s has no key=%s impl class type,", loadClazz, key));
    }
    // ...
}
```



## 四、注册中心

### 需求背景

注册中心是 RPC 框架的一个核心模块，目的是帮助服务消费者获取到服务提供者的调用地址，而不是将调用地址硬编码到项目中。

![](assets/easy-rpc-registry-center.drawio.png)

### 方案设计

#### 注册中心的核心能力

1. 数据分布式存储：集中的注册信息数据存储、读取和共享
2. 服务注册：服务提供者上报服务信息到注册中心
3. 服务发现：服务消费者从注册中心拉取服务信息
4. 心跳检测：定期检查服务提供者的存活状态
5. 服务注销：手动剔除节点、或者自动剔除失效节点
6. 容错、缓存、……

#### 技术选型

根据核心能力进行选型。

**数据分布式存储**是最重要的，此外还需要有数据过期、数据监听的能力。在此基础上，考虑性能、高**可用性**、高**可靠性**、稳定性、数据一致性、社区生态和活跃度。

主流的注册中心中间件：

- Zookeeper
- Redis
- Etcd ✅
- ……

> ### Etcd
>
> Etcd 是一个 Go 语言实现的、开源的、分布式 的键值存储系统，它主要用于分布式系统中的服务发现、配置管理和分布式锁等场景。Etcd 采用 [Raft 一致性算法](https://en.wikipedia.org/wiki/Raft_(algorithm))来保证数据的一致性和可靠性，具有高可用性、强一致性、分布式特性等特点。
>
> #### Etcd 数据结构
>
> 1）key：Etcd 中的基本数据单元，类似于文件系统中的文件名。每个键都唯一标识一个值，并且可以包含子键，形成类似于路径的层次结构；「etcd 中的每个键都有一个与之关联的版本号，用于跟踪键的修改历史」
>
> 2）Value：与键关联的数据，可以是任意类型的数据，通常是字符串形式。
>
> #### 核心特性
>
> **1）Lease（租约）**：用于对键值对进行 TTL 超时设置，即设置键值对的过期时间。当租约过期时，相关的键值对将被自动删除；
>
> **2）Watch（监听）：**可以监视特定键的变化，当键的值发生变化时，会触发相应的通知。
>
> #### 安装
>
> - [Etcd](https://etcd.io/docs/v3.5/install/)
>- [EtcdKeeper](https://github.com/evildecay/etcdkeeper)
> 
> #### 了解 Raft In Etcd
>
> - http://play.etcd.io/play
>
> #### Etcd Java 客户端
>
> [jetcd](https://github.com/etcd-io/jetcd)
>
> 📢注意：需要 JDK11+
>
> 1. **kvClient**：用于对 etcd 中的键值对进行操作。通过 kvClient 可以进行设置值、获取值、删除值、列出目录等操作；
>2. **leaseClient**：用于管理 etcd 的租约机制。租约是 etcd 中的一种时间片，用于为键值对分配生存时间，并在租约到期时自动删除相关的键值对。通过 leaseClient 可以创建、获取、续约和撤销租约；
> 3. **watchClient**：用于监视 etcd 中键的变化，并在键的值发生变化时接收通知；
>4. clusterClient：用于与 etcd 集群进行交互，包括添加、移除、列出成员、设置选举、获取集群的健康状态、获取成员列表信息等操作；
> 5. authClient：用于管理 etcd 的身份验证和授权。通过 authClient 可以添加、删除、列出用户、角色等身份信息，以及授予或撤销用户或角色的权限；
> 6. maintenanceClient：用于执行 etcd 的维护操作，如健康检查、数据库备份、成员维护、数据库快照、数据库压缩等；
> 7. lockClient：用于实现分布式锁功能，通过 lockClient 可以在 etcd 上创建、获取、释放锁，能够轻松实现并发控制；
> 8. electionClient：用于实现分布式选举功能，可以在 etcd 上创建选举、提交选票、监视选举结果等。



#### 存储结构设计

存储结构设计的几个要点：

1. key 的设计
2. Value 的设计
3. key 的过期时间

由于一个服务可能有多个服务提供者（负载均衡），此处可以有两种结构设计：

1）层级结构。将服务理解为文件夹、将服务对应的多个节点理解为文件夹下的文件，那么可以通过服务名称，用前缀查询的方式查询到某个服务的所有节点。

【🌰栗子】

键名的规则可以是 `/业务前缀/服务名/服务节点地址`：

![](assets/easy-rpc-key-design-folder.drawio.png)

2）列表结构。将所有的服务节点以列表的形式整体作为 Value，即服务为 key 其节点整体为 Value。

【🌰栗子】

![](assets/easy-rpc-key-design-list.drawio.png)

> 选择哪种存储结构呢？

这个跟技术选型有关。对于 ZooKeeper 和 Etcd 这种支持层级查询的中间件，**用第一种结构会更清晰；**对于 Redis，由于本身就支持列表数据结构，可以选择第二种结构。

最后，一定要**给 key 设置过期时间**，比如默认 30 秒过期，这样如果服务提供者宕机了，也可以超时后自动移除。

#### 具体实现细节

抽象接口：

```java
public interface Registry {


    /**
     * 注册中心初始化
     *
     * @param config registry config
     */
    void init(RegistryConfig config);

    /**
     * 服务端注册服务
     *
     * @param metadata 服务元信息
     */
    void register(ServiceMetadata metadata) throws Exception;

    /**
     * 服务端注销服务
     *
     * @param metadata 服务元信息
     */
    void deregister(ServiceMetadata metadata);

    /**
     * 消费端获取某服务的所有节点，服务发现
     *
     * @param serviceKey 服务键
     * @return 某服务下的所有服务节点元信息
     */
    List<ServiceMetadata> discovery(String serviceKey);


    /**
     * 注册中心销毁
     */
    void destroy();

}
```

最后，也是可以利用自定义 SPI 实现注册中心的扩展（同 Serializer）。

## 五、自定义协议

### 需求背景

目前的 RPC 框架，底层网络传输使用的是 HTTP 协议，有没有更好的选择呢？

一般情况下，RPC 框架会比较注重性能，而 HTTP 协议中的头部信息、请求响应格式会比较重，会影响网络传输。

所以需要自定义一套 RPC 协议，比如利用 TCP 等传输层协议，自定义请求响应结构，来实现性能更高、更灵活、更安全的 RPC 框架。

### 方案设计

自定义 RPC 协议可以分为两大核心部分：

- 自定义网络传输
- 自定义消息结构

#### 网络传输设计

**目标**：选择一个能够高性能通信的网络协议和传输方式。

HTTP 协议的头信息是比较重的，会影响传输性能；除此之外，HTTP 本身属于无状态协议，这意味着每个 HTTP 请求都是独立的，每次请求 / 响应都需要重新建立 / 关闭连接，也会影响性能。当然，在 HTTP/1.1 中引入了 Keep-Alive 机制，允许在单个 TCP 连接上发送多个 HTTP 请求和响应，避免了每次请求都要重新建立和关闭连接的开销，但是还是会发生「响应队头阻塞」的问题。

因此，为了追求更高的性能，还是选择使用 TCP 协议完成网络传输，有更多自主的设计空间。

> 也可以使用 UDP + QUIC协议实现，性能更高。

#### 消息结构设计

**目标**：用**最少的空间**传递**需要的信息**。

1）如何使用最少的空间？

尽可能使用更轻量的类型，比如 `byte`，甚至是 `bit`。

2）消息内需要哪些信息？

1. 魔数 (magic)：用于安全校验，防止服务器处理了非框架发来的乱七八糟的消息，类似 HTTPS 的安全证书；
2. 版本号 (version)：保证请求和响应的一致性，类似 HTTP 协议有 1.0/2.0 等版本；
3. 序列化方式 (serializer)：来告诉服务端和客户端如何解析数据，类似 HTTP 的 Content-Type 内容类型；
4. 类型 (type)：标识是请求还是响应，或者是心跳检测等其他用途，类似 HTTP 有请求头和响应头；
5. 状态 (status)：如果是响应，记录响应的结果，类似 HTTP 的 200 状态代码；
6. 请求 id (reqId)：唯一标识某个请求，因为 TCP 是双向通信的，需要有个唯一标识来追踪每个请求；
7. 请求体数据长度 (bodyLength)：保证能够完整地获取 body 内容信息，TCP 协议本身会存在**半包和粘包**问题，每次传输的数据可能是不完整的。

![](assets/easy-rpc-protocol_1.0.drawio.png)

实际上，这些数据应该是**紧凑**的，请求头信息总长为 17 byte，即上述消息结构本质上就是拼接在一起的一个**字节数组**。后续实现时，需要有**消息编码器 / 消息解码器**，编码器先创建一个空的 Buffer 缓冲区，然后按照顺序向缓冲区依次写入这些数据；解码器在读取时也按照**顺序**依次**读取**，就能够还原出编码前的数据。

![](assets/easy-rpc-encoder_decoder.drawio.png)



### 粘包半包问题解决

- 半包：接受到的数据少了
- 粘包：接收到的数据多了

#### 如何解决粘包？

解决粘包和半包的核心思路也是类似的：**每次只读取指定长度的数据，超过长度的留着下一次接收到消息时再读取**。

解决思路：将读取完整的消息拆分为2次：

1. 先完整读取请求头信息，由于请求头信息长度是固定的，可以使用 `Vertx.RecordPraser` 保证每次都完整读取；
2. 再根据请求头长度信息更改 `RecordParser` 的固定长度，保证完整获取到请求体。

```java
private RecordParser initRecordParser(Handler<Buffer> bufferHandler) {
    // 构造 Parser
    RecordParser parser = RecordParser.newFixed(ProtocolConstant.MESSAGE_HEADER_LENGTH);

    parser.setOutput(new Handler<>() {
        // 初始化
        int size = -1;
        // 一次性完整地读取（头 + 体）
        Buffer resultBuffer = Buffer.buffer();

        @Override
        public void handle(Buffer buffer) {
            if (-1 == size) {
                // 读取消息体长度
                size = buffer.getInt(ProtocolConstant.BODY_LEN_POS);
                parser.fixedSizeMode(size);
                // 写入头信息
                resultBuffer.appendBuffer(buffer);
            } else {
                // 写入消息体
                resultBuffer.appendBuffer(buffer);
                bufferHandler.handle(resultBuffer);

                // 重置一轮
                parser.fixedSizeMode(ProtocolConstant.MESSAGE_HEADER_LENGTH);
                size = -1;
                resultBuffer = Buffer.buffer();
            }
        }
    });

    return parser;
}
```

具体实现时，使用**装饰者模式**，使用 `RecordParser` 对原有的 `Buffer` 处理器的能力进行增强。

## 六、负载均衡

### 需求背景

当客户端发起请求时，选择一个服务提供者发起请求，而不是每次都请求同一个服务提供者，这个操作就叫做 **负载均衡**。

> 参考：[什么是负载均衡？](https://www.codefather.cn/%E4%BB%80%E4%B9%88%E6%98%AF%E8%B4%9F%E8%BD%BD%E5%9D%87%E8%A1%A1/)

#### 常见负载均衡算法

1）**轮询 (Round Robin)**：按照循环的顺序将请求分配给每个服务器，适用于各服务器性能相近的情况；

2）**随机 (Random)**：随机选择一个服务器来处理请求，适用于服务器性能相近且负载均匀的情况；

3）**加权轮询 (Weighted Round Robin)**：根据服务器的性能或权重分配请求，性能更好的服务器会获得更多的请求，适用于服务器性能不均的情况；

4）**加权随机 (Weighted Random)**：根据服务器的权重随机选择一个服务器处理请求，适用于服务器性能不均的情况；

5）**最小连接数 (Least Connections)**：选择当前连接数最少的服务器来处理请求，适用于长连接场景；

6）**IP Hash**：根据客户端 IP 地址的哈希值选择服务器处理请求，确保同一客户端的请求始终被分配到同一台服务器上，适用于需要保持会话一致性的场景。

> 一致性 Hash 参考：[一致性哈希算法分区](https://www.codejuzi.icu/docs/46902.html)

### 方案设计

通过 SPI 机制，支持配置和扩展负载均衡器

1）引入负载均衡器工厂 `LoadBalancer`

2）使用工厂模式 `LoadBalancerFactory`

实现算法：

- Round Robin
- Random
- ConsistentHash



## 七、重试机制

### 需求背景

目前，如果使用 RPC 框架的服务消费者调用接口失败，就会直接报错。

调用接口失败可能有很多原因，有时可能是服务提供者返回了错误，但有时可能只是网络不稳定或服务提供者重启等临时性问题。这种情况下，我们可能更希望服务消费者拥有自动重试的能力，提高系统的可用性。

### 方案设计

#### 重试策略

1. 什么时候、什么条件下重试？
2. 重试时间？
3. 什么时候、什么条件下停止重试？
4. 重试后要做什么？

#### 重试条件

希望提高系统的可用性，当由于网络等异常情况发生时，触发重试。

#### 重试时间算法

1）固定重试间隔 `(Fixed Retry Interval)`：在每次重试之间使用固定的时间间隔；

2）指数退避重试 (`Exponential Backoff Retry`)：在每次失败后，重试的时间间隔会以指数级增加，以避免请求过于密集；

3）随机延迟重试 (`Random Delay Retry`)：在每次重试之间使用随机的时间间隔，以避免请求的同时发生；

4）可变延迟重试 (`Variable Delay Retry`)：这种策略更「高级」，根据先前重试的成功或失败情况，动态调整下一次重试的延迟时间。比如，根据前一次的响应时间调整下一次重试的等待时间。

> 值得一提的是，以上的策略是可以组合使用的，一定要根据具体情况和需求灵活调整。比如可以先使用指数退避重试策略，如果连续多次重试失败，则切换到固定重试间隔策略。

#### 停止重试

一般来说，重试次数是有上限的，否则随着报错的增多，系统同时发生的重试也会越来越多，造成雪崩。

主流的停止重试策略有：

1）最大尝试次数：一般重试当达到最大次数时不再重试；

2）超时停止：重试达到最大时间的时候，停止重试。

#### 重试工作

最后一点是重试后要做什么事情？一般来说就是重复执行原本要做的操作，比如发送请求失败了，那就再发一次请求。

需要注意的是，当重试次数超过上限时，往往还要进行其他的操作，比如：

1）通知告警：让开发者人工介入；

2）降级容错：改为调用其他接口、或者执行其他操作。

#### 重试方案设计

1）使用 `guava-retrying`；

2）可以将 `VertxTcpClient.doRequest` 封装为一个可重试的任务，如果请求失败（重试条件），系统就会自动按照重试策略再次发起请求，不用开发者关心；

3）实现策略：

- 不重试
- 固定时间间隔
- 指数退避

4）使用 SPI 机制，实现可配置和可扩展

## 八、容错机制

### 需求背景

服务消费者调用服务失败后，一定要重试嘛？可以尝试另一种提高服务消费端可靠性和健壮性的机制——容错机制。

### 方案设计

#### 容错机制

容错是指系统在出现异常情况时，可以通过一定的策略保证系统仍然稳定运行，从而提高系统的可靠性和健壮性。

在分布式系统中，容错机制尤为重要，因为分布式系统中的各个组件都可能存在网络故障、节点故障等各种异常情况。要顾全大局，尽可能消除偶发 / 单点故障对系统带来的整体影响。比如，将分布式系统类比为一家公司，如果公司某个优秀员工请假了，需要 「触发容错」，让另一个普通员工顶上，这本质上是容错机制的一种 **降级** 策略。

#### 容错策略

1）`Fail-Over` 故障转移：一次调用失败后，切换一个其他节点再次进行调用，也算是一种重试；

2）`Fail-Back` 失败自动恢复：系统的某个功能出现调用失败或错误时，通过其他的方法，恢复该功能的正常。可以理解为降级，比如重试、调用其他服务等；

3）`Fail-Safe` 静默处理：系统出现部分非重要功能的异常时，直接忽略掉，不做任何处理，就像错误没有发生过一样；

4）`Fail-Fast` 快速失败：系统出现调用错误时，立刻报错，交给外层调用方处理。

#### 容错实现方式

1）重试：重试本质上也是一种容错的降级策略，系统错误后再试一次；

2）限流：当系统压力过大、已经出现部分错误时，通过限制执行操作（接受请求）的频率或数量，对系统进行保护；

3）降级：系统出现错误后，改为执行其他更稳定可用的操作。也可以叫做 「兜底」 或 「有损服务」，这种方式的本质是：即使牺牲一定的服务质量，也要保证系统的部分功能可用，保证基本的功能需求得到满足；

4）熔断：系统出现故障或异常时，暂时中断对该服务的请求，而是执行其他操作，以避免连锁故障；

5）超时控制：如果请求或操作长时间没处理完成，就进行中断，防止阻塞和资源占用；

在实际项目中，根据对系统可靠性的需求，通常会结合多种策略或方法实现容错机制。



#### 容错方案设计

1）先容错再重试：当系统发生异常时，首先会触发容错机制，比如记录日志，告警等，然后可以选择是否进行重试；

> 这种方案其实是把重试当做容错的一种可选方案。

2）先重试再容错：当系统发生异常时，先尝试重试操作，如果重试多次仍然失败，则触发容错机制，比如记录日志、告警等。

两种方案其实可以结合使用，系统错误时，可以先通过重试机制解决一些临时性的异常，比如网络波动、服务端临时不可用等；如果重试多次后仍然失败，说明可能存在更严重的问题，此时可以触发其他的容错策略，比如调用降级服务、熔断、限流、快速失败等等，来减少异常的影响，保障系统的稳定性和可靠性。

【🌰栗子】

1. 系统调用服务 A 出现网络错误，使用容错机制 —— 重试；
2. 重试 3 次失败后，使用其他容错策略 —— 降级；
3. 系统改为调用不依赖网络的服务 B，完成操作。

具体实现时，使用 SPI 机制，实现可配置和可扩展。

```java
public interface TolerantStrategy {


    /**
     * 容错
     *
     * @param context 上下文
     * @param e       异常
     * @return RpcResponse
     */
    RpcResponse tolerant(Map<String, Object> context, Exception e) throws Exception;

}
```

使用容错机制：

```java
RpcResponse rpcResponse;
try {
    // 重试机制
    RetryStrategy retryStrategy = RetryStrategyFactory.getRetryStrategy(
        RpcApplication.resolve().getRetry());
    // 发送 TCP 请求
    rpcResponse = retryStrategy.retry(
        () -> VertxTcpClient.doRequest(rpcRequest, selectedService));
} catch (Exception e) {
    // 容错上下文
    Map<String, Object> context = new HashMap<>() {{
        put("serviceMetadataList", serviceMetadataList);
        put("errorService", selectedService);
        put("rpcRequest", rpcRequest);
    }};
    // 容错机制
    TolerantStrategy tolerantStrategy = TolerantStrategyFactory.getTolerantStrategy(
        RpcApplication.resolve().getTolerant());
    rpcResponse = tolerantStrategy.tolerant(context, e);
}
return rpcResponse.getData();
```

## 九、启动机制

### 需求背景

让用户更快上手。

### 方案设计

#### 启动机制设计

把所有启动代码封装成一个专门的**启动类**或者方法，然后由服务提供者 / 服务消费者来调用即可。

```java
public class ProviderBootstrap {

    public static void init(List<ServiceRegisterInfo<?>> serviceRegisterInfoList) {
        // RPC 框架初始化（配置、注册中心）
        RpcApplication.init();
        // 全局配置
        final ApplicationConfig applicationConfig = RpcApplication.resolve();
        // 获取注册中心
        RegistryConfig registryConfig = applicationConfig.getRegistry();
        Registry registry = RegistryFactory.getRegistry(registryConfig.getRegistry());

        // 注册服务
        for (ServiceRegisterInfo<?> serviceRegisterInfo : serviceRegisterInfoList) {
            String serviceName = serviceRegisterInfo.getServiceName();
            Class<?> implClass = serviceRegisterInfo.getImplClass();

            // 本地注册
            LocalRegistry.registry(serviceName, implClass);

            ServiceMetadata serviceMetadata = new ServiceMetadata();
            serviceMetadata.setServiceName(serviceName);
            serviceMetadata.setServiceHost(applicationConfig.getHost());
            serviceMetadata.setServicePort(applicationConfig.getPort());

            try {
                // 注册到注册中心
                registry.register(serviceMetadata);
            } catch (Exception e) {
                throw new RpcException("register service failed", e);
            }
        }

        // 启动 Provider Web 服务
        RpcServer rpcServer = new VertxTcpServer();
        rpcServer.doStart(applicationConfig.getPort());

    }
}
```

```java
public class ConsumerBootstrap {

    public static void init() {
        // RPC 框架初始化
        RpcApplication.init();
    }

}
```

## 十、注解驱动

### 需求背景

几个注解即可使用 RPC 框架，比如 Dubbo 的 `@EnableDubbo`、 `@DubboService`、`@DubboReference`。使用更简便。

### 方案设计

#### 实现注解驱动的常见方式

1. 主动扫描：让开发者指定要扫描的路径，然后遍历所有的类文件，针对有注解的类文件，执行自定义的操作；
2. 监听 Bean 加载：在 Spring 项目中，可以通过实现 `BeanPostProcessor` 接口，在 Bean 初始化后执行自定义的操作。

#### 定义注解

参考 Dubbo 的注解

1. `@EnableDubbo`：在 Spring Boot 主应用类上使用，用于启用 Dubbo 功能。
2. `@DubboComponentScan`：在 Spring Boot 主应用类上使用，用于指定 Dubbo 组件扫描的包路径。
3. `@DubboReference`：在消费者中使用，用于声明 Dubbo 服务引用。
4. `@DubboService`：在提供者中使用，用于声明 Dubbo 服务。
5. `@DubboMethod`：在提供者和消费者中使用，用于配置 Dubbo 方法的参数、超时时间等。
6. `@DubboTransported`：在 Dubbo 提供者和消费者中使用，用于指定传输协议和参数，例如传输协议的类型、端口等。

本次实现遵循最小可用化原则，我们只需要定义 3 个注解

1）`@EnableRpc`：用于全局标识项目需要引入 RPC 框架、执行初始化方法。由于服务消费者和服务提供者初始化的模块不同，我们需要在 EnableRpc 注解中，指定是否需要启动服务器等属性。

2）`@RpcService`：服务提供者注解，在需要注册和提供的服务类上使用。`@RpcService` 注解中，需要指定服务注册信息属性，比如服务接口实现类、版本号、服务名称等；

3）`@RpcReference`：服务消费者注解，在需要注入服务代理对象的属性上使用，类似 Spring 中的 `@Resource` 注解。

#### 获取注解的属性

实现 `Spring` 的 `ImportBeanDefinitionRegistrar `接口，并且在 `registerBeanDefinitions` 方法中，获取到项目的注解和注解属性。

```java
@Slf4j
public class RpcInitBootStrap implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(@NonNull AnnotationMetadata importingClassMetadata,
            @NonNull BeanDefinitionRegistry registry) {
        // 获取 @EnableRpc 注解的属性值
        boolean needServer = (boolean) Objects.requireNonNull(
                        importingClassMetadata.getAnnotationAttributes(EnableRpc.class.getName()))
                .get("needServer");

        // RPC 框架初始化
        RpcApplication.init();

        // 全局配置
        final Config applicationConfig = RpcApplication.resolve();

        if (needServer) {
            // 启动 服务器
            RpcServer rpcServer = new VertxTcpServer();
            rpcServer.doStart(applicationConfig.getPort());
        }
    }
}
```

#### 获取到包含注解的类属性（自动注入）

实现 `BeanPostProcessor `接口的 `postProcessAfterInitialization `方法，就可以在某个服务提供者 Bean 初始化后，执行注册服务等操作了。

```java
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
                throw new RpcException("failed to set rpc proxy in field: " + field.getName(),
                        e);
            }
        }
        return BeanPostProcessor.super.postProcessAfterInitialization(bean, beanName);
    }
}
```

```java
@Slf4j
public class RpcProviderBootStrap implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName)
            throws BeansException {
        Class<?> beanClazz = bean.getClass();
        RpcService rpcService = beanClazz.getAnnotation(RpcService.class);
        // 需要注册服务
        if (rpcService != null) {
            // 1. 获取服务基本信息
            Class<?> interfaceClass = rpcService.interfaceClass();
            // 默认值处理
            if (interfaceClass == void.class) {
                interfaceClass = beanClazz.getInterfaces()[0];
            }

            String serviceName = interfaceClass.getName();
            String serviceVersion = rpcService.serviceVersion();

            // 全局配置
            final Config applicationConfig = RpcApplication.resolve();
            // 获取注册中心
            RegistryConfig registryConfig = applicationConfig.getRegistry();
            Registry registry = RegistryFactory.getRegistry(registryConfig.getRegistry());

            // 2. 注册服务
            LocalRegistry.registry(serviceName, beanClazz);

            ServiceMetadata serviceMetadata = new ServiceMetadata();
            serviceMetadata.setServiceName(serviceName);
            serviceMetadata.setServiceHost(applicationConfig.getHost());
            serviceMetadata.setServicePort(applicationConfig.getPort());
            serviceMetadata.setServiceVersion(serviceVersion);

            try {
                // 注册到注册中心
                registry.register(serviceMetadata);
            } catch (Exception e) {
                throw new RpcException("register service failed", e);
            }
        }
        return BeanPostProcessor.super.postProcessAfterInitialization(bean, beanName);
    }
}
```

#### 启动类注解

```java
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Import({RpcInitBootStrap.class, RpcProviderBootStrap.class, RpcConsumerBootStrap.class})
public @interface EnableRpc {

    /**
     * @return 是否需要开启 Server
     */
    boolean needServer() default true;
}
```

引入 `@EnableRpc`时，将`RpcInitBootstrap`、`RpcProviderBootstrap` 和`RpcConsumerBootstrap` 这三个类导入到 Spring 的上下文中。这三个类可能包含了一些重要的配置信息、Bean 定义或者其他初始化逻辑，它们对于 RPC 框架的初始化和运行至关重要。
