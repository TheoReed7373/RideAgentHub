# 第 2 章 请求怎么进来，数据怎么变形

> 第 1 章我们从业务上跟完了一次下单。这一章回答三个更底层的问题：一段 HTTP 文本怎么变成 Java 方法调用？数据在 DTO、实体、VO 之间怎么变形？配置值从哪里来？
>
> 本章不启动服务也能读完；所有"会返回什么"的结论都是**静态推导**，不是实际调用结果。

## 学习目标

读完本章，你应该能：

1. 拿到一个真实请求（方法 + 路径 + 参数位置），定位到具体的 Controller 方法、参数绑定方式和 Service 实现。
2. 区分 DTO、Entity、VO、Result 四种类型，并说清它们是**本项目的命名约定**，不是框架强制的流水线。
3. 完整追踪订单创建中一组字段的变化：JSON → DTO → 服务端覆盖/补全 → Entity → String 响应。
4. 找到配置的真实读取点，分清源码默认值、`application.yaml`、其它属性源（命令行、系统环境变量、导入文件）与运行时未知值。
5. 分别解释三件事：Java `String` 返回、`Long`/`BigDecimal` 的序列化规则、`Result.code` 与 HTTP 状态码的关系。

## 前置知识与阅读时间

- 需要：读完[第 1 章](01-project-map.md)；会 Java 类、接口、注解的基本读法；会在 IDE 里全局搜索与跳转。
- 不需要：Spring MVC 原理、AOP、Jackson、Lombok 的预先知识。本章第一次出现这些概念时就地解释。
- 阅读时间：约 70～90 分钟（含两个练习与自测）。
- **路径约定**：Java 类使用简写，基准是 `src/main/java/com/fancy/rideagenthub/`（例如 `controller/RideOrderController.java`）；资源与配置文件写完整仓库相对路径（例如 `src/main/resources/application.yaml`）。代码块注释里给完整路径。
- **证据约定**：标"框架行为"的，是 Spring / Spring Boot / Jackson / MyBatis 的规则，不是本仓库自己写的代码；涉及版本默认值的会注明版本与条件；标"静态推导，未实测"的，表示没有启动或发请求验证。

---

## 一、从同一个请求再问三个问题

第 1 章出现过这个请求（示意，未实际发送）：

```http
POST /order HTTP/1.1
Host: localhost:8080
Authorization: Bearer <token>
Content-Type: application/json

{
  "startAddress": "文三路地铁站",
  "startLat": 30.278900,
  "startLng": 120.128300,
  "endAddress": "萧山国际机场",
  "endLat": 30.229500,
  "endLng": 120.434200,
  "vehicleType": 1,
  "isExpedited": 0,
  "isReservation": 0
}
```

第 1 章回答了"业务上发生了什么"。现在换三个问题：

1. **这段文本是怎么变成 Java 对象的？** 谁读 JSON、谁创建 `CreateOrderDTO`、字段名怎么对上？
2. **服务器怎么知道要调用 `RideOrderController#create`，而不是同一个类里的 `detail`？** 路径和方法各自起什么作用？
3. **方法返回的 `String` 怎么变成响应体里的 `data`？** 为什么有时是字符串、有时是数字？

这三个问题对应本章的三条主线：**绑定 → 调用 → 序列化**，中间还夹着一条**配置**线（值从哪来）。

---

## 二、先走一遍：一条不依赖启动服务的调用链

先把正常路径走完，再逐段拆概念。下面每一站都标了"是本仓库代码还是框架"。

| # | 站点 | 做什么 | 本项目的证据 | 归属 |
|---|---|---|---|---|
| 1 | Servlet 容器 | 接收 TCP 连接，把 HTTP 文本解析成请求对象 | `pom.xml` 引入 `spring-boot-starter-web`；仓库没有自定义 Servlet | 框架 |
| 2 | `DispatcherServlet` | 前端控制器：把请求交给"处理器映射" | 仓库没有自定义 `DispatcherServlet`，用 Spring Boot 默认注册 | 框架 |
| 3 | `HandlerMapping` | 按**路径 + HTTP 方法**（以及 consumes/produces 等条件）找到目标方法 | `RideOrderController` 类上 `@RequestMapping("/order")`、方法上 `@PostMapping` | 框架机制 + 仓库声明 |
| 4 | 参数解析 | 把请求体/路径/查询参数转换成方法参数 | `@RequestBody`、`@PathVariable`、`@RequestParam` 的实际使用 | 框架机制 + 仓库声明 |
| 5 | Controller | 取身份、装配参数、调用 Service、包装响应 | `RideOrderController#create` | 仓库代码 |
| 6 | Service | 业务规则、补全、覆盖、调用 Mapper/外部服务 | `RideOrderServiceImpl#createOrder` | 仓库代码 |
| 7 | Mapper / 客户端 | 读写 MySQL、MongoDB、Redis 等 | `RideOrderMapper#insert`、`OrderRouteService#addOrder` | 仓库接口 + 框架代理 |
| 8 | 返回值转换 | 把 `Result` 写成 JSON | 仓库返回 `Result`；转换器由框架提供（Jackson） | 框架 |
| 9 | 异常出口 | 方法抛异常时统一转成 HTTP 状态 + `Result` | `exception/GlobalExceptionHandler` | 仓库代码 |

表中第 8 站的 Jackson 是 Spring Boot 默认使用的 JSON 库：请求体反序列化（JSON → Java 对象）和响应体序列化（Java 对象 → JSON）都由它完成。本章不要求你会配置它，只需要知道"文本 ↔ 对象"这一步是它做的。

**表里省略了两个拦截器站**（它们在第 3 站 `HandlerMapping` 之前执行，可以提前结束请求）：

- `RequestRateLimitInterceptor`：只挂 `/auth/**` 与 `/order/estimate`，按客户端 IP 的滑动窗口限流（60 秒 120 次），超限抛 `BusinessException(429)`；
- `AuthTokenInterceptor`：挂 `/**`，排除 `/error` 与 `/favicon.ico`，负责把 token 解析成 `UserTokenContext`；公开端点无 token 时也放行。

拦截器顺序由 `config/WebMvcConfig#addInterceptors` 的注册顺序决定，`@RequirePermission` 的角色检查则在控制器方法调用时由切面执行（第 5、6 章展开）。

对应到 `POST /order` 的真实顺序：

```text
HTTP 请求
  → RideOrderController#create(@RequestBody CreateOrderDTO)
  → UserTokenContext.getUserIdInString()           // 覆盖请求体里的 userId
  → RideOrderService#createOrder(CreateOrderDTO)   // 接口
  → RideOrderServiceImpl#createOrder(...)          // 实现
      → 校验/补全、算路、写 Mongo、写 MySQL、同步缓存
  → 返回 String（订单号）
  → Result.ok(orderId)
  → JSON 响应
```

第 1 章已经讲过这条链的业务含义（算路、写轨迹、副作用、失败清理），这里**不重复**；需要时回看[第 1 章第五节](01-project-map.md)。本章只关心"文本 ↔ 对象 ↔ 方法"的转换。

---

## 三、最少概念：HTTP 的四个部件与映射注解

### 3.1 一个请求由什么组成

| 部件 | 例子 | 在本项目里谁在用 |
|---|---|---|
| 方法（method） | `POST`、`GET` | 决定调用哪个方法；同一个路径可以有不同方法 |
| 路径（path） | `/order`、`/order/123` | 类上的前缀 + 方法上的路径 |
| 头（header） | `Authorization: Bearer xxx`、`Content-Type: application/json` | 身份由拦截器读（第 1 章 5.2）；`Content-Type` 决定请求体怎么解析 |
| 请求体（body） | 那段 JSON | `@RequestBody` 参数 |

查询参数（`?page=1&size=10`）不是独立部件，它附在路径后面，由 `@RequestParam` 读取。

### 3.2 映射注解：类上定前缀，方法上定后缀

```java
// src/main/java/com/fancy/rideagenthub/controller/RideOrderController.java（节选）
@RestController
@RequestMapping("/order")
@RequiredArgsConstructor
public class RideOrderController {

    private final RideOrderService rideOrderService;
    private final DriverLocationService driverLocationService;

    @PostMapping
    @RequirePermission
    public Result create(@RequestBody CreateOrderDTO req) {
        req.setUserId(UserTokenContext.getUserIdInString());
        String orderId = rideOrderService.createOrder(req);
        return Result.ok(orderId);
    }

    @GetMapping("/{orderId}")
    @RequirePermission
    public Result detail(@PathVariable String orderId) {
        RideOrderVO vo = rideOrderService.getOrderDetail(orderId);
        return Result.ok(vo);
    }
}
```

读法：

- 类上的 `@RequestMapping("/order")` 是**前缀**；方法上的 `@PostMapping` 没有写路径，等于后缀为空，最终路径就是 `/order`。
- `@GetMapping("/{orderId}")` 的后缀是 `/{orderId}`，最终 `/order/{orderId}`；`{orderId}` 是占位符，运行时匹配任意一段路径。
- `@PostMapping` / `@GetMapping` 是 `@RequestMapping(method = ...)` 的简写（框架行为）。`UserPOIController` 里还能看到 `@PutMapping`、`@DeleteMapping`。

**同一个路径、不同方法，是两个不同的端点。** 本仓库有一个现成例子：

