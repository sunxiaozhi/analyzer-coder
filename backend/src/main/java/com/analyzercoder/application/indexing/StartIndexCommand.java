package com.analyzercoder.application.indexing;

import com.analyzercoder.domain.indexing.IndexJobType;
import com.analyzercoder.domain.repository.CodeRepositoryId;

/** 封装发起当前模块用例所需的输入参数，作为稳定的应用层调用边界。 */
public record StartIndexCommand(CodeRepositoryId repositoryId, IndexJobType type) {}
