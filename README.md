# Analyzer Coder · 代码与知识联合检索

这是一个面向开发者的最小检索工具：导入代码仓库，建立版本化索引，用一次查询同时找到相关代码与项目知识，并能回到文件、行号和知识卡来源。

## 产品边界

核心路径只有四步：

1. 导入 Git 或 ZIP 仓库；
2. 为当前快照建立代码索引；
3. 维护与代码范围关联的知识卡；
4. 在“代码与知识”页面统一检索两类结果。

系统保留基于检索证据的问答、代码关系和知识失效提示作为辅助能力。它不再提供变更审查、PR/MR Webhook、审查 CI、任务结果回报或审查型 MCP 工具。

## 第一次使用

1. 登录后在“项目管理”导入一个小仓库。
2. 在“项目总览”完成仓库准备，确认当前快照已有内容索引。
3. 在“知识库”录入一条业务约束，并绑定相关路径或符号。
4. 打开“代码与知识”，搜索类名、函数名、错误信息或业务术语。
5. 检查混排结果是否同时包含源码位置和相关知识卡。

没有配置外部模型时，系统仍可使用关键词与字符相似度检索；这不等同于完整语义理解。向量模型和 CodeGraph 都是可选增强，不影响最小路径成立。

## 启动

- Linux 源码启动：`bash scripts/start.sh`
- 后端：准备 PostgreSQL/pgvector 后运行 Spring Boot，详见 [后端说明](backend/README.md)
- 前端：`npm --prefix frontend ci`，然后 `npm --prefix frontend run dev`
- 环境诊断：`node scripts/check-runtime.mjs`

后端不能只靠前端开发服务器启动；数据库、仓库目录配置和 Java 服务都必须可用。

## 验证

```sh
mvn -pl backend -am test
npm --prefix frontend test -- --run
npm --prefix frontend run build
npm --prefix mcp-server test
node --test scripts/evaluate-quality.test.mjs
node scripts/evaluate-quality.mjs --validate
```

数据库集成测试默认跳过，应只在独立测试数据库与独立受管文件目录中执行。

## 项目结构

| 目录 | 职责 |
| --- | --- |
| `backend` | 仓库接入、版本化索引、代码与知识联合检索 API |
| `frontend` | 项目管理、知识维护和统一检索工作台 |
| `mcp-server` | 向 AI 客户端暴露只读 `search_project` 工具 |
| `evaluation` | 检索召回和证据问答质量样本 |
| `scripts` / `deploy` | 启动、诊断与部署脚本 |

详细使用流程见 [功能与数据流](docs/13-system-usage-map.md)。
