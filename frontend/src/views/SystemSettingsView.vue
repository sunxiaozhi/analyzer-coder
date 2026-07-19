<script setup lang="ts">
import { reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
const tab = ref('路径与安全');
const tabs = ['路径与安全', '模型服务', '索引参数', '排除规则', '运行日志'];
const form = reactive({ roots: 'C:\\workspace; D:\\repositories', links: '允许，但不得越过根目录', external: true, excludes: '.env*, *.pem, *.key, credentials.*, secrets/**' });
</script>

<template>
  <section class="settings-design surface">
    <aside class="settings-nav"><button v-for="item in tabs" :key="item" :class="{active:tab===item}" @click="tab=item">{{item}}</button></aside>
    <main class="settings-content">
      <template v-if="tab==='路径与安全'">
        <div class="settings-heading"><h2>路径与数据安全</h2><p>限制服务可访问的代码目录，并控制代码是否允许发送到外部模型。</p></div>
        <el-form label-position="top">
          <el-form-item label="允许导入的根目录"><el-input v-model="form.roots" /><small>仓库规范化路径必须位于其中一个目录下。</small></el-form-item>
          <el-form-item label="符号链接策略"><el-select v-model="form.links" style="width:100%"><el-option label="允许，但不得越过根目录" value="允许，但不得越过根目录" /><el-option label="完全禁止" value="完全禁止" /></el-select></el-form-item>
          <el-form-item label="允许发送代码到外部模型"><div class="switch-line"><el-switch v-model="form.external" /><span>已启用，仅发送检索命中的片段</span></div></el-form-item>
          <el-alert title="外部调用记录 provider、模型、发送字符数和时间，不记录明文代码。" type="info" :closable="false" />
          <el-form-item label="敏感文件默认排除"><el-input v-model="form.excludes" /><small>规则变更后，相关仓库需要重新索引。</small></el-form-item>
          <el-button round @click="ElMessage.success('路径权限检查通过')">测试路径权限</el-button>
        </el-form>
      </template>
      <div v-else class="settings-placeholder"><b>{{tab}}</b><p>此处使用本地 mock 配置，保存后仅在当前浏览器会话生效。</p></div>
    </main>
  </section>
</template>
