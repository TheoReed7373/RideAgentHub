CREATE TABLE `sys_user` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `user_id` BIGINT NOT NULL COMMENT '用户唯一标识(业务 雪花ID)',
  `username` VARCHAR(50) NOT NULL COMMENT '用户名',
  `email` VARCHAR(100) DEFAULT NULL COMMENT '用户邮箱',
  `password` VARCHAR(255) NOT NULL COMMENT '加密后的用户密码',
  `role` VARCHAR(20) COMMENT '用户权限/角色: ADMIN, USER, GUEST',
  `status` TINYINT(1) DEFAULT '1' COMMENT '账号状态: 0-禁用, 1-启用',
  `is_deleted` TINYINT(1) DEFAULT '0' COMMENT '逻辑删除: 0-未删除, 1-已删除',
  `last_login_time` DATETIME DEFAULT NULL COMMENT '最后登录时间',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户登录表';

-- 兴趣点表
CREATE TABLE `sys_user_poi` (
                                `id` BIGINT AUTO_INCREMENT COMMENT '主键ID',
                                `user_id` BIGINT NOT NULL COMMENT '用户ID',
                                `poi_tag` VARCHAR(64) NOT NULL COMMENT '兴趣点标签',
                                `poi_name` VARCHAR(255) NOT NULL COMMENT '兴趣点名称',
                                `poi_address` VARCHAR(500) DEFAULT NULL COMMENT '兴趣点详细地址',
                                `longitude` DECIMAL(10, 7) NOT NULL COMMENT '经度',
                                `latitude` DECIMAL(10, 7) NOT NULL COMMENT '纬度',
                                PRIMARY KEY (`id`),
                                INDEX `idx_user_id` (`user_id`) -- 为用户ID建立索引，方便查询个人收藏
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户兴趣点表';

CREATE TABLE `sys_ride_order` (
    -- ================= 基础信息 =================
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `order_id` BIGINT NOT NULL COMMENT '订单ID',
    `user_id` BIGINT NOT NULL COMMENT '乘客用户ID',
    `driver_id` BIGINT DEFAULT NULL COMMENT '司机ID (接单后回填)',
    `mongo_trace_id` VARCHAR(64) DEFAULT NULL COMMENT '关联MongoDB的轨迹ID (路径规划结果)',

    -- ================= 订单核心参数 =================
    `vehicle_type` INT DEFAULT 1 COMMENT '车辆类型 (1:快车, 2:优享, 3:专车)',
    `is_reservation` TINYINT(1) DEFAULT 0 COMMENT '是否为预约单 (0:否, 1:是)',
    `is_expedited` TINYINT(1) DEFAULT 0 COMMENT '是否加急 (0:否, 1:是)',
    `safety_code` CHAR(4) DEFAULT NULL COMMENT '安全码 (乘客上车核销用)',

    -- ================= 状态流转 =================
    `order_status` TINYINT NOT NULL DEFAULT 10 COMMENT '状态: 10-创建, 20-司机接单, 30-司机到达, 40-行程中, 50-完成待支付, 60-已支付, 90-已取消',
    `cancel_role` TINYINT DEFAULT NULL COMMENT '取消方: 1-用户, 2-司机, 3-系统',
    `cancel_reason` VARCHAR(255) DEFAULT NULL COMMENT '取消原因',

    -- ================= 时间轴 =================
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
    `scheduled_time` DATETIME DEFAULT NULL COMMENT '预约上车时间 (仅预约单)',
    `driver_accept_time` DATETIME DEFAULT NULL COMMENT '司机接单时间',
    `driver_arrive_time` DATETIME DEFAULT NULL COMMENT '司机到达起点时间',
    `pickup_time` DATETIME DEFAULT NULL COMMENT '开始行程/上车时间 (计费开始)',
    `finish_time` DATETIME DEFAULT NULL COMMENT '结束行程/下车时间 (计费结束)',
    `pay_time` DATETIME DEFAULT NULL COMMENT '支付时间',

    -- ================= 位置信息 =================
    `start_address` VARCHAR(255) NOT NULL COMMENT '起点结构化地址文本',
    `start_lat` DECIMAL(10, 6) NOT NULL COMMENT '起点纬度',
    `start_lng` DECIMAL(10, 6) NOT NULL COMMENT '起点经度',
    `end_address` VARCHAR(255) NOT NULL COMMENT '终点结构化地址文本',
    `end_lat` DECIMAL(10, 6) NOT NULL COMMENT '终点纬度',
    `end_lng` DECIMAL(10, 6) NOT NULL COMMENT '终点经度',
    `est_distance` DECIMAL(10, 2) DEFAULT NULL COMMENT '预估距离(Km)',
    `real_distance` DECIMAL(10, 3) DEFAULT NULL COMMENT '实际距离(Km)',

    -- ================= 费用精细化 =================
    `est_price` DECIMAL(10, 2) DEFAULT NULL COMMENT '预估一口价',
    `real_price` DECIMAL(10, 2) DEFAULT NULL COMMENT '实际最终车费',
    `price_base` DECIMAL(10, 2) DEFAULT 0.00 COMMENT '基础里程费',
    `price_time` DECIMAL(10, 2) DEFAULT 0.00 COMMENT '时长费',
    `price_distance` DECIMAL(10, 2) DEFAULT 0.00 COMMENT '远途/空驶费',
    `price_expedited` DECIMAL(10, 2) DEFAULT 0.00 COMMENT '加急费',
    `price_radio` DECIMAL(10, 2) DEFAULT 0.00 COMMENT '乘算比例',

    -- ================= 系统字段 =================
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT '逻辑删除',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_id` (`order_id`),
    KEY `idx_user_create_time` (`user_id`, `create_time`),
    KEY `idx_driver_create_time` (`driver_id`, `create_time`),
    KEY `idx_status_create_time` (`order_status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='网约车订单表';

create table sys_messages
(
    `id`              bigint auto_increment not null primary key,
    `chat_id` char(36) not null,
    `message_text`    text     not null,
    `created_at`      DATETIME DEFAULT current_timestamp not null
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话历史表';
CREATE INDEX idx_chat_id_id_desc ON sys_messages (chat_id, id DESC);

CREATE TABLE `sys_chat_tool_repsonse`
(
    `id`              bigint auto_increment not null primary key,
    `call_id`         varchar(128) not null,
    `chat_id`         char(36) not null,
    `response`    text     not null
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话工具调用结果表';

create table `sys_chat`
(
    `id`              bigint auto_increment not null primary key,
    `chat_id`         char(36) not null,
    `user_id`         bigint not null,
    `title`           varchar(255) not null,
    `locked`          tinyint(1) DEFAULT 0 not null,
    `created_at`      DATETIME DEFAULT current_timestamp not null,
    `updated_at`      DATETIME DEFAULT current_timestamp not null
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ;

-- 工单主表
CREATE TABLE `sys_ticket` (
                              `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                              `ticket_id` varchar(16) NOT NULL COMMENT '工单对外编号(业务唯一标识)',
                              `user_id` bigint NOT NULL COMMENT '发起人ID',
                              `user_type` tinyint(4) NOT NULL DEFAULT 1 COMMENT '发起人类型: 1-乘客, 2-司机',
                              `order_id` bigint DEFAULT NULL COMMENT '关联的网约车订单ID',
    -- 核心状态与分类
                              `ticket_type` tinyint(4) NOT NULL COMMENT '工单类型: 1-物品遗失, 2-费用争议, 3-服务投诉, 4-安全问题, 5-其他',
                              `priority` tinyint(4) NOT NULL DEFAULT 1 COMMENT '优先级: 1-普通, 2-紧急, 3-特急',
                              `ticket_status` tinyint(4) NOT NULL DEFAULT 0 COMMENT '状态: 0-待分配, 1-处理中, 2-待用户确认, 3-已完成, 4-已关闭',
    -- 处理流程
                              `handler_id` bigint DEFAULT NULL COMMENT '当前处理客服ID',
    -- 内容信息
                              `title` varchar(128) NOT NULL COMMENT '工单标题',
                              `content` varchar(1536) NOT NULL COMMENT '工单详情描述',
    -- 结果反馈
                              `process_result` varchar(512) DEFAULT NULL COMMENT '处理结果摘要',
                              `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                              `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                              PRIMARY KEY (`id`),
                              UNIQUE KEY `uk_ticket_id` (`ticket_id`),
                              KEY `idx_user` (`user_id`, `user_type`),
                              KEY `idx_order_id` (`order_id`),
                              KEY `idx_handler_status` (`handler_id`, `ticket_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单记录表';

-- 工单沟通记录表
CREATE TABLE `sys_ticket_chat` (
                                   `id` bigint NOT NULL AUTO_INCREMENT,
                                   `ticket_id` varchar(16) NOT NULL COMMENT '关联sys_ticket的ticket ID',
                                   `sender_id` bigint NOT NULL COMMENT '发送者ID',
                                   `sender_role` tinyint(4) NOT NULL COMMENT '发送者角色: 1-乘客, 2-司机, 3-客服, 0-系统自动',
                                   `content` varchar(1536) NOT NULL COMMENT '消息内容',
                                   `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                   PRIMARY KEY (`id`),
                                   KEY `idx_ticket_id` (`ticket_id`),
                                   KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单沟通记录表';

CREATE TABLE `sys_qa_info`
(
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY ,
    `group_id` BIGINT NOT NULL ,--雪花id
    `answer` TEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='rag问答对信息表';

CREATE TABLE `sys_qa_es`
(
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY ,
    `elastic_id` BIGINT NOT NULL , --雪花id
    `group_id` BIGINT NOT NULL , --雪花id
    `question` VARCHAR(255) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='rag问答对ES映射表';