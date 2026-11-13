# 知识库与工单变更

知识库管理接口仍由管理员访问，`RagController` 共 7 个端点，全部标注 `@RequirePermission({"ADMIN"})`：

| 端点 | 作用 |
| --- | --- |
| `POST /rag/qa/page` | 分页查询问答对 |
| `POST /rag/qa/add` | 新增单组问答（questions + answer） |
| `POST /rag/qa/add-batch` | 批量新增问答 |
| `POST /rag/admin/qa/delete` | 按 groupIds / questionIds 删除 |
| `POST /rag/admin/qa/update-answer` | 更新某 groupId 下所有问题的答案 |
| `POST /rag/admin/qa/update-question` | 更新某个 questionId 的问题（必须同步重嵌向量） |
| `GET /rag/search?question=...` | 知识库混合检索（同样仅 ADMIN） |

新增、修改和删除先在同一 MySQL 事务中保存有效记录及同步任务（`syncJobs.enqueue(groupId)`），接口成功表示数据已保存，检索索引随后更新，默认每 5 秒处理任务。失败的任务保留并在 30 秒后重试，不阻止后续正常任务处理。`rag.sync.interval-ms`（默认 5000）、`rag.search.timeout-ms`（默认 8000）与 `AsyncWorkConfig` 的线程池参数（4/8/32、8/32/64）当前只以 `@Value` / `@Scheduled` 默认值和 Java 配置形式存在，**未在 `application.yaml` 中显式外置**；调整它们需要改代码或加配置覆盖。

检索结果会按数据库核对有效问题和最新答案，已删除记录或失败新增留下的 ES 文档不会独立作为有效答案返回。修改问题至索引同步完成之间可能暂时检索不到该问题。向量生成和双路检索共享默认 8 秒预算；执行线程及队列有上限。网络调用的物理取消能力仍取决于底层客户端。

工单提交仅可关联发起人的乘客/司机订单，类型由登录身份决定。状态流转在事务中锁定工单行并比较旧状态；关闭/完成后不允许普通处理动作重新开启。待分配工单可以正常强制分配。客服操作仍需原有角色与处理人权限。
