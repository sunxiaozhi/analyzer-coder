/**
 * 基础设施层实现领域端口，并封装数据库、文件系统、Git、SQLite 和向量检索等技术细节。
 *
 * <p>适配器负责技术模型与领域模型之间的转换，禁止将驱动类型向上泄漏到应用层。
 */
package com.analyzercoder.infrastructure;
