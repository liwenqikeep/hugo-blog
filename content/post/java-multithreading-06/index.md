---
title: "Java 多线程（六）：异步编程与高级主题"
date: 2017-12-22
draft: false
categories: ["Java"]
tags: ["Java SE", "多线程", "并发编程", "CompletableFuture", "Fork/Join", "ThreadLocal"]
---

## 前言

这是本系列文章的最后一篇，我们将讲解 Java 异步编程的利器 CompletableFuture、分治思想的 Fork/Join 框架，以及实际项目中容易踩坑的 ThreadLocal 内存泄漏问题。

## 一、CompletableFuture 异步编排

### 1.1 为什么需要 CompletableFuture

传统 Future 的局限性：
- 不能手动完成
- 不能链式调用
- 不能组合多个 Future
- 没有异常处理机制

```java
// 传统 Future 的问题
Future<String> future = executor.submit(() -> {
    // 异步任务
    return "result";
});

// 主线程需要轮询检查是否完成，效率低下
while (!future.isDone()) {
    // 忙等待
}
String result = future.get();  // 阻塞
```

### 1.2 创建 CompletableFuture

```java
public class CompletableFutureDemo {
    
    // 1. supplyAsync - 有返回值
    public CompletableFuture<String> fetchUserData() {
        return CompletableFuture.supplyAsync(() -> {
            // 模拟耗时操作
            return "UserData";
        });
    }
    
    // 2. runAsync - 无返回值
    public CompletableFuture<Void> sendNotification() {
        return CompletableFuture.runAsync(() -> {
            // 发送通知
            System.out.println("通知已发送");
        });
    }
    
    // 3. 自定义完成
    public CompletableFuture<String> customFuture() {
        CompletableFuture<String> future = new CompletableFuture<>();
        
        // 模拟异步操作
        new Thread(() -> {
            try {
                String result = "completed";
                future.complete(result);  // 手动完成
            } catch (Exception e) {
                future.completeExceptionally(e);  // 标记异常
            }
        }).start();
        
        return future;
    }
}
```

### 1.3 链式操作

```java
public class ChainDemo {
    
    public void chainDemo() {
        CompletableFuture.supplyAsync(() -> {
                return fetchUserId();
            })
            .thenApply(userId -> {
                // 上一步的结果自动传入
                return fetchUserProfile(userId);
            })
            .thenApply(profile -> {
                // 继续处理
                return enrichProfile(profile);
            })
            .thenAccept(profile -> {
                // 最终消费结果，无返回值
                System.out.println("最终结果: " + profile);
            })
            .exceptionally(ex -> {
                // 异常处理
                System.err.println("处理失败: " + ex.getMessage());
                return null;
            });
    }
    
    private String fetchUserId() { return "123"; }
    private UserProfile fetchUserProfile(String userId) { return new UserProfile(); }
    private UserProfile enrichProfile(UserProfile profile) { return profile; }
}
```

### 1.4 组合多个 Future

