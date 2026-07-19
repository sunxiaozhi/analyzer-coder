<script setup lang="ts">
import { Search } from '@element-plus/icons-vue';
import { computed, ref } from 'vue';

const query = ref('登录鉴权逻辑在哪里实现？');
const selected = ref(0);
const results = [
  { name: 'AuthController.login', score: '0.94', mode: '结构事实 · 精确符号', path: 'src/main/java/...', lines: 'L42-78', text: '负责接收登录请求，校验凭据并创建访问令牌...' },
  { name: 'TokenService.validateToken', score: '0.89', mode: '语义 + 调用关系', path: 'src/main/java/...', lines: 'L42-78', text: '负责接收登录请求，校验凭据并创建访问令牌...' },
  { name: 'SecurityConfig.filterChain', score: '0.82', mode: '关键词 + 结构事实', path: 'src/main/java/...', lines: 'L42-78', text: '负责接收登录请求，校验凭据并创建访问令牌...' },
  { name: 'AuthIntegrationTest', score: '0.76', mode: '相关测试', path: 'src/main/java/...', lines: 'L42-78', text: '负责接收登录请求，校验凭据并创建访问令牌...' },
];
const current = computed(() => results[selected.value]);
const code = '@PostMapping("/login")\n\npublic LoginResponse login(LoginRequest request) {\n  User user = authService.authenticate(\n      request.username(), request.password());\n\n  String token = tokenService.createToken(user);\n\n  return new LoginResponse(token);\n}';
</script>

<template>
  <section class="code-search-design">
    <aside class="search-results-pane">
      <div class="result-search">
        <el-input v-model="query" :prefix-icon="Search" />
        <div><el-tag effect="plain" type="info">综合</el-tag><el-tag effect="plain" type="info">当前仓库</el-tag></div>
      </div>
      <button v-for="(item, index) in results" :key="item.name" :class="{active:selected===index}" @click="selected=index">
        <header><b>{{item.name}}</b><strong>{{item.score}}</strong></header>
        <el-tag effect="plain" :type="index===0?'primary':'info'">{{item.mode}}</el-tag>
        <p>{{item.text}}</p>
        <footer><span class="mono">{{item.path}}</span><span class="mono">· {{item.lines}}</span></footer>
      </button>
    </aside>
    <main class="search-code-pane">
      <header><b>AuthController.java</b><span>main · a1b2c3d · L42-78</span></header>
      <pre><code>{{code}}</code></pre>
    </main>
    <aside class="search-evidence-pane">
      <header><b>命中依据</b><p>识别为功能定位，优先使用语义召回，并通过调用关系补充入口。</p></header>
      <article><b>1. 定义 · {{current.name}}</b><span class="mono">AuthController.java L42-78</span></article>
      <article><b>2. 调用 · TokenService.createToken</b><span class="mono">TokenService.java L31-56</span></article>
      <article><b>3. 测试 · AuthIntegrationTest</b><span class="mono">AuthIntegrationTest.java L20-88</span></article>
    </aside>
  </section>
</template>
