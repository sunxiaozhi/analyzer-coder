package com.analyzercoder.domain.codegraph;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.util.List;
import java.util.Optional;

/** 定义代码图谱的领域端口，由基础设施层提供具体适配实现。 */
public interface CodeGraphPort {

    /**
     * 按稳定符号标识查询代码图谱节点。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param symbolId 目标对象的唯一标识
     * @return 可能为空的匹配结果
     */
    Optional<CodeSymbol> getSymbol(CodeRepositoryId repositoryId, String symbolId);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param query 经过规范化的查询条件
     * @param limit 允许返回的最大记录数
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<CodeSymbol> searchSymbols(CodeRepositoryId repositoryId, String query, int limit);

    /**
     * 查询指定源码文件中定义的代码符号。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param filePath 相对于仓库根目录的规范化文件路径
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<CodeSymbol> getFileSymbols(CodeRepositoryId repositoryId, String filePath);

    /**
     * 读取指定代码符号对应的源码片段。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param symbolId 目标对象的唯一标识
     * @return 可能为空的匹配结果
     */
    Optional<CodeSource> getSymbolSource(CodeRepositoryId repositoryId, String symbolId);

    /**
     * 查询直接或间接调用指定符号的上游符号。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param symbolId 目标对象的唯一标识
     * @param depth 代码图谱遍历允许的最大关系深度
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<CodeGraphEdge> getCallers(CodeRepositoryId repositoryId, String symbolId, int depth);

    /**
     * 查询指定符号直接或间接调用的下游符号。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param symbolId 目标对象的唯一标识
     * @param depth 代码图谱遍历允许的最大关系深度
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<CodeGraphEdge> getCallees(CodeRepositoryId repositoryId, String symbolId, int depth);

    /**
     * 按图关系查询与指定符号相关的代码符号。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param symbolId 目标对象的唯一标识
     * @param depth 代码图谱遍历允许的最大关系深度
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<CodeSymbol> getRelatedSymbols(CodeRepositoryId repositoryId, String symbolId, int depth);
}