```java
// controller/RideOrderController.java（节选）
@PostMapping("/driver/location")
@RequirePermission({"DRIVER"})
public Result reportDriverLocation(@RequestBody DriverLocationReqDTO req) { ... }

@GetMapping("/driver/location")
@RequirePermission({"DRIVER"})
public Result driverLocation() { ... }
```

`POST /order/driver/location` 上报位置，`GET /order/driver/location` 查询状态。只看路径会把它们混成一个，必须把方法一起看。

> 框架行为说明：Spring MVC 在启动时把每个处理器方法登记成一组**映射条件**——路径、HTTP 方法、`params`（请求参数条件）、`headers`、`consumes`（请求体类型）、`produces`（响应类型）以及自定义条件。判断两条映射是否"重复"，比较的是这整组条件，而不是只看路径和方法（Spring Framework 6.2.15 的 `RequestMappingInfo#equals` 就是逐项比较这些字段）。
>
> 所以：**路径与方法相同、但 `params` 或 `headers` 不同的两个方法可以共存**，框架按请求实际带的参数/头选择匹配的那条；只有全部条件都一样，才构成重复映射。
>
> 教学假设（本项目没有这样的端点）：同一个类里两个方法都写 `@PostMapping("/demo")`，一个加 `params = "type=a"`，另一个加 `params = "type=b"`。`POST /demo?type=a` 与 `?type=b` 会分别命中；只有把 `params` 也写成一样，启动时才会因重复映射失败。这段是**假设示例**，不要当成仓库现有接口。

### 3.3 参数注解决定"数据从哪来"

| 注解 | 数据来源 | 本仓库的真实端点 | 绑定失败时（框架抛出的异常） |
|---|---|---|---|
| `@RequestBody` | 请求体，按 `Content-Type` 反序列化 | `POST /order` → `CreateOrderDTO` | 请求体无法解析（如 JSON 语法错误、已知字段类型不匹配）→ `HttpMessageNotReadableException` |
| `@PathVariable` | 路径里的占位符 | `GET /order/{orderId}` → `String orderId`；`GET /user/poi/{id}` → `Long id` | 类型转换失败 → `MethodArgumentTypeMismatchException` |
| `@RequestParam` | 查询参数（或表单） | `GET /order/driver/tasks?isFinished=false` → `Boolean` | 缺失 → `MissingServletRequestParameterException`；类型错 → 同上一条的 `MethodArgumentTypeMismatchException` |
| 不加注解的简单类型 | 查询参数（按名称） | 本仓库控制器中没有看到这种写法 | — |
| `@ModelAttribute` / `@RequestHeader` | 表单对象 / 请求头 | 本仓库控制器**未使用** | — |

表中"绑定失败"列只说明框架会抛什么异常，**不代表本仓库一定把它们都映射成 400**；实际映射见 7.2，且只覆盖 `GlobalExceptionHandler` 显式列出的类型。

最后两行是"本仓库没有"，不是"框架没有"。写教材时把"仓库没用到"和"框架不支持"分开，是这个项目里很容易犯的错。

---

## 四、参数绑定：先分清"绑不上"和"绑上了但不合格"

日常说的"参数有问题"其实是两件事，发生位置和结果都不同：

| 类型 | 什么时候发生 | 方法体执行了吗 | 本仓库的结果 |
|---|---|---|---|
| **绑定/转换失败** | 框架把 HTTP 文本变成方法参数时失败：JSON 不合法、已知字段类型不匹配、路径变量不是数字、缺少必填查询参数 | 没有 | 由 `GlobalExceptionHandler` 映射（见 7.2） |
| **绑定成功、业务校验失败** | JSON 合法、类型也对，字段进了 DTO，但缺失或越界 | 已经执行 | 由代码判断并抛异常（见 4.2） |

这一节先把第一种讲清楚，再用 `POST /order` 的真实反例说明第二种。

### 4.1 三种真实写法

```java
// controller/RideOrderController.java（节选）
@GetMapping("/driver/tasks")
@RequirePermission({"DRIVER"})
public Result driverTasks(@RequestParam Boolean isFinished) {
    Long driverId = UserTokenContext.getUserIdInLong();
    List<RideOrderVO> list = rideOrderService.getDriverOrderTasks(String.valueOf(driverId), isFinished);
    return Result.ok(list);
}

@GetMapping("/driver/pool/page")
@RequirePermission({"DRIVER"})
public Result driverPool(@RequestParam Integer page, @RequestParam Integer size) { ... }
```

```java
// controller/UserPOIController.java（节选）
@GetMapping("/{id}")
@RequirePermission({"USER"})
public Result get(@PathVariable Long id) {
    UserPOI poi = userPOIService.findById(id);
    if (poi == null) {
        throw new com.fancy.rideagenthub.exception.BusinessException(404, "兴趣点不存在");
    }
    return Result.ok(poi);
}

@PostMapping
@RequirePermission({"USER"})
public Result create(@RequestBody POIInfoVO poiInfo) {
    userPOIService.createForCurrentUser(new UserPOI(poiInfo));
    return Result.ok();
}
```

三处细节值得注意：

1. `@RequestParam Boolean isFinished` 没有 `required = false`，默认必填。少传 `isFinished` 会在进入方法体前失败。
2. `@PathVariable Long id` 声明为 `Long`，所以路径必须是数字。`GET /user/poi/abc` 会在转换阶段失败，不会执行 `findById`。
3. `create` 的请求体类型叫 `POIInfoVO`——名字里带 VO，却被当作输入。**这说明 DTO/VO 是命名约定，不是编译期约束。** 本章第五节会展开。

需要可选参数时，仓库里也有写法：`TicketController#assign` 的 `@RequestParam(required = false) Long handlerId`。

### 4.2 绑上了但不合格：`POST /order` 的真实反例

假设请求已通过登录鉴权，请求体是合法 JSON，字段类型也都对得上，只是没有 `startLat` / `startLng`（示意，未实际调用）：

```json
{ "startAddress": "文三路地铁站", "endAddress": "萧山国际机场", "endLat": 30.229500, "endLng": 120.434200, "vehicleType": 1 }
```

`CreateOrderDTO.startLat` 声明为 `BigDecimal`，缺失时绑定结果是 `null`——**这不是绑定失败**，请求已经进入 `RideOrderController#create`，DTO 也构造出来了。随后：

```java
// service/impl/RideOrderServiceImpl.java#createOrder（节选）
CreateOrderDTO.ValidationResult result = dto.validateAndSupplement(amapGeoRegeoService);
if (!result.isComplete()) {
    throw new IllegalArgumentException("订单信息不完整，缺失: " + result.missingFields());
}
```

`validateAndSupplement` 的前两段校验把缺失的经纬度加入 `missingFields`（`CreateOrderDTO` 里 `startLat == null || startLng == null` 等判断），`isComplete()` 返回 false，Service 抛 `IllegalArgumentException`；`GlobalExceptionHandler#handleIllegalArgument` 把它映射成 400。这里 `missingFields` 是列表，按当前代码拼接后，响应体的 `message` 静态预期为 `订单信息不完整，缺失: [startLat/startLng]`。

这条链说明：

- 这个 400 来自**业务校验**，不是框架绑定；方法体执行了，DTO 和 `ValidationResult` 都构造出来了。
- 控制器参数上没有 `@Valid` / `@Validated`，也没有 Bean Validation 注解；缺失字段不会在绑定阶段被拦下，而是以 `null` 进入方法体（本章涉及的控制器均如此）。
- 缺少 `userId` 时更早：控制器先从 token 写入，Service 再检查，为空抛 `BusinessException(401)`。
- 如果请求体本身不是合法 JSON，才回到"绑定失败"，见 4.3。

### 4.3 未知字段：当前版本默认忽略，但有条件

如果请求体里多写一个 `CreateOrderDTO` 不存在的字段，会发生什么？

先看项目这一侧的事实：

- `application.yaml` 没有 `spring.jackson.*` 配置；
- `config/Config` 只有一个 `Jackson2ObjectMapperBuilderCustomizer`，它只注册 `Long` / `BigDecimal` 的序列化器，没有改反序列化 feature；
- 没有自定义 `ObjectMapper` 或 `Jackson2ObjectMapperBuilder` Bean；
- `CreateOrderDTO` 类上没有 `@JsonIgnoreProperties`。

再看框架这一侧（版本可查）：

- 本项目使用 Spring Boot 3.5.9（`pom.xml` 的 parent），它管理的 Spring Framework 版本是 6.2.15。
- Spring Framework 6.2.15 的 `Jackson2ObjectMapperBuilder#customizeDefaultFeatures`：如果调用方没有在 features 里显式设置 `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES`，就把它设为 `false`。
- Spring Boot 3.5.9 的 `JacksonAutoConfiguration` 用这个 builder 构建默认 `ObjectMapper`，并把容器里的 `Jackson2ObjectMapperBuilderCustomizer` 应用上去；本项目的 customizer 没有覆盖该 feature。

**结论（有条件）**：在上述默认自动配置、没有外部属性或其他定制覆盖的前提下，当前版本的多余未知 JSON 字段默认被**忽略**，不会报错。

这是"核验版本源码 + 核对项目配置"后的**静态推导**，不是实际发请求的结果。部署时设置 `spring.jackson.deserialization.fail-on-unknown-properties=true` 可以启用未知字段失败；自行注册 `ObjectMapper` 或 `Jackson2ObjectMapperBuilder` 也可能因其具体配置改变结果。仅在 DTO 上添加 `@JsonIgnoreProperties(ignoreUnknown = false)`，不会单独覆盖全局禁用的 `FAIL_ON_UNKNOWN_PROPERTIES`；仍需看 ObjectMapper 的实际设置。

