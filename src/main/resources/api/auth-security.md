# 认证接口变更

接口路径保持 /auth/email-code、/auth/register、/auth/login/password、/auth/login/email-code、/auth/password/reset、/auth/logout、/auth/account（`@DeleteMapping`，**DELETE** 方法）。此外还有 `GET /auth/username/available`（用户名可用性检查，无需登录，仅受来源地址限流）、`GET /user/current`（读取当前用户）与 `POST /user/current/update`（资料修改，含改绑邮箱；**不存在 `POST /user/current`**）、`POST /user/current/password/reset`、`POST /user/admin/{support/page,create,delete,disable,activate,page,update}`（7 个管理员账户管理端点）、`POST /user/rebuild`（布隆过滤器重建）。`/user/admin/**` 与 `/user/rebuild` 都要求 `ADMIN` 权限，经 `AuthTokenInterceptor → UserTokenContext → PermissionAspect → @RequirePermission` 鉴权链；上述 `/auth/**` 与 `/order/estimate` 是入口层来源地址限流的作用路径，`OPTIONS` 预检请求放行（不消耗配额）。

邮箱验证码使用安全随机数。发送冷却、失败次数和消费均由 Redis 原子操作处理：有效码按消费/预留状态防并发复用；格式正确但内容错误的 6 位数字码累计 5 次后，验证码被删除。null 或非 6 位数字在 Java 层直接返回 false，不进入 Redis 错误计数。邮箱大小写与首尾空白统一处理。注册和重置密码在数据库事务中预留验证码，提交后消费，回滚后释放预留；邮件发送失败仅清理本次发送所属状态。

认证及估价入口按直接连接来源地址限制为每分钟 120 次，超过返回 HTTP 429。入口限流未捕获 Redis 执行异常，故障时通常由全局处理器返回 HTTP 500；这不同于对话限流的异常放行。未信任客户端伪造的转发地址头；反向代理部署需确保来源地址策略符合实际网络。

令牌与用户索引原子发布，注销全部会话会推进会话版本。验证令牌还核对数据库当前凭据摘要、角色及账号状态，防止密码重置期间旧验证请求产生有效会话。升级前的旧令牌没有这些信息，升级后用户需要重新登录。

用户名、邮箱在相同角色下对未删除账号保持唯一，数据库索引裁决并发注册。逻辑删除后的名称可复用。历史重复数据须在执行迁移前人工确认处理，不自动删除账号。

原用户定位或兴趣点查询中的“数据不存在”也统一返回 HTTP 404，响应体仍保留原 Result 错误结构。
