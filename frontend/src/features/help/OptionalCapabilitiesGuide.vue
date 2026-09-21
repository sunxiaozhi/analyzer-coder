<script setup lang="ts">
import { Network, Plug } from 'lucide-vue-next';
import { RouterLink } from 'vue-router';
</script>

<template>
  <section class="optional-guide" aria-labelledby="optional-guide-title">
    <header>
      <span>按需使用</span>
      <h2 id="optional-guide-title">结构查看与 AI 客户端接入</h2>
      <p>联合检索可独立使用；需要追踪调用关系或让编码工具读取项目时，再进入以下功能。</p>
    </header>
    <div class="optional-grid">
      <article>
        <Network :size="21" aria-hidden="true" />
        <h3>代码图谱</h3>
        <p>在已发布 CodeGraph 的项目版本中查看模块、符号、源码和关系。目标分支尚未准备时，先到项目管理完成准备。</p>
        <RouterLink to="/atlas">查看图谱</RouterLink>
      </article>
      <article>
        <Plug :size="21" aria-hidden="true" />
        <h3>MCP 接入</h3>
        <p>创建账户访问令牌，连接同一个 MCP 地址。客户端可发现授权项目和分支，用 contextId 固定版本后查询代码、知识和图谱。</p>
        <RouterLink to="/mcp">查看接入说明</RouterLink>
      </article>
    </div>
  </section>
</template>

<style scoped>
.optional-guide { max-width: 1320px; margin: 24px auto 0; }
.optional-guide header { margin-bottom: 12px; }
.optional-guide header span { color: var(--app-color-action); font-size: 12px; font-weight: 750; letter-spacing: .12em; }
.optional-guide h2 { margin: 3px 0 4px; color: var(--app-color-identity); font-family: var(--app-font-display); font-size: 19px; }
.optional-guide header p { margin: 0; color: var(--app-text-muted); font-size: 14px; }
.optional-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.optional-grid article { display: grid; gap: 10px; align-content: start; min-width: 0; padding: 18px; border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-surface); }
.optional-grid svg { color: var(--app-color-action); }
.optional-grid h3 { margin: 0; color: var(--app-color-identity); font-size: 17px; }
.optional-grid p { margin: 0; color: var(--app-text-regular); font-size: 13px; line-height: 1.7; }
.optional-grid a { width: fit-content; color: var(--app-color-action); font-size: 13px; font-weight: 650; }
@media (max-width: 700px) { .optional-grid { grid-template-columns: 1fr; } }
</style>
