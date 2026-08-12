package com.analyzercoder.application.indexing;

import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.domain.indexing.IndexJobId;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.util.List;

/** 定义索引任务用例的应用层入口，隔离接口适配层与具体实现。 */
public interface IndexJobUseCase {

    /**
     * 校验输入并启动对应应用用例。
     *
     * @param command 经过接口层校验的用例输入命令
     * @return 接口约定的操作结果
     */
    IndexJob start(StartIndexCommand command);

    /**
     * 按标识读取目标领域对象，不存在时由实现报告业务错误。
     *
     * @param indexJobId 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    IndexJob get(IndexJobId indexJobId);

    /**
     * 查询指定仓库最近一次索引任务状态。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    IndexJob getLatestStatus(CodeRepositoryId repositoryId);

    /**
     * 按当前访问范围和筛选条件查询记录列表。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<IndexJob> list(CodeRepositoryId repositoryId);

    /**
     * 取消指定索引任务并返回最新状态。
     *
     * @param indexJobId 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    IndexJob cancel(IndexJobId indexJobId);

    /**
     * 基于原任务参数创建可执行的重试任务。
     *
     * @param indexJobId 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    IndexJob retry(IndexJobId indexJobId);
}
