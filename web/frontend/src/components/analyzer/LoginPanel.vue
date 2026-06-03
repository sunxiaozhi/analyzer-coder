<script setup lang="ts">
import { Lock, Right, User } from '@element-plus/icons-vue'
import { ElButton, ElCard, ElForm, ElFormItem, ElInput } from 'element-plus'
import BrandLogo from './BrandLogo.vue'

const form = defineModel<{ username: string; password: string }>('form', { required: true })

defineProps<{
  busy: boolean
  message: string
}>()

defineEmits<{
  login: []
}>()
</script>

<template>
  <main class="login-screen">
    <ElCard class="login-card" shadow="never">
      <div class="login-brand">
        <BrandLogo />
        <div>
          <h1>代码智库</h1>
        </div>
      </div>

      <ElForm class="control-form login-form" label-position="top" @submit.prevent="$emit('login')">
        <ElFormItem label="账号">
          <ElInput v-model="form.username" placeholder="admin" :prefix-icon="User" />
        </ElFormItem>
        <ElFormItem label="密码">
          <ElInput
            v-model="form.password"
            placeholder="admin123"
            show-password
            type="password"
            :prefix-icon="Lock"
            @keyup.enter="$emit('login')"
          />
        </ElFormItem>
        <ElButton class="login-submit" type="primary" :loading="busy" :icon="Right" @click="$emit('login')">
          登录
        </ElButton>
      </ElForm>

      <p v-if="message" class="login-message">{{ message }}</p>
    </ElCard>
  </main>
</template>