对比"未知字段"与"已知字段类型错误"（示意）：

| 请求体 | 结果 |
|---|---|
| `{"startLat": 30.1, "foo": "bar"}` | `foo` 不认识，默认被忽略，正常绑定 |
| `{"startLat": "abc"}` | `startLat` 是已知字段，`"abc"` 无法转成 `BigDecimal` → `HttpMessageNotReadableException` → 400 |

一句话：**"不认识"和"认识但转不过去"是两种失败**。前者默认被跳过，后者在绑定阶段就失败。

如果希望显式控制，配置键是 `spring.jackson.deserialization.fail-on-unknown-properties`（Spring Boot 配置项，当前仓库没有设置）。

资料来源（2026-11-14 查阅版本源码）：

- Spring Framework v6.2.15 `Jackson2ObjectMapperBuilder`：<https://github.com/spring-projects/spring-framework/blob/v6.2.15/spring-web/src/main/java/org/springframework/http/converter/json/Jackson2ObjectMapperBuilder.java>
- Spring Boot v3.5.9 `JacksonAutoConfiguration`：<https://github.com/spring-projects/spring-boot/blob/v3.5.9/spring-boot-project/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/jackson/JacksonAutoConfiguration.java>

### 4.4 同一份 JSON，字段名怎么对上

`CreateOrderDTO` 的字段名与 JSON 的键一一对应（`startAddress` 对 `startAddress`）。Jackson 默认按属性名匹配（框架行为）。**就这个 DTO 而言**，没有使用 `@JsonProperty` 重命名，也没有类级 `@JsonIgnoreProperties`；仓库层面也没有配置全局命名策略。

但不要把"这个 DTO 没有"推广成"全仓库没有"：高德、天气的响应 POJO 大量使用 `@JsonProperty` 把下划线字段映射成驼峰（例如 `agentbase/amap/pojo/georegeo/Regeocode` 的 `@JsonProperty("formatted_address")`），不少还标了 `@JsonIgnoreProperties(ignoreUnknown = true)`（如 `agentbase/amap/pojo/search/AmapSearchPoi`）。同一个项目里允许不同风格并存。

因此：**请求 JSON 的键必须和对应 DTO 的字段名或 `@JsonProperty` 名称一致**（默认大小写敏感）。改字段名等于改接口契约，不能只改 Java 类。

---

## 五、三种对象与一个包装：DTO / Entity / VO / Result

### 5.1 先给定义，再看它们在本项目里怎么用

| 类型 | 一句话定义 | 常见位置 | 谁创建 |
|---|---|---|---|
| DTO | 承载一次请求的输入 | `domain/dto/`、控制器内部静态类 | 框架反序列化 |
| Entity | 对应数据库一行 | `domain/entity/`，配 `@TableName` | Service 用 Builder 构造 |
| VO | 承载一次响应的输出 | `domain/vo/` | Service 手动转换 |
| Result | 统一响应外壳 | `domain/response/Result` | Controller |

**关键纠正**：这四类不是一条必经流水线。一个接口可能只用 DTO + Result（如 `POST /order`），也可能 Entity 直接当响应（如 `GET /user/poi`），还可能 VO 当输入（如 `POST /user/poi`）。

### 5.2 DTO：`CreateOrderDTO`

```java
// src/main/java/com/fancy/rideagenthub/domain/dto/CreateOrderDTO.java（节选）
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Slf4j
public class CreateOrderDTO {
    private String userId;
    private String mongoTraceId;
    private Integer vehicleType;
    private Integer isReservation;
    private Integer isExpedited;
    private LocalDateTime scheduledTime;
    private String startAddress;
    private BigDecimal startLat;
    private BigDecimal startLng;
    private String endAddress;
    private BigDecimal endLat;
    private BigDecimal endLng;
    private BigDecimal estPrice;
    private BigDecimal estDistance;
    private BigDecimal radio;
}
```

三个观察：

- 所有字段都是**引用类型**：包装类型（`Integer`）、`String`、`BigDecimal`、`LocalDateTime`，没有 `int` / `long` 这类基本类型。引用类型可以为 `null`，这样"没传"和"传了 0"能区分开——后面补默认值时才不会误判。
- `userId` 是 `String`，实体里是 `Long`。转换发生在 Service（`convertToLong`）。
- 出现了 `estPrice`、`estDistance`、`radio`，但它们在服务端会被**重新计算并覆盖**（5.4）。

### 5.3 Entity：`RideOrder`

```java
// src/main/java/com/fancy/rideagenthub/domain/entity/RideOrder.java（节选）
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_ride_order")
public class RideOrder implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField("order_id")
    private Long orderId;
    @TableField("user_id")
    private Long userId;
    @TableField("driver_id")
    private Long driverId;
    @TableField("mongo_trace_id")
    private String mongoTraceId;
    @TableField("order_status")
    private Integer orderStatus;
    @TableField("safety_code")
    private String safetyCode;
    // ……还有车型、预约、时间戳、起终点、金额分项、isDeleted 等字段
}
```

与 DTO 的差别不只是字段多少：Entity 有 `orderId`、`safetyCode`、`orderStatus`、金额分项、逻辑删除位——这些**请求里根本没有**，是服务端生成的。

### 5.4 字段追踪：从 JSON 到实体

把 `POST /order` 的每个输入字段跟到底（源码：`RideOrderController#create`、`RideOrderServiceImpl#createOrder`、`CreateOrderDTO#validateAndSupplement`）：

| JSON 字段 | 在 DTO 里 | 服务端做了什么 | 落到 Entity |
|---|---|---|---|
| `userId`（即使传了） | `String` | 控制器用 token 覆盖；Service 在已登录时再覆盖一次；转 `Long` | `RideOrder.userId` |
| `startAddress` / `endAddress` | `String` | 为空时调用逆地理编码补全（最多 3 次，失败只告警） | 同名 `String` 字段 |
| `startLat` / `startLng` / `endLat` / `endLng` | `BigDecimal` | 校验非空与范围（纬度 ±90、经度 ±180） | 同名 `BigDecimal` 字段 |
| `vehicleType` | `Integer` | 缺省补 1；校验 1～3 | 同名，缺省再兜底 1 |
| `isReservation` | `Integer` | 缺省补 0；为 0 时清空 `scheduledTime` | 同名 |
| `scheduledTime` | `LocalDateTime` | 预约单要求晚于当前时间 | 同名 |
| `isExpedited` | `Integer` | 缺省补 0 | 同名 |
| `mongoTraceId`（即使传了） | `String` | 生成新 `UUID` 覆盖 | 同名 |
| `estDistance` | `BigDecimal` | 用高德返回的里程覆盖 | `estDistance` |
| `estPrice` | `BigDecimal` | 服务端重算后覆盖 | `estPrice` |
| `radio` | `BigDecimal` | 服务端重算后覆盖 | `priceRadio` |
| —（请求里没有） | — | 雪花 ID | `orderId` |
| — | — | 随机安全码 | `safetyCode` |
| — | — | 固定 `CREATED`（10） | `orderStatus` |
| — | — | 创建/更新时间；其余时间留空 | 时间字段 |
| — | — | 金额分项置 0、`isDeleted=0` | 金额分项、`isDeleted` |

结论：**请求体只提供"素材"，最终值由服务端决定**。这也是第 1 章"服务端定权威"在类型层面的体现。

对应的实体构造（节选，省略号处是逐字段赋值）：

```java
// service/impl/RideOrderServiceImpl.java#createOrder（节选）
RideOrder order = RideOrder.builder()
        .orderId(orderId)                                   // 雪花 ID
        .userId(convertToLong(validatedDto.getUserId()))     // DTO 的 String → Long
        .driverId(null)
        .mongoTraceId(normalizeText(validatedDto.getMongoTraceId()))
        .vehicleType(validatedDto.getVehicleType() == null ? 1 : validatedDto.getVehicleType())
        .safetyCode(generateSafetyCode())                    // 服务端生成
        .orderStatus(RideOrderStatus.CREATED.getCode())      // 固定 10
        .createTime(com.fancy.rideagenthub.util.BusinessTime.now())
        // ……起终点、预约时间、其他时间字段
        .estDistance(validatedDto.getEstDistance())
        .estPrice(validatedDto.getEstPrice())
        .priceBase(BigDecimal.ZERO)
        .priceRadio(validatedDto.getRadio() == null ? BigDecimal.ZERO : validatedDto.getRadio())
        .isDeleted(0)
        .build();
```

### 5.5 VO：详情接口另走一条路，`POST /order` 不返回它

`RideOrderVO` 的 `orderId` / `userId` / `driverId` 都是 `String`，还多了 `estRoute`、`realRoute` 两个从 MongoDB 拼进来的字段。

```java
// service/impl/RideOrderServiceImpl.java#toDetailVO（节选）
private RideOrderVO toDetailVO(RideOrder order, OrderRoute routeLine) {
    return RideOrderVO.builder()
            .orderId(order.getOrderId() != null ? order.getOrderId().toString() : null)
            .userId(order.getUserId() != null ? order.getUserId().toString() : null)
            .driverId(order.getDriverId() != null ? order.getDriverId().toString() : null)
            .estRoute(routeLine == null ? null : routeLine.getEstPolyline())
            .realRoute(routeLine == null ? null : routeLine.getRealPolyline())
            .vehicleType(order.getVehicleType())
            // ……其余字段逐个赋值
            .build();
}
```