```java
public class ComposeDemo {
    
    // thenCompose - 扁平化，用于返回 Future 的场景
    public CompletableFuture<String> getUserDepartment() {
        return CompletableFuture.supplyAsync(() -> "userId")
            .thenCompose(userId -> getDepartmentId(userId))
            .thenCompose(deptId -> getDepartmentName(deptId));
    }
    
    private CompletableFuture<String> getDepartmentId(String userId) {
        return CompletableFuture.supplyAsync(() -> "deptId");
    }
    
    private CompletableFuture<String> getDepartmentName(String deptId) {
        return CompletableFuture.supplyAsync(() -> "研发部");
    }
    
    // thenCombine - 合并两个独立的 Future
    public CompletableFuture<UserInfo> getUserInfoComplete() {
        CompletableFuture<String> userFuture = 
            CompletableFuture.supplyAsync(() -> fetchUserName());
        CompletableFuture<Integer> ageFuture = 
            CompletableFuture.supplyAsync(() -> fetchUserAge());
        
        return userFuture.thenCombine(ageFuture, (name, age) -> 
            new UserInfo(name, age)
        );
    }
    
    // allOf - 等待所有 Future 完成
    public CompletableFuture<Void> waitAll() {
        List<CompletableFuture<String>> futures = IntStream.range(0, 3)
            .mapToObj(i -> CompletableFuture.supplyAsync(() -> "Task-" + i))
            .collect(Collectors.toList());
        
        return CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0])
        );
    }
    
    // anyOf - 任意一个完成即可
    public CompletableFuture<Object> waitAny() {
        return CompletableFuture.anyOf(
            CompletableFuture.supplyAsync(() -> task1()),
            CompletableFuture.supplyAsync(() -> task2()),
            CompletableFuture.supplyAsync(() -> task3())
        );
    }
    
    private String fetchUserName() { return "张三"; }
    private int fetchUserAge() { return 25; }
    private String task1() { return "Task1"; }
    private String task2() { return "Task2"; }
    private String task3() { return "Task3"; }
}
```

### 1.5 实战：电商详情页聚合

```java
public class ProductDetailService {
    
    private final ExecutorService executor = 
        Executors.newFixedThreadPool(10);
    
    public ProductDetail getProductDetail(String productId) {
        try {
            return CompletableFuture
                .supplyAsync(() -> fetchProduct(productId), executor)
                .thenCombine(
                    CompletableFuture.supplyAsync(() -> fetchPrice(productId), executor),
                    (product, price) -> {
                        product.setPrice(price);
                        return product;
                    }
                )
                .thenCombine(
                    CompletableFuture.supplyAsync(() -> fetchStock(productId), executor),
                    (product, stock) -> {
                        product.setStock(stock);
                        return product;
                    }
                )
                .thenCombine(
                    CompletableFuture.supplyAsync(() -> fetchComments(productId), executor),
                    (product, comments) -> {
                        product.setComments(comments);
                        return product;
                    }
                )
                .thenCombine(
                    CompletableFuture.supplyAsync(() -> fetchRecommendations(productId), executor),
                    (product, recommendations) -> {
                        product.setRecommendations(recommendations);
                        return product;
                    }
                )
                .get(3, TimeUnit.SECONDS);  // 超时控制
        } catch (Exception e) {
            throw new RuntimeException("获取商品详情失败", e);
        }
    }
    
    // 模拟服务调用
    private Product fetchProduct(String id) { return new Product(); }
    private Price fetchPrice(String id) { return new Price(); }
    private Stock fetchStock(String id) { return new Stock(); }
    private List<Comment> fetchComments(String id) { return new ArrayList<>(); }
    private List<Product> fetchRecommendations(String id) { return new ArrayList<>(); }
}
```

## 二、Fork/Join 框架

### 2.1 Fork/Join 核心思想

Fork/Join 是分治思想在并发中的应用：
- **Fork**：将大任务拆分为小任务
- **Join**：合并小任务的计算结果

```
        大任务
        /    \
    任务1    任务2
    /  \      /  \
  子1  子2  子3  子4
   \  /      \  /
   结果1     结果2
        \    /
        合并结果
```

### 2.2 工作窃取算法

Fork/Join 使用工作窃取（Work Stealing）：
- 每个线程有自己的双端任务队列
- 空闲线程可以从其他线程队列尾部偷任务
- 减少线程空闲等待

```
线程 A 队列: [Task1] [Task2] [Task3] [Task4]
线程 B 队列: [Task5] [Task6] [Task7] [Task8]
                    ↑
            B 空闲时从 A 尾部偷 Task4
```

### 2.3 基本使用

