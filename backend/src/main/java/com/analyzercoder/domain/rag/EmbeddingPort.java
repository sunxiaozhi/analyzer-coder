package com.analyzercoder.domain.rag;

import java.util.List;

/** 定义向量嵌入的领域端口，由基础设施层提供具体适配实现。 */
public interface EmbeddingPort {

    /**
     * 为输入文本生成向量表示。
     *
     * @param text 待生成向量的单条文本
     * @return 接口约定的操作结果
     */
    EmbeddingVector embed(String text);

    /**
     * 为输入文本生成向量表示。
     *
     * @param texts 待批量生成向量的文本列表
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<EmbeddingVector> embedAll(List<String> texts);
}
