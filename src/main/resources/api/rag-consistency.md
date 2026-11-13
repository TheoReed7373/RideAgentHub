# 知识库与工单变更

知识库管理接口仍由管理员访问，`RagController` 共 7 个端点，全部标注 `@RequirePermission({"ADMIN"})`：

| 端点 | 作用 |
| --- | --- |
| `POST /rag/qa/page` | 分页查询问答对 |
| `POST /rag/qa/add` | 新增单组问答（questions + answer） |
| `POST /rag/qa/add-batch` | 批量新增问答 |
| `POST /rag/admin/qa/delete` | 按 groupIds / questionIds 删除 |
| `POST /rag/admin/qa/update-answer` | 更新某 groupId 下所有问题的答案 |
| `POST /rag/admin/qa/update-question` | 更新某个 questionId 的问题，并入队等待后台重新生成向量 |
| `GET /rag/search?question=...` | 知识库混合检索（同样仅 ADMIN） |

新增、修改和删除先在同一 MySQL 事务中保存有效记录及同步任务（`syncJobs.enqueue(groupId)`），接口成功表示数据已保存，检索索引随后更新，默认每 5 秒处理任务。单组同步失败后将任务下次可领取时间延后 30 秒，随后继续处理本批任务；实际重试还需等待轮询、锁与处理资源。该隔离不覆盖 claimBatch 或 defer 自身抛异常的情况。`rag.sync.interval-ms`（默认 5000）、`rag.search.timeout-ms`（默认 8000）与 `AsyncWorkConfig` 的线程池参数（4/8/32、8/32/64）当前只以 `@Value` / `@Scheduled` 默认值和 Java 配置形式存在，**未在 `application.yaml` 中显式外置**；rag.sync.interval-ms 与 rag.search.timeout-ms 可以直接加配置覆盖；线程池容量是硬编码，调整需要改 AsyncWorkConfig。

检索结果会按数据库核对有效问题和最新答案，已删除记录或失败新增留下的 ES 文档不会独立作为有效答案返回。修改问题至索引同步完成之间可能暂时检索不到该问题。向量生成和双路检索的 Future 等待共享默认 8 秒预算；后续 RRF 融合和 authoritative 同步数据库核对不受该 deadline 约束，因此不是整个请求的硬超时。最终最多返回 5 组：先按 RRF 排序并按 groupId 去重截取 Top5，再核对数据库；剔除无效候选后不会从后续排名补足。执行线程及队列有上限。网络调用的物理取消能力仍取决于底层客户端。

工单提交的 userId 由登录态注入，userType 按司机为 2、其他登录角色为 1 设置；可选的 orderId 必须关联该身份本人订单。ticketType 是请求中的工单业务类型，不由登录身份决定。状态流转在事务中锁定工单行并比较旧状态；关闭/完成后不允许普通处理动作重新开启。待分配工单可以正常强制分配。客服操作仍需原有角色与处理人权限。