```java
public class SumTask extends RecursiveTask<Long> {
    
    private static final long THRESHOLD = 1000;
    private final long[] array;
    private final int start;
    private final int end;
    
    public SumTask(long[] array, int start, int end) {
        this.array = array;
        this.start = start;
        this.end = end;
    }
    
    @Override
    protected Long compute() {
        int length = end - start;
        
        // 小任务直接计算
        if (length <= THRESHOLD) {
            long sum = 0;
            for (int i = start; i < end; i++) {
                sum += array[i];
            }
            return sum;
        }
        
        // 大任务拆分
        int mid = start + length / 2;
        SumTask leftTask = new SumTask(array, start, mid);
        SumTask rightTask = new SumTask(array, mid, end);
        
        // Fork：异步执行子任务
        leftTask.fork();
        rightTask.fork();
        
        // Join：获取结果并合并
        return leftTask.join() + rightTask.join();
    }
}

// 使用
public class ForkJoinDemo {
    
    public static void main(String[] args) {
        ForkJoinPool pool = ForkJoinPool.commonPool();
        
        long[] array = new long[1000000];
        // 初始化数组...
        
        SumTask task = new SumTask(array, 0, array.length);
        long result = pool.invoke(task);
        
        System.out.println("Sum: " + result);
    }
}
```

### 2.4 实战：并行数据处理

```java
public class ParallelProcessor {
    
    public <T, R> List<R> process(List<T> items, 
                                   Function<T, R> processor,
                                   int parallelism) {
        
        ForkJoinPool pool = new ForkJoinPool(parallelism);
        
        try {
            return pool.submit(() ->
                items.parallelStream()
                     .map(processor)
                     .collect(Collectors.toList())
            ).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            pool.shutdown();
        }
    }
    
    // 实战：并行文件处理
    public void parallelFileProcessing(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            List<Path> files = paths
                .filter(Files::isRegularFile)
                .collect(Collectors.toList());
            
            ForkJoinPool pool = new ForkJoinPool();
            
            pool.execute(() -> {
                files.parallelStream().forEach(file -> {
                    try {
                        processFile(file);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            });
            
            pool.shutdown();
            pool.awaitQuiescence(1, TimeUnit.HOURS);
        }
    }
    
    private void processFile(Path file) throws IOException {
        // 处理文件逻辑
    }
}
```

## 三、ThreadLocal 详解

### 3.1 什么是 ThreadLocal

ThreadLocal 为每个线程提供独立的变量副本，实现线程隔离：

```java
public class ThreadLocalDemo {
    
    private static final ThreadLocal<DateFormat> dateFormat = 
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));
    
    public String formatDate(Date date) {
        return dateFormat.get().format(date);  // 每个线程独立使用
    }
    
    public void cleanup() {
        dateFormat.remove();  // 手动释放，防止内存泄漏
    }
}
```

### 3.2 ThreadLocal 的使用场景

**适用场景**：
- 数据库连接绑定到线程
- Session 管理
- 线程上下文信息传递
- 复杂参数传递（替代多层传参）

```java
// 场景 1：用户上下文
public class UserContext {
    private static final ThreadLocal<User> currentUser = new ThreadLocal<>();
    
    public static void set(User user) {
        currentUser.set(user);
    }
    
    public static User get() {
        return currentUser.get();
    }
    
    public static void clear() {
        currentUser.remove();
    }
}

// 使用
public class UserService {
    public void process() {
        User user = UserContext.get();  // 获取当前用户
        // 处理业务
    }
}

// 场景 2：数据库连接
public class DBContext {
    private static final ThreadLocal<Connection> connection = new ThreadLocal<>();
    
    public static Connection getConnection() {
        Connection conn = connection.get();
        if (conn == null) {
            conn = createConnection();
            connection.set(conn);
        }
        return conn;
    }
}
```

### 3.3 ThreadLocal 内存泄漏问题

**为什么会泄漏？**

```
ThreadLocal 的结构：
┌─────────────┐
│ ThreadLocal │ ──→ Entry[ThreadLocal, value]
│    对象      │
└─────────────┘
    Key 是弱引用，Value 是强引用
```

