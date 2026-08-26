/**
 * 基础设施层实现领域端口，并封装 PostgreSQL、文件系统、Git、CodeGraph CLI 和模型调用等技术细节。
 *
 * <p>适配器负责技术模型与领域模型之间的转换，禁止将驱动类型向上泄漏到应用层。
 */
package com.analyzercoder.infrastructure;
