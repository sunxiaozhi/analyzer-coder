package com.analyzercoder.application.repository;

import com.analyzercoder.domain.repository.CodeRepository;

/** 封装代码仓库用例的返回数据，避免接口层依赖内部领域对象。 */
public record RepositoryScanResult(boolean changed, CodeRepository repository) {}