调用链是 `RideOrderController#detail` → `RideOrderServiceImpl#getOrderDetail` → `getRequiredOrder`（查库，不存在抛 404）→ `assertCanViewOrder`（越权抛 403）→ `orderRouteService.getByTraceId`（查 Mongo）→ `toDetailVO`。

**所以不要把两个接口混成一条流水线**：

| 接口 | 输入 | 输出 | 经过 VO 吗 |
|---|---|---|---|
| `POST /order` | `CreateOrderDTO` | `String`（订单号） | 不经过 |
| `GET /order/{orderId}` | 路径参数 `String` | `RideOrderVO` | 经过 |
| `POST /order/page` | `OrderPageReqDTO`（控制器内部类） | `PageResult<RideOrderVO>` | 经过 |
| `GET /user/poi` | 无 | `List<UserPOI>`（实体直接当响应） | 不经过 |

最后一行是本章要求你记住的反例：**Entity 也可以直接返回**，它不是"只能在 Service 内部使用"。

### 5.6 Result：统一外壳

```java
// src/main/java/com/fancy/rideagenthub/domain/response/Result.java（节选）
@Data
@AllArgsConstructor
public class Result {
    private int code;
    private String message;
    private Object data;
    private Long total;

    public static Result ok() { return new Result(200, "success", null, 0L); }
    public static Result ok(Object data) { return new Result(200, "success", data, 0L); }
    public static Result fail(int code, String message) { return new Result(code, message, null, 0L); }
}
```

`data` 声明为 `Object`，所以同一个外壳能装 `String`、VO、`List`、`PageResult`。代价是编译期看不出里面是什么——读代码时要顺控制器看 `Result.ok(...)` 的参数类型。

分页响应再套一层 `PageResult{page,size,total,records}`（`domain/response/PageResult`）。

---

## 六、对象是谁创建的：注解、接口、注入与 Lombok

### 6.1 `@RestController` 与 `@Service` 是"注册 + 语义"

- `@RestController` = `@Controller` + `@ResponseBody`（框架定义）。它告诉 Spring：这个类要注册成 Bean（Bean = 由 Spring 容器创建和管理的对象），并且方法返回值直接写响应体，而不是当视图名。
- `@Service` 是 `@Component` 的特化，语义上表示"业务层"，对容器行为没有额外影响（框架行为）。

两者都靠**组件扫描**注册。本项目的启动类在 `com.fancy.rideagenthub` 包下：

```java
// src/main/java/com/fancy/rideagenthub/RideAgentHubApplication.java
@SpringBootApplication
@EnableScheduling
public class RideAgentHubApplication {
    public static void main(String[] args) {
        SpringApplication.run(RideAgentHubApplication.class, args);
    }
}
```

`@SpringBootApplication` 隐含 `@ComponentScan`，默认扫描启动类所在包及其子包（框架行为）。所以 `controller/`、`service/`、`config/` 下的类会被扫到——**前提是它们带了 `@Component` 系列注解**。

### 6.2 构造器注入：`@RequiredArgsConstructor` 生成了构造器

```java
// controller/RideOrderController.java（节选）
@RestController
@RequestMapping("/order")
@RequiredArgsConstructor
public class RideOrderController {
    private final RideOrderService rideOrderService;
    private final DriverLocationService driverLocationService;
    // ……
}
```

类里没有写构造器，但 Lombok 的 `@RequiredArgsConstructor` 会为**尚未初始化的 `final` 字段**（以及标注了 `@NonNull` 的字段）生成构造器参数；在声明处已经赋过初值的 `final` 字段不参与，`static` 字段也不参与。本项目这两个字段都是未初始化的 `final`，所以正好都进入构造器。Spring 看到"只有一个构造器"就用它注入（框架行为）。

Lombok 只是**编译期生成代码**：源码里看不到 `getXxx()`、`builder()`、构造器，但编译后的 class 里有。阅读方法：

- 看到 `@Data` → 有 getter/setter/`toString`/`equals`/`hashCode`；
- 看到 `@Builder` → 有 `Xxx.builder()` 和链式 setter；
- 看到 `@RequiredArgsConstructor` → 有"未初始化 final 字段 + `@NonNull` 字段"的构造器（已初始化的 final 与 static 字段不参与）；
- 看到 `@Slf4j` → 有一个 `log` 字段；
- IDE 里用"Structure / 结构"视图能看到生成后的成员；命令行可用 `javap` 看字节码（本章不要求）。

### 6.3 接口 + 实现：注入的是接口，注册的是实现

`RideOrderController` 声明的字段类型是接口 `RideOrderService`，但容器里实际注入的是 `RideOrderServiceImpl`：

```java
// service/impl/RideOrderServiceImpl.java（节选）
@Service
@RequiredArgsConstructor
@Slf4j
public class RideOrderServiceImpl implements RideOrderService {
    // ……
}
```

为什么能对上？因为 `RideOrderServiceImpl` 带了 `@Service`，被扫描成 Bean；它实现了 `RideOrderService`，按类型查找时正好匹配（框架行为）。本仓库里这个接口**只有一个实现**，所以不需要 `@Qualifier` 或 `@Primary`。

反例更能说明"接口不一定是 `@Service` 类"：`PaymentVerifier` 接口的 Bean 是在配置类里用 `@Bean` 方法返回的：

```java
// config/PaymentConfig.java（节选，省略了方法上方的一行注释）
@Bean
@ConditionalOnMissingBean(PaymentVerifier.class)
PaymentVerifier unavailablePaymentVerifier() {
    return (orderId, userId, channel, tradeNo, amount) -> {
        throw new BusinessException(503, "支付渠道尚未接入，无法核验支付结果");
    };
}
```

`RideOrderServiceImpl` 通过字段注入拿到它：

```java
@jakarta.annotation.Resource
private com.fancy.rideagenthub.service.PaymentVerifier paymentVerifier;
```

同一个类里出现了两种注入风格：**构造器注入**（`final` 字段 + Lombok）与**字段注入**（`@Resource`）。两者都能工作，但构造器注入能在编译期暴露依赖、便于测试；字段注入更短。仓库没有统一，读代码时两种都要认。

### 6.4 Mapper：没有实现类的接口

```java
// mapper/RideOrderMapper.java
@Mapper
public interface RideOrderMapper extends BaseMapper<RideOrder> {
}
```

这个接口没有实现类。`@Mapper` 让 MyBatis 为它生成代理对象并注册成 Bean（框架行为），代理负责把方法调用翻译成 SQL。所以 `RideOrderServiceImpl` 能像普通依赖一样注入它。

**不要把 `LambdaQueryWrapper` 当成 Mapper**：它是 MyBatis-Plus 的条件构造器，出现在 Service 里，不是 Bean。

### 6.5 顺带一句：AOP 是什么

`@RequirePermission` 能生效，是因为 `aspect/PermissionAspect` 用 `@Around` 包住了带注解的方法（AOP：在不改方法代码的前提下，在方法前后插入逻辑）。本章只需要知道"进入方法体前可能还有一层"，具体规则在 `01-auth/02-authorization-and-defense.md`。不要把 AOP 和参数绑定混为一谈：绑定是框架解析参数，AOP 是方法调用前后的拦截。

---

## 七、返回值、HTTP 状态与异常

### 7.1 成功分支

`POST /order` 成功时，Controller 返回 `Result.ok(orderId)`。响应体（示意）：

```json
{ "code": 200, "message": "success", "data": "1837465928374652928", "total": 0 }
```

HTTP 状态码由框架默认设为 200（方法上没有 `@ResponseStatus`，框架行为）。**`Result.code` 与 HTTP 状态是两个字段**，只是在这个成功分支上恰好都是 200。

### 7.2 绑定失败：发生在方法体之前

下表只列本仓库 `GlobalExceptionHandler` **显式处理**的绑定类异常。它们不会进入 Controller 方法，也不会走 Service 的业务判断：

| 情况 | 触发的异常（框架） | 本仓库的处理结果 |
|---|---|---|
| 请求体不是合法 JSON，或已知字段类型不匹配 | `HttpMessageNotReadableException` | 400，`message="请求参数格式不正确"` |
| 路径变量类型不对（如 `/user/poi/abc`） | `MethodArgumentTypeMismatchException` | 400，同上 |
| 缺少必填查询参数（如 `/order/driver/tasks` 不带 `isFinished`） | `MissingServletRequestParameterException` | 400，同上 |

两点边界：

- 处理器还捕获 `MethodArgumentNotValidException`（Bean Validation 的失败）。但本章涉及的控制器参数上没有 `@Valid` / `@Validated`，这条分支走不到；如果以后加了校验注解，它才会生效。
- 不要把"400"推广成"所有请求错误都是 400"：没登录是 401、越权是 403、资源不存在是 404，来源是切面或 Service，见 7.3。

### 7.3 业务拒绝：发生在方法体/Service 里

