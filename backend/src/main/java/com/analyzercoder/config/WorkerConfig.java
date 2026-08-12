package com.analyzercoder.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 集中声明后台任务相关组件及运行参数，保持装配逻辑与业务代码分离。 */
@Configuration
@EnableScheduling
public class WorkerConfig {}
