/**
 * 接口层负责 HTTP 协议适配、参数校验和响应转换，并将请求委派给应用层用例。
 *
 * <p>控制器不直接访问 Mapper 或文件系统，认证与授权由统一安全组件执行。
 */
package com.analyzercoder.interfaces;