```java
// service/impl/RideOrderServiceImpl.java#getRequiredOrder（节选）
private RideOrder getRequiredOrder(String orderId) {
    RideOrder order = rideOrderMapper.selectOne(
            new LambdaQueryWrapper<RideOrder>()
                    .eq(RideOrder::getOrderId, orderId)
                    .eq(RideOrder::getIsDeleted, 0));
    if (order == null) {
        throw new BusinessException(404, "订单不存在");
    }
    return order;
}
```

`GET /order/{orderId}` 查不到订单 → `BusinessException(404)`；查到但不是自己的单 → `BusinessException(403, "无权限查看该订单")`。这两类都发生在方法体里。

### 7.4 异常怎么变成 HTTP 状态

```java
// src/main/java/com/fancy/rideagenthub/exception/GlobalExceptionHandler.java（节选）
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Result> handleUnauthorized(UnauthorizedException e) { return response(401, e.getMessage()); }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result> handleBusiness(BusinessException e) { return response(e.getCode(), e.getMessage()); }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result> handleIllegalArgument(IllegalArgumentException e) { return response(400, e.getMessage()); }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result> handleInvalidRequest(Exception e) { return response(400, "请求参数格式不正确"); }

    private ResponseEntity<Result> response(int code, String message) {
        int status = code >= 400 && code <= 599 ? code : 400;
        return ResponseEntity.status(status).body(Result.fail(code, message));
    }
}
```

读法：

- `@RestControllerAdvice` 是**本仓库选择的**全局异常出口：它把 `@ExceptionHandler` 方法应用到所有控制器。框架同样支持在某个控制器内部写 `@ExceptionHandler` 只处理该控制器的异常，本仓库没有采用；不能把当前的全局写法当成唯一方案。
- `BusinessException` 的 `code` 直接当 HTTP 状态（400～599 时），所以 `404`、`403`、`409`、`503` 都会"原样"变成 HTTP 状态；响应体里的 `code` 与 HTTP 状态一致。
- 其它未列出的异常落到兜底 500（本类最后还有一个 `@ExceptionHandler(Exception.class)`）。

**归纳三句话**：

1. 本仓库列出的那几类绑定异常由框架在方法体前抛出，并在 `GlobalExceptionHandler` 里被映射成 400；
2. 业务拒绝 = 代码主动抛 `BusinessException(code, ...)`，HTTP 状态跟着 code 走；字段绑上了但不合格由业务校验抛 `IllegalArgumentException`，也映射 400，但原因不同（见 4.2）；
3. `Result.code` 是响应体字段，成功时是 200；它不是 HTTP 状态的替代品，只是异常分支里两者被对齐了。

---

## 八、配置：从 `application.yaml` 到代码

### 8.1 三种读取方式，本项目都有实例

| 方式 | 例子 | 注册/生效方式 | 键是否在 yaml 里 |
|---|---|---|---|
| `@ConfigurationProperties` | `config/AuthProperties`（`prefix="auth"`） | 类上有 `@Component`，由组件扫描注册后绑定 | 是 |
| `@Value` | `config/IdGeneratorConfig` 的 `${id.worker-id:1}` | Bean 方法参数上的占位符 | **否**（只有代码默认值） |
| 占位符 | `${MYSQL_USERNAME}` | `application.yaml` 里的 `${...}`，由 Spring Environment 从各属性源解析 | 是（值来自命令行/系统环境变量/导入文件等属性源） |

### 8.2 `@ConfigurationProperties`：一个配置类怎么被绑定

```java
// src/main/java/com/fancy/rideagenthub/config/AuthProperties.java
@Data
@Component
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {
    private long tokenTtlSeconds = 604800;
    private long emailCodeTtlSeconds = 300;
    private long emailCodeCooldownSeconds = 60;
}
```

对应 `src/main/resources/application.yaml`：

```yaml
auth:
  token-ttl-seconds: 604800          # Token 有效期: 7 天
  email-code-ttl-seconds: 300        # 验证码有效期: 5 分钟
  email-code-cooldown-seconds: 60    # 验证码发送冷却: 60 秒
```

绑定规则（Spring Boot 框架行为，配合仓库注解）：

- `@Component` 让这个类成为 Bean；`@ConfigurationProperties` 让 Spring Boot 把 `auth.*` 下的键绑定到同名字段。仓库里**没有** `@EnableConfigurationProperties`：Spring Boot 的自动配置已经提供了绑定基础设施，这个类自己又通过 `@Component` 成了 Bean，所以不需要再显式注册（框架行为）。
- 键名用 kebab-case（`token-ttl-seconds`）对应 Java 的驼峰（`tokenTtlSeconds`），这是宽松绑定。
- 字段初始值是**源码默认值**：如果没有任何属性源给 `auth.token-ttl-seconds` 赋值（yaml 里没有这一行，外部也没有覆盖），值才是 604800。yaml 写了同值，只是把默认值显式化。

#### 8.2.1 一条完整的绑定与消费链

把"声明 → 绑定 → 注入 → 读取 → 影响行为"串起来。

```java
// 教学简化示意：service/impl/TokenServiceImpl.java#saveToken
// keys 与 json 代表省略的实际参数构造，不是源码中的局部变量
@Service
@RequiredArgsConstructor
public class TokenServiceImpl implements TokenService {
    private final AuthProperties authProperties;   // 构造器注入（Lombok 生成）

    @Override
    public void saveToken(UserToken token) {
        // ……
        redisTemplate.execute(SAVE, keys, json,
                String.valueOf(authProperties.getTokenTtlSeconds()),   // ← 作为 TTL 参数
                token.getToken(), String.valueOf(token.getSessionVersion()));
    }
}
```

```java
// service/impl/AuthServiceImpl.java#issueLoginResultVO（节选）
long expiresAt = now + authProperties.getTokenTtlSeconds() * 1000;
UserToken userToken = UserToken.builder()
        // ……
        .expiresAtEpochMs(expiresAt)
        .build();
tokenService.saveToken(userToken);
return LoginResultVO.builder()
        // ……
        .expiresInSec(authProperties.getTokenTtlSeconds())
        .build();
```

逐步读：

1. **声明**：`AuthProperties` 用 `@ConfigurationProperties(prefix = "auth")` + `@Component`；字段 `tokenTtlSeconds` 对应键 `auth.token-ttl-seconds`，初始值 604800。
2. **绑定**：应用启动时，Binder 从属性源里取 `auth.token-ttl-seconds`；取到就写入字段，取不到就保留初始值。
3. **注入**：`TokenServiceImpl` 声明 `private final AuthProperties authProperties`，`@RequiredArgsConstructor` 生成构造器参数，Spring 从容器取这个 Bean 注入；`AuthServiceImpl` 是显式构造器参数（`AuthServiceImpl` 的构造器声明在类开头）。
4. **读取**：两处都调用 `getTokenTtlSeconds()`（`@Data` 生成）。
5. **影响**：`AuthServiceImpl` 用它算出 `expiresAtEpochMs`（当前时间 + TTL），并把同一个值作为 `expiresInSec` 返回；`TokenServiceImpl#saveToken` 把它作为 Redis 脚本的 TTL 参数（脚本细节留到 `00-foundations/04-redis-flux-and-sse-basics.md`，本章不展开）。

所以修改 `auth.token-ttl-seconds` 会同时改变**令牌在 Redis 里的过期时间**和**登录响应里的 `expiresInSec`**；两处读同一个 getter，不会各自漂移。

另一个配置类多了启动校验：

```java
// config/RideOrderTimeoutProperties.java（节选）
@Data
@Component
@ConfigurationProperties(prefix = "ride-order.timeout")
public class RideOrderTimeoutProperties {
    private boolean enabled = true;
    private long acceptTimeoutMinutes = 15;
    // ……
    @PostConstruct
    public void validate() {
        if (acceptTimeoutMinutes <= 0 || arriveTimeoutMinutes <= 0 || payTimeoutMinutes <= 0
                || pollIntervalMs <= 0 || batchSize <= 0) {
            throw new IllegalArgumentException("订单超时阈值、轮询间隔和批量大小必须为正数");
        }
    }
}
```

`@PostConstruct` 在 Bean 初始化后执行。配置成 0 或负数时，启动阶段就会失败——这是"配置错误尽早暴露"的做法。

### 8.3 `@Value`：只有代码默认值、没有 yaml 条目的配置

```java
// src/main/java/com/fancy/rideagenthub/config/IdGeneratorConfig.java（节选）
@Bean(destroyMethod = "close")
public NodeLease idNodeLease(RedissonClient redis, @Value("${id.worker-id:1}") long workerId,
        @Value("${id.datacenter-id:1}") long datacenterId) {
    // ……
}
```

`${id.worker-id:1}` 的意思是"取 `id.worker-id`，取不到就用 `1`"。在 `application.yaml` 里搜索 `id:` 或 `worker-id`，**搜不到**——这两个键只存在于代码里。

全库 `@Value` 使用点共三处（`IdGeneratorConfig` 方法上带了两个键），如下：

| 位置 | 键 | 代码默认值 | yaml 中是否存在 |
|---|---|---|---|
| `config/IdGeneratorConfig` | `id.worker-id` / `id.datacenter-id` | `1` / `1` | 否 |
| `config/WebMvcConfig` | `web.cors.allowed-origins` | `http://localhost:5173,http://localhost:3000` | 否 |
| `agentbase/rag/RagService` | `rag.search.timeout-ms` | `8000` | 否 |

这意味着：**只看 `application.yaml` 会漏掉配置**。读配置时既要搜 yaml，也要搜 `@Value` 和 `@ConfigurationProperties`。

