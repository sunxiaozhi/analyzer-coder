package com.analyzercoder.application.repository;

import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.util.List;

/** 定义代码仓库用例的应用层入口，隔离接口适配层与具体实现。 */
public interface RegisterRepositoryUseCase {

    /**
     * 登记本地 Git 仓库并返回新建的仓库聚合。
     *
     * @param command 经过接口层校验的用例输入命令
     * @return 接口约定的操作结果
     */
    CodeRepository register(RegisterRepositoryCommand command);

    /**
     * 登记由系统托管的仓库快照并返回仓库聚合。
     *
     * @param command 经过接口层校验的用例输入命令
     * @return 接口约定的操作结果
     */
    CodeRepository registerManaged(RegisterRepositoryCommand command);

    /**
     * 按标识读取目标领域对象，不存在时由实现报告业务错误。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    CodeRepository get(CodeRepositoryId repositoryId);

    /**
     * 按当前访问范围和筛选条件查询记录列表。
     *
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<CodeRepository> list();

    /**
     * 重新扫描仓库元数据并更新当前版本信息。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    RepositoryScanResult rescan(CodeRepositoryId repositoryId);

    /**
     * 删除符合给定条件的数据。
     *
     * @param repositoryId 目标对象的唯一标识
     */
    void delete(CodeRepositoryId repositoryId);
}
