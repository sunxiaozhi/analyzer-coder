package com.analyzercoder.domain.rag;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.util.List;

/** 定义向量检索的领域端口，由基础设施层提供具体适配实现。 */
public interface VectorSearchPort {

    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param query 经过规范化的查询条件
     * @param limit 允许返回的最大记录数
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<VectorSearchHit> search(CodeRepositoryId repositoryId, String query, int limit);
}