**泄漏原因**：
1. ThreadLocal 对象被 GC 回收后，Entry 的 key 变为 null
2. 但 value 仍然被 Entry 强引用，无法回收
3. ThreadLocalMap 持有对 Thread 的引用
4. Thread 存活期间，对应的 value 永远无法释放

**弱引用 vs 强引用**：
- 如果 key 是强引用：ThreadLocal 对象回收后，key 仍被 Entry 引用，无法回收
- 如果 key 是弱引用：ThreadLocal 对象回收后，key 变为 null，但 value 仍泄漏

### 3.4 正确使用 ThreadLocal

```java
public class SafeThreadLocal<T> {
    
    private final ThreadLocal<T> delegate = new ThreadLocal<>();
    
    public void set(T value) {
        delegate.set(value);
    }
    
    public T get() {
        return delegate.get();
    }
    
    // 必须在使用完毕后清理！
    public void remove() {
        delegate.remove();
    }
    
    // 建议使用 try-finally 确保清理
    public void execute(Consumer<T> action, T value) {
        try {
            set(value);
            action.accept(value);
        } finally {
            remove();  // 一定要清理！
        }
    }
}
```

### 3.5 实战：Web 请求上下文

```java
public class RequestContext {
    
    private static final ThreadLocal<RequestContextHolder> contextHolder = 
        new ThreadLocal<>();
    
    public static class RequestContextHolder {
        private String traceId;
        private String userId;
        private Map<String, Object> attributes = new HashMap<>();
        
        // 省略 getter/setter
    }
    
    // 设置上下文
    public static void set(RequestContextHolder context) {
        contextHolder.set(context);
    }
    
    // 获取上下文
    public static RequestContextHolder get() {
        return contextHolder.get();
    }
    
    // 清理（必须在过滤器/拦截器中调用）
    public static void clear() {
        contextHolder.remove();
    }
}

// Web 过滤器
@WebFilter(urlPatterns = "/*")
public class RequestContextFilter implements Filter {
    
    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, 
                         FilterChain chain) 
            throws IOException, ServletException {
        try {
            // 请求开始时设置上下文
            RequestContextHolder holder = new RequestContextHolder();
            holder.setTraceId(UUID.randomUUID().toString());
            RequestContext.set(holder);
            
            chain.doFilter(req, resp);
        } finally {
            // 请求结束后必须清理，防止内存泄漏
            RequestContext.clear();
        }
    }
}
```

### 3.6 InheritableThreadLocal

如果需要在子线程继承父线程的值，使用 InheritableThreadLocal：

```java
public class InheritableDemo {
    
    private static final InheritableThreadLocal<String> inheritable = 
        new InheritableThreadLocal<>();
    
    public static void main(String[] args) {
        inheritable.set("MainValue");
        
        // 子线程会自动继承父线程的值
        Thread child = new Thread(() -> {
            System.out.println(inheritable.get());  // 输出: MainValue
        });
        child.start();
    }
}
```

**注意**：线程池环境下 InheritableThreadLocal 不能自动继承，需要额外处理。

## 四、综合实战：微服务并发调用

