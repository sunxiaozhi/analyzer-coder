<script setup lang="ts">
import MarkdownIt from 'markdown-it'
import { computed } from 'vue'
import { DocumentAdd, Notebook, Select, View } from '@element-plus/icons-vue'
import {
  ElButton,
  ElCard,
  ElForm,
  ElFormItem,
  ElIcon,
  ElInput,
  ElOption,
  ElSelect,
  ElScrollbar,
  ElTabPane,
  ElTabs
} from 'element-plus'

const path = defineModel<string>('path', { required: true })
const content = defineModel<string>('content', { required: true })

defineProps<{
  busy: boolean
  selectedPath: string
}>()

const emit = defineEmits<{
  createFile: [path: string, content: string]
  saveFile: []
}>()

const markdown = new MarkdownIt({
  breaks: true,
  html: false,
  linkify: true
})

const renderedMarkdown = computed(() => markdown.render(content.value || ''))

const templates = {
  domain: {
    path: 'domain/user-registration.md',
    content: `# 用户注册

## 业务规则
- 手机号必须唯一

## 相关接口
- POST /api/users/register

## 相关代码
- UserController.register
- UserService.createUser

## 边界条件
- 手机号为空时拒绝
- 手机号已存在时返回业务错误
`
  },
  api: {
    path: 'api/user-api.md',
    content: `# 用户接口

## 接口
- POST /api/users/register

## 请求
- phone: 用户手机号

## 响应
- 注册成功返回用户信息
- 手机号重复返回业务错误
`
  },
  decision: {
    path: 'decisions/phone-unique-rule.md',
    content: `# 手机号唯一规则

## 决策
注册以手机号作为唯一身份标识。

## 原因
- 降低重复账户风险
- 便于后续登录和通知流程

## 影响范围
- 用户注册
- 用户资料更新
`
  },
  troubleshooting: {
    path: 'troubleshooting/registration-errors.md',
    content: `# 注册错误排查

## 现象
用户注册失败。

## 排查
- 检查手机号是否已存在
- 检查注册接口是否返回业务错误
- 检查数据库唯一约束
`
  }
}

function applyTemplate(name: keyof typeof templates) {
  path.value = templates[name].path
  content.value = templates[name].content
}

function createFile() {
  emit('createFile', path.value, content.value)
}
</script>

<template>
  <ElCard class="panel kb-editor-panel" shadow="never">
    <template #header>
      <div class="panel-title">
        <ElIcon><Notebook /></ElIcon>
        <span>{{ selectedPath || '新建知识文档' }}</span>
      </div>
    </template>

    <ElForm label-position="top" class="control-form kb-editor-form">
      <ElFormItem label="文档路径">
        <ElInput v-model="path" clearable placeholder="domain/user-registration.md" />
      </ElFormItem>
      <ElFormItem label="模板">
        <ElSelect class="control-select" placeholder="选择模板" @change="applyTemplate">
          <ElOption label="业务规则" value="domain" />
          <ElOption label="接口说明" value="api" />
          <ElOption label="决策记录" value="decision" />
          <ElOption label="故障排查" value="troubleshooting" />
        </ElSelect>
      </ElFormItem>
    </ElForm>

    <ElTabs class="kb-editor-tabs">
      <ElTabPane>
        <template #label>
          <span class="tab-label"><ElIcon><DocumentAdd /></ElIcon>编辑</span>
        </template>
        <ElInput v-model="content" class="kb-editor-input" type="textarea" resize="none" />
      </ElTabPane>
      <ElTabPane>
        <template #label>
          <span class="tab-label"><ElIcon><View /></ElIcon>预览</span>
        </template>
        <ElScrollbar class="kb-preview">
          <article v-html="renderedMarkdown"></article>
        </ElScrollbar>
      </ElTabPane>
    </ElTabs>

    <div class="kb-editor-actions">
      <ElButton :disabled="busy" :icon="DocumentAdd" @click="createFile">
        新建
      </ElButton>
      <ElButton type="primary" :loading="busy" :icon="Select" @click="$emit('saveFile')">
        保存
      </ElButton>
    </div>
  </ElCard>
</template>
