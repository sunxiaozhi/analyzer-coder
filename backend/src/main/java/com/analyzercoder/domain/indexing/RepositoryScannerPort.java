package com.analyzercoder.domain.indexing;

import com.analyzercoder.domain.repository.CodeRepository;
import java.util.List;

/** 定义仓库扫描的领域端口，由基础设施层提供具体适配实现。 */
public interface RepositoryScannerPort {

    /**
     * 扫描指定仓库快照并返回可处理文件。
     *
     * @param repository 待处理的代码仓库领域对象
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<ScannedRepositoryFile> scan(CodeRepository repository);
}