```java
public class MicroserviceInvoker {
    
    private final Map<String, ExecutorService> servicePools = 
        new ConcurrentHashMap<>();
    
    public MicroserviceInvoker() {
        // 根据服务类型创建不同线程池
        servicePools.put("user", createPool(5));
        servicePools.put("order", createPool(10));
        servicePools.put("product", createPool(8));
    }
    
    private ExecutorService createPool(int size) {
        return new ThreadPoolExecutor(
            size, size, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadFactoryBuilder()
                .setNameFormat("microservice-" + size + "-%d")
                .build()
        );
    }
    
    // 并行调用多个服务
    public CompletableFuture<ServiceResult> invokeService(
            String serviceName, String endpoint, Object param) {
        
        ExecutorService pool = servicePools.get(serviceName);
        if (pool == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Unknown service: " + serviceName)
            );
        }
        
        return CompletableFuture.supplyAsync(() -> {
            // 模拟 RPC 调用
            return callRemoteService(endpoint, param);
        }, pool);
    }
    
    // 聚合调用
    public CompletableFuture<AggregatedResult> aggregate(
            String userId, String orderId, String productId) {
        
        CompletableFuture<UserInfo> userFuture = 
            invokeService("user", "/user/" + userId, null)
                .thenApply(result -> (UserInfo) result.getData());
        
        CompletableFuture<OrderInfo> orderFuture = 
            invokeService("order", "/order/" + orderId, null)
                .thenApply(result -> (OrderInfo) result.getData());
        
        CompletableFuture<ProductInfo> productFuture = 
            invokeService("product", "/product/" + productId, null)
                .thenApply(result -> (ProductInfo) result.getData());
        
        // 等待所有服务返回
        return CompletableFuture.allOf(userFuture, orderFuture, productFuture)
            .thenApply(ignored -> 
                new AggregatedResult(
                    userFuture.join(),
                    orderFuture.join(),
                    productFuture.join()
                )
            );
    }
    
    // 带超时的调用
    public <T> CompletableFuture<T> withTimeout(
            CompletableFuture<T> future, long timeoutMs) {
        
        return future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .exceptionally(ex -> {
                // 超时时返回默认值
                if (ex instanceof TimeoutException) {
                    return getDefaultValue();
                }
                throw new RuntimeException(ex);
            });
    }
    
    private ServiceResult callRemoteService(String endpoint, Object param) {
        // 实际 RPC 调用逻辑
        return new ServiceResult();
    }
    
    private <T> T getDefaultValue() {
        return null;
    }
}
```

## 五、总结与系列回顾

### 5.1 六篇文章回顾

| 篇目 | 主题 | 核心知识点 |
|------|------|------------|
| 第一篇 | 线程创建与生命周期 | Thread、Runnable、Callable、Future |
| 第二篇 | 线程同步机制 | synchronized、Lock、ReentrantLock、读写锁 |
| 第三篇 | 线程间通信 | wait/notify、Condition、并发工具类 |
| 第四篇 | JMM 与并发基础 | volatile、CAS、atomic 包、并发集合 |
| 第五篇 | 线程池深入 | ThreadPoolExecutor、队列、拒绝策略 |
| 第六篇 | 异步与高级主题 | CompletableFuture、Fork/Join、ThreadLocal |

### 5.2 实际项目最佳实践

1. **优先使用线程池**：避免手动创建线程，使用 ThreadPoolExecutor
2. **选择合适的队列**：根据任务特性选择有界队列
3. **CompletableFuture 优先**：异步编程的首选工具
4. **ThreadLocal 注意清理**：Web 应用必须在请求结束时清理
5. **监控线程状态**：使用 JMX 或自定义监控
6. **优雅关闭**：确保应用退出时正确关闭线程池

### 5.3 学习路线建议

```
基础 → 并发工具 → 线程安全 → 性能优化 → 源码分析

1. 掌握基础 API 使用
2. 理解底层原理（JMM、锁机制）
3. 学习并发工具类的源码
4. 在项目中实践并优化
5. 深入理解 JDK 源码实现
```

---

**相关阅读**：
- [Java 多线程（一）：线程创建与生命周期]({{< relref "post/java-multithreading-01" >}})
- [Java 多线程（二）：线程同步机制]({{< relref "post/java-multithreading-02" >}})
- [Java 多线程（三）：线程间通信与协作]({{< relref "post/java-multithreading-03" >}})
- [Java 多线程（四）：JMM 与并发基础]({{< relref "post/java-multithreading-04" >}})
- [Java 多线程（五）：线程池深入]({{< relref "post/java-multithreading-05" >}})