### 8.4 属性源与占位默认值

```yaml
spring:
  config:
    import: optional:file:./.env.properties
  datasource:
    url: ${MYSQL_URL:jdbc:mysql://127.0.0.1:3306/taxi_agent?...}   # 原值带一串 URL 参数，此处省略
    username: ${MYSQL_USERNAME}
    password: ${MYSQL_PASSWORD}
```

先区分两个概念：

- **Spring Environment（属性源）**：Spring 把多个来源合并成一个"按名字取值"的环境，包括命令行参数、系统环境变量（操作系统里的 `MYSQL_USERNAME`）、`application.yaml`、以及 `spring.config.import` 导入的文件等。
- **操作系统环境变量**：只是上述属性源中的一种。

所以：

- `${MYSQL_URL:jdbc:...}` 有默认值：所有属性源都没有 `MYSQL_URL` 时，用冒号后的本地 URL。
- `${MYSQL_USERNAME}` **没有默认值**：如果所有属性源都没有这个键，占位符无法解析（框架行为；本项目没有写兜底值）。但它**不是只能由操作系统环境变量提供**——项目明确导入了 `optional:file:./.env.properties`，如果该文件存在且写了这个键，也能解析；命令行参数 `--spring.datasource.username=...` 同样可以。
- `optional:file:./.env.properties` 的 `optional` 只表示**这个文件可以不存在**，不表示**所需属性可以缺失**。文件不存在且没有其他属性源提供时，`${MYSQL_USERNAME}` 仍然解析失败。
- **本章不读取、不展示 `.env` 内容**，也不假设它存在；能静态确认的只有"导入机制存在"，不能确认实际值。

### 8.5 四层值：读配置时必须分清

| 层 | 例子 | 怎么确认 |
|---|---|---|
| 源码默认值 | `AuthProperties.tokenTtlSeconds = 604800`；`@Value` 的 `:1` | 读字段初始值 / 注解默认值 |
| 配置文件 | `application.yaml` 的 `auth.token-ttl-seconds: 604800` | 读 yaml |
| 其它属性源 | 命令行参数、操作系统环境变量、导入的 `.env.properties`、配置中心 | 仓库只声明了导入机制；实际文件与启动参数**在仓库外** |
| 运行时未知 | 生产环境实际生效值 | 只能看运行环境（配置中心、启动参数、监控端点），静态阅读无法确定 |

教材能静态保证的是第一、二层，以及"项目声明了第三层的一种入口（optional 导入）"；第三层的实际内容与第四层必须标注"取决于部署环境"。

### 8.6 配置绑定与请求体反序列化是两套机制

同一个项目里，`application.yaml` 的绑定由 Spring Boot 的 Binder 完成，请求体 JSON 的绑定由 Jackson 完成。它们各有一套"未知字段"策略：`@ConfigurationProperties` 有 `ignoreUnknownFields` 属性，Jackson 有 `FAIL_ON_UNKNOWN_PROPERTIES` feature。两套互不相干，不要用一个的结论套另一个。

---

## 九、序列化：三种"字符串"要分清

### 9.1 `POST /order` 的字符串来自 Java 类型

```java
// service/impl/RideOrderServiceImpl.java#createOrder（结尾）
String createdOrderId = order.getOrderId().toString();
// ……
return createdOrderId;
```

方法签名是 `String createOrder(CreateOrderDTO)`（`service/RideOrderService` 里也是这样声明的），控制器 `Result.ok(orderId)` 拿到的就是 `String`。**这条接口没有经过 `Long` 序列化器**。

### 9.2 对象里的 `BigDecimal` 与 `Long`：全局规则

```java
// src/main/java/com/fancy/rideagenthub/config/Config.java（节选；为节省篇幅压缩了换行与原有注释）
@Bean
public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
    return builder -> {
        builder.serializerByType(BigDecimal.class, ToStringSerializer.instance);
        builder.serializerByType(Long.class, new JsonSerializer<Long>() {
            @Override
            public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                if (value == null) { gen.writeNull(); return; }
                if (value > 9007199254740991L || value < -9007199254740991L) {
                    gen.writeString(value.toString());
                } else {
                    gen.writeNumber(value);
                }
            }
        });
    };
}
```

两条规则要分开记：

- **`BigDecimal` 一律写成字符串**，与大小无关。所以 `RideOrderVO` 里声明为 `BigDecimal` 的 `estPrice` 会输出成 `"12.50"` 这种带引号的形式，具体小数位由该 `BigDecimal` 的 scale 决定（`RidePricing` 计算出的金额是两位小数）。
- **`Long` 按绝对值判断**：超过正负 `9007199254740991`（JavaScript 能精确表示的最大整数）才转字符串，否则保持数字。所以同一个响应里，雪花 ID 是字符串，小整数仍是数字。

`LocalDateTime` 没有自定义序列化器；Spring Boot 默认把日期写成 ISO 风格的文本（形如 `2026-11-14T08:35:00`），而不是时间戳数字（框架默认，参考 Spring Boot《JSON》文档）。

### 9.3 三种情况对照

| 场景 | 字段类型 | 为什么是字符串 | 证据 |
|---|---|---|---|
| `POST /order` 的 `data` | 方法返回 `String` | 代码显式 `toString()` | `RideOrderServiceImpl#createOrder` |
| `GET /order/{id}` 的 `orderId` | VO 字段已是 `String` | Builder 里显式 `toString()` | `toDetailVO` |
| `GET /order/{id}` 的 `estPrice` | `BigDecimal` | 全局 `ToStringSerializer` | `config/Config` |
| `GET /user/poi` 的 `userId` | `Long`（雪花） | 超安全整数范围 | `UserPOI` + `Config` |
| `GET /user/poi` 的 `id` | `Long`（自增） | 通常很小 → 仍是数字 | 同上 |
| `Result.total` | `Long`，值 `0` | 很小 → 数字 | `Result.ok` |

（以上 JSON 形态为**静态推导**，未实际发请求观察。）

---

## 十、失败与边界

### 10.1 三种"失败"不要混

| 失败 | 例子 | 方法体执行了吗 | 谁拒绝 | 本仓库结果 |
|---|---|---|---|---|
| 绑定/转换失败 | JSON 语法错、`/user/poi/abc`、缺少 `isFinished` | 否 | 框架 | 400（`GlobalExceptionHandler` 显式列出的类型） |
| 绑上了但不合格 | 缺 `startLat`/`startLng`、`vehicleType=4` | 是 | `validateAndSupplement` + `IllegalArgumentException` | 400（见 4.2） |
| 业务拒绝 | 越权、订单不存在、支付渠道未接入 | 是 | Service 抛 `BusinessException` | code 决定 HTTP 状态（403/404/503……） |

判断方法：先看异常是不是框架在方法体前抛的；如果是代码抛的，再看它是"字段不合格"还是"业务规则不允许"。

### 10.2 未知字段：结论有前提

见 4.3。当前版本默认忽略未知字段，但只在"默认自动配置 + 没有外部属性或其他定制覆盖"时成立。这是版本源码与项目配置的静态推导，**没有实际发请求**；部署环境若改了策略，结论会变。

### 10.3 配置缺失

- `${MYSQL_USERNAME}` 没有默认值：如果所有已加载的属性源都没有这个键，占位符无法解析（框架行为，静态推导）。它可以来自导入的 `.env.properties`、命令行参数、系统环境变量等，不限于操作系统环境变量。
- `optional:file:./.env.properties` 只表示"文件可以不存在"，不表示"属性可以缺失"。
- `@ConfigurationProperties` 字段类型不匹配（例如把 `accept-timeout-minutes` 写成 `abc`）会在绑定阶段失败；`RideOrderTimeoutProperties` 还会在 `@PostConstruct` 里校验正数。
- 这些失败发生在**启动阶段**，不是请求阶段——所以"服务起不来"和"接口报错"要分开排查。

### 10.4 本章不展开的内容

- 行锁、事务传播、条件更新的裁决：`00-foundations/03-data-and-transactions.md`（计划）。
- Lua、TTL、回填租约：`00-foundations/04-redis-flux-and-sse-basics.md`（计划）。
- 注解权限与资源归属的完整规则：`01-auth/02-authorization-and-defense.md`（计划）。
- 完整 Agent 工具循环：`04-chat-and-agent/02-routing-and-tool-loop.md`（计划）。

---

## 十一、设计取舍

1. **统一 `Result` 外壳**：好处是前端只解析一种结构；代价是 `data` 是 `Object`，编译期无法约束，且 HTTP 状态与 `code` 需要人工保持一致（异常处理器负责对齐）。
2. **DTO / Entity / VO 分离**：好处是请求字段、表结构、响应字段可以各自演进（例如 VO 把 Long 转 String、把 Mongo 折线拼进来）；代价是重复字段与手写转换（`toDetailVO` 几十行）。
3. **两种注入风格并存**：构造器注入利于测试，字段注入代码短；仓库没有统一，读代码时要能切换。
4. **配置分散在 yaml 与代码默认值**：只有**部分**参数有默认值（`auth.*`、`id.*`、`rag.search.timeout-ms` 等）；凭据（`MYSQL_USERNAME`/`MYSQL_PASSWORD`）没有默认值，Redis、MySQL 等依赖仍需满足。因此不能称为"零配置可运行"。代价是只看 yaml 会漏掉 `id.*`、`web.cors.*`、`rag.search.timeout-ms` 这类键。
5. **未显式覆盖未知字段策略**：仓库保留了序列化 customizer（`Long`/`BigDecimal`），但没有改 `FAIL_ON_UNKNOWN_PROPERTIES`，也没有自定义 `ObjectMapper`。当前版本默认忽略未知字段；代价是这个策略依赖框架默认，升级版本或外部属性覆盖都会改变行为。

---

## 十二、动手验证

### 练习 A：字段追踪（纯阅读，约 15 分钟）

目标：不看运行结果，判断一个字段最终落在哪、响应里有没有。

| 步骤 | 搜索/打开 | 预期发现 | 判断依据 |
|---|---|---|---|
| 1 | `RideOrderController` 里第一个**无路径**的 `@PostMapping` | `create(@RequestBody CreateOrderDTO)` | 方法 + 路径 = `POST /order` |
| 2 | `CreateOrderDTO` 字段列表 | 15 个字段，含 `userId`、`estPrice`、`mongoTraceId` | DTO 是输入形状 |
| 3 | `RideOrderServiceImpl#createOrder` 开头 | `dto.setUserId(...)`、默认值、`validateAndSupplement` | 输入被覆盖/补全 |
| 4 | 同方法中段 | `setMongoTraceId`、`setEstDistance`、`setEstPrice`、`setRadio` | 客户端值被服务端覆盖 |
| 5 | 同方法 `RideOrder.builder()` | `orderId`、`safetyCode`、`orderStatus`、`isDeleted` 等请求里没有的字段 | Entity 含服务端生成值 |
| 6 | 方法结尾 + 控制器 | `return createdOrderId` → `Result.ok(orderId)` | 响应是 `String`，没有 VO |

追问（写下来）：

- 如果客户端在 JSON 里写 `"mongoTraceId": "abc"`，最终实体里是什么？为什么？
- 如果把 `vehicleType` 从 JSON 里删掉，实体里是多少？依据在哪一行？

<details>
<summary>练习 A 参考答案</summary>

1. 实体里是服务端新生成的 UUID，不是 `"abc"`。依据：`createOrder` 里先 `String traceId = UUID.randomUUID().toString();`，再 `validatedDto.setMongoTraceId(traceId);`，之后才 `RideOrder.builder().mongoTraceId(...)`。客户端的值只留在 DTO 上，从未进入实体。
2. 实体里是 `1`。依据：`createOrder` 开头的 `if (dto.getVehicleType() == null) { dto.setVehicleType(1); }`；随后 `validateAndSupplement` 按 1 校验通过；实体的 `.vehicleType(...)` 还有一次 `== null ? 1 : ...` 的兜底。
</details>

### 练习 B：配置到消费者（纯阅读，约 15 分钟）

| 步骤 | 搜索/打开 | 预期发现 | 判断依据 |
|---|---|---|---|
| 1 | `src/main/resources/application.yaml` 搜 `auth:` | 3 个键 | 配置文件层 |
| 2 | 搜 `@ConfigurationProperties(prefix = "auth")` | `AuthProperties`，带 `@Component` | 绑定目标 |
| 3 | 搜 `getTokenTtlSeconds` | `AuthServiceImpl`（两处）、`TokenServiceImpl`（一处） | 消费者 |
| 4 | 把 yaml 里 `token-ttl-seconds` 那行"假设删掉" | 若没有其他属性源赋值，值仍是 `604800` | 字段初始值是源码默认；有覆盖时以覆盖为准 |
| 5 | 搜 `id.worker-id` | `IdGeneratorConfig` 的 `@Value`，yaml 无此键 | 默认值在注解里 |
| 6 | 搜 `web.cors.allowed-origins` | `WebMvcConfig` 的 `@Value`，yaml 无此键 | 同上 |

追问：如果部署时通过操作系统环境变量、导入的 `.env.properties` 或命令行覆盖了 `auth.token-ttl-seconds`，静态阅读能知道最终值吗？为什么？

<details>
<summary>练习 B 参考答案</summary>

不能。仓库里能看到的是源码默认值（604800）、`application.yaml`（604800）和"导入了 `optional:file:./.env.properties`"这条规则；但文件是否存在、里面写了什么、启动命令带了什么参数、机器上设了哪些环境变量，都在仓库之外。静态阅读只能确认"这个键可以被多种属性源覆盖"，不能确认"某台机器上实际覆盖成了多少"。要得到确定值，需要看启动命令、属性源文件、配置中心或运行时的配置端点——这些都不属于本章的源码阅读范围。

另外，`optional` 只说明文件可以不存在；如果文件不存在、也没有其他属性源提供这个键，字段就保留 604800。
</details>

### 运行检查点（可选，本章不要求）

本轮**没有执行**以下命令，也不假设你的环境已满足：

```powershell
java -version    # 需要 21（pom.xml 的 java.version）
mvn -v           # 确认 Maven 使用的是 JDK 21
```

按静态阅读，运行期有四类触发，不能只说"用户不用就不会访问依赖"：

| 触发 | 本项目实例 | 会访问什么 |
|---|---|---|
| Bean 创建 | `IdGeneratorConfig#idNodeLease` 创建 Bean 时向 Redis 拿节点租约锁并 `join()` | Redis |
| 应用就绪事件 | `HikariPoolLoader` 在 `ApplicationReadyEvent` 里查一次数据库；失败只记警告 | MySQL |
| 自动定时任务 | `@EnableScheduling` 开启；`QaIndexSynchronizer#synchronize` 按 `rag.sync.interval-ms`（代码默认 5 秒）执行，第一步 `jobs.claimBatch()`；队列中有任务时调用 Embedding 与 Elasticsearch。`OrderTimeoutJob` 还有两个 `@Scheduled`（轮询延迟队列、按库扫描超时） | MySQL（每次）、Embedding/ES（有任务时）、Redis |
| 用户请求 | 各接口 | 取决于接口 |

因此：

- **"没有用户请求"不等于"不访问数据库或外部服务"**：定时任务在没有请求时也会跑。`QaIndexSynchronizer#synchronize` 不依赖用户先搜索；只要队列里有同步任务，就会调用 Embedding 与 ES。
- 凭据与中间件仍需满足：`MYSQL_USERNAME` / `MYSQL_PASSWORD` 没有默认值；Redis 在启动阶段就会被用来拿节点租约。
- `HikariPoolLoader` 吞掉预热异常只说明**这个入口**的处理方式，不能证明所有数据库访问都延迟到用户操作，也不能保证其他启动依赖都被容错。
- MongoDB、高德、SMTP 目前只在对应业务/工具代码路径里被调用（静态检索没有发现定时入口），但这不等于"绝不会被后台访问"，也不改变启动与环境的不确定性。

以上均为**静态推导，未实测**；真实启动还受属性源、端口占用、中间件版本与自动配置影响。

---

## 十三、一页复习

**一条链**

`HTTP 请求 → DispatcherServlet → 按路径、方法及 params/headers/consumes/produces 等条件选方法 → 参数绑定（@RequestBody/@PathVariable/@RequestParam）→ Controller → Service → Mapper/客户端 → Result → Jackson 写 JSON`。异常走本仓库的 `GlobalExceptionHandler`。

**四个类型**

- DTO：输入形状（`CreateOrderDTO`），框架反序列化。
- Entity：表行（`RideOrder`），Service 构造。
- VO：输出形状（`RideOrderVO`），Service 手动转换。
- Result：统一外壳（`code/message/data/total`）。
- **不是流水线**：`POST /order` 只到 DTO + String；`GET /user/poi` 直接返回 Entity。

**三条容易混的规则**

1. 绑定/转换失败在方法体前（本仓库列出的类型映射 400）；"绑上了但不合格"在方法体里（`IllegalArgumentException` 也映射 400，但原因不同）；业务拒绝由 `BusinessException` 的 code 决定 HTTP 状态。
2. `Result.code` 是响应体字段，不等于 HTTP 状态，只是异常分支里被对齐。
3. 字符串有三种来源：Java `String` 返回、`BigDecimal` 全局规则、`Long` 超安全范围。

**配置四层**

源码默认值 → `application.yaml` → 其它属性源（命令行/系统环境变量/导入的 `.env.properties` 等）→ 运行时实际值。前两层可静态确认；第三层只能确认"项目声明了导入机制"，实际内容与第四层要部署信息。

**两个框架默认的边界**

- 重复映射按"路径+方法+params+headers+consumes+produces+自定义条件"整组判断，不是只看前两项。
- 未知 JSON 字段在当前版本（Spring Boot 3.5.9 / Framework 6.2.15）默认被忽略，前提是默认自动配置且没有外部覆盖；没有实际发请求验证。

---

## 十四、自测与答案

先自己写，再展开。

**1. 请求 `GET /order/driver/tasks?isFinished=false` 会进入哪个方法？参数通过什么方式绑定？如果把 `?isFinished=false` 去掉会怎样？**

<details>
<summary>参考答案</summary>

进入 `RideOrderController#driverTasks(@RequestParam Boolean isFinished)`。类上前缀 `/order`，方法上 `/driver/tasks`，HTTP 方法是 GET。`@RequestParam` 从查询参数取值并转换成 `Boolean`。

去掉参数后，因为注解没有 `required = false`，默认必填，框架抛出 `MissingServletRequestParameterException`，被 `GlobalExceptionHandler` 映射成 400（`message="请求参数格式不正确"`）。方法体不会执行。
</details>

**2. `GET /user/poi/abc` 会发生什么？错误发生在进入方法体之前还是之后？**

<details>
<summary>参考答案</summary>

`@PathVariable Long id` 需要把 `"abc"` 转成 `Long`，转换失败抛 `MethodArgumentTypeMismatchException`，进入 `GlobalExceptionHandler` 的 400 分支。**发生在方法体之前**，所以 `userPOIService.findById` 不会执行，控制器里的 404 判断也不会走到。

对比：`GET /user/poi/999`（数字但不存在）会进入方法体，查到 `null` 后主动抛 `BusinessException(404, "兴趣点不存在")`。两者都是 4xx 客户端错误，但一个来自框架绑定（400），一个来自业务代码（404）。

还有一种容易混的 400：请求体合法、DTO 也能绑定，但缺少业务必填字段（如 `startLat`），进入方法体后由 `validateAndSupplement` + `IllegalArgumentException` 拒绝（见 4.2）。它也是 400，但不是绑定失败。
</details>

**3. `RideOrderController` 的字段声明是接口 `RideOrderService`，为什么能调用到 `RideOrderServiceImpl`？如果把 `RideOrderServiceImpl` 上的 `@Service` 去掉会怎样？**

<details>
<summary>参考答案</summary>

因为 `RideOrderServiceImpl` 带 `@Service`，被组件扫描注册成 Bean，并且实现了 `RideOrderService`；Spring 按类型注入时找到它（框架行为）。仓库里该接口只有一个实现，所以不需要 `@Qualifier`。

去掉 `@Service` 后，容器里没有 `RideOrderService` 类型的 Bean，`RideOrderController` 的构造器注入失败，应用启动阶段就会报"找不到 Bean"。这是**启动失败**，不是请求时 500。
</details>

**4. `auth.token-ttl-seconds` 在 yaml 里是 604800，`AuthProperties` 字段初始值也是 604800。如果把 yaml 里这一行删掉，运行时的值是多少？如果部署时通过某个属性源（例如系统环境变量）覆盖成 3600 呢？**

<details>
<summary>参考答案</summary>

删掉 yaml 行后，如果没有**任何其他属性源**给 `auth.token-ttl-seconds` 赋值，`@ConfigurationProperties` 不会给这个字段赋新值，字段保留初始值 **604800**。注意这个前提：项目导入了 `optional:file:./.env.properties`，如果文件存在且写了这个键，或者命令行/系统环境变量提供了它，值就不是默认值。

用环境变量覆盖成 3600 时，最终值是 3600（其它属性源层）。但**静态阅读无法确认**部署环境到底有没有设置：仓库里看不到实际环境变量，也看不到 `.env.properties` 的内容。教材只能说到"可以被多种属性源覆盖"，不能断言生产值。

补充：`@ConfigurationProperties` 的宽松绑定让 `AUTH_TOKEN_TTL_SECONDS` 这类系统环境变量名能映射到 `auth.token-ttl-seconds`（框架行为）。
</details>

**5. `POST /order` 成功时，HTTP 状态码和响应体里的 `code` 分别是多少？如果 `GET /order/{id}` 查不到订单呢？**

<details>
<summary>参考答案</summary>

成功：HTTP 200，`Result.code = 200`（`Result.ok` 写死 200；HTTP 状态由框架默认 200）。

查不到：Service 抛 `BusinessException(404)`，`GlobalExceptionHandler#response` 把 404 当 HTTP 状态，响应体 `code` 也是 404。因为 `response()` 只在 code 位于 400～599 时采用它，否则退化为 400。

注意：`Result.code` 本身只是响应体字段；它能和 HTTP 状态一致，是异常处理器主动对齐的结果，不是框架自动同步。
</details>

**6. 为什么 `POST /order` 的 `data` 是字符串，而 `GET /user/poi` 里的 `id` 通常是数字？**

<details>
<summary>参考答案</summary>

`POST /order`：`createOrder` 内部 `order.getOrderId().toString()`，返回类型就是 `String`，与序列化器无关。

`GET /user/poi`：返回的是 `UserPOI` 实体，`id` 是 `Long` 自增主键，通常很小，`Config#jacksonCustomizer` 只把绝对值超过 `9007199254740991` 的 `Long` 转字符串，所以它保持数字；同一个实体里的 `userId` 是雪花 ID，通常超范围，会变成字符串。

也就是说：**同一个响应里可以既有数字 ID 又有字符串 ID**，判断依据是字段类型 + 值的大小 + 有没有显式转换。
</details>

**7. 有人推断："既然项目分了 DTO、Entity、VO，那每个接口一定是 DTO → Entity → VO 一条流水线。" 这个推断为什么不成立？**

<details>
<summary>参考答案</summary>

三点反例：

1. `POST /order` 返回 `String`，全程没有 VO；请求体是 DTO，落库是 Entity，响应是订单号。
2. `GET /user/poi` 直接把 `List<UserPOI>`（Entity）作为 `data` 返回，没有 VO。
3. `POST /user/poi` 的请求体类型叫 `POIInfoVO`——名字是 VO，却当输入用。

所以 DTO/Entity/VO 是**命名约定**，帮助读者判断"这个类大概用在哪"，不是框架强制的转换链。判断一个接口实际经过哪些类型，要看方法签名和 `Result.ok(...)` 的参数，不能看包名猜。
</details>

**8. `CreateOrderDTO` 的源码里没有 `getStartLat()` 这样的方法，为什么 `RideOrderServiceImpl` 能调用？**

<details>
<summary>参考答案</summary>

Lombok 的 `@Data` 在编译期生成 getter/setter/`toString`/`equals`/`hashCode`，`@Builder` 生成 `builder()` 与链式 setter，`@RequiredArgsConstructor` 为**未初始化的 final 字段**（以及 `@NonNull` 字段）生成构造器参数。源码里看不到，但编译后的 class 里有。

阅读方法：看类上的 Lombok 注解判断有哪些生成成员；IDE 的 Structure 视图能看到；`javap` 可看字节码。不要把"源码里没写"误判成"没有这个方法"。
</details>

---

## 十五、导航

- **上一章**：[第 1 章 业务地图](01-project-map.md)。
- **下一章**：[第 3 章 数据存在哪，事务管到哪](03-data-and-transactions.md)——本章留下的"条件更新、事务边界、提交后回调"在那里展开。
- **相关专题**：
  - 登录与令牌 DTO、配置 → `01-auth/01-login-and-session.md`（计划）
  - 角色注解与资源归属 → `01-auth/02-authorization-and-defense.md`（计划）
  - 订单状态机与字段语义 → `02-orders/01-order-lifecycle.md`（计划）
  - 计价与 BigDecimal 的业务含义 → `02-orders/05-pricing-and-payment.md`（计划）

### 本章源码核验入口

路径基准：Java 类相对 `src/main/java/com/fancy/rideagenthub/`，资源文件写完整仓库相对路径。

| 结论 | 源码位置 |
|---|---|
| 类前缀 + 方法路径 | `controller/RideOrderController.java`、`controller/UserPOIController.java` |
| 同路径不同方法 | `RideOrderController#reportDriverLocation`、`#driverLocation` |
| 三种参数绑定 | `RideOrderController#create`、`#detail`、`#driverTasks`；`UserPOIController#get` |
| DTO 字段 | `domain/dto/CreateOrderDTO.java` |
| Entity 字段与表映射 | `domain/entity/RideOrder.java`、`domain/entity/UserPOI.java` |
| VO 构造 | `service/impl/RideOrderServiceImpl#toDetailVO`、`#toVO` |
| 统一响应 | `domain/response/Result.java`、`domain/response/PageResult.java` |
| 字段覆盖与补全 | `RideOrderServiceImpl#createOrder`、`CreateOrderDTO#validateAndSupplement` |
| 注入方式 | `controller/RideOrderController`（构造器）、`RideOrderServiceImpl`（`@Resource` 字段）、`config/PaymentConfig`（`@Bean`） |
| Mapper 代理 | `mapper/RideOrderMapper.java` |
| 异常映射 | `exception/GlobalExceptionHandler.java`（`handleInvalidRequest` 与 `handleIllegalArgument`） |
| 业务校验失败（与绑定失败的区别） | `CreateOrderDTO#validateAndSupplement`、`RideOrderServiceImpl#createOrder`、`GlobalExceptionHandler#handleIllegalArgument` |
| 序列化规则 | `config/Config.java#jacksonCustomizer` |
| 未知字段默认（框架） | 正文 4.3 的两条版本源码链接；仓库侧 `config/Config.java`、`src/main/resources/application.yaml` |
| 配置绑定 | `config/AuthProperties.java`、`config/RideOrderTimeoutProperties.java`、`config/ChatGuardProperties.java` |
| 配置到消费者的追踪 | `AuthServiceImpl#issueLoginResultVO`、`TokenServiceImpl#saveToken` |
| `@Value` 使用点 | `config/IdGeneratorConfig.java`、`config/WebMvcConfig.java`、`agentbase/rag/RagService.java` |
| 自动定时入口 | `agentbase/rag/QaIndexSynchronizer#synchronize`、`service/base/OrderTimeoutJob` |
| 配置文件 | `src/main/resources/application.yaml` |
| 启动类与扫描范围 | `RideAgentHubApplication.java` |
