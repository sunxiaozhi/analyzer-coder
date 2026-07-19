<script setup lang="ts">
import { LockKeyhole, ShieldCheck, UserRound } from 'lucide-vue-next';
import { reactive } from 'vue';
import { useRouter } from 'vue-router';
import loginIllustration from '@/assets/login-illustration.png';

const router = useRouter();
const form = reactive({ username: 'admin', password: 'admin123', captcha: 'V7A9' });

function submit() {
  void router.push('/repositories');
}
</script>

<template>
  <main class="login-page">
    <section class="login-visual" aria-hidden="true">
      <img :src="loginIllustration" alt="" />
    </section>
    <section class="login-content">
      <div class="login-stack">
        <div class="login-brand">
          <span class="login-logo">&lt;/&gt;</span>
          <span class="login-brand-text">代码知识平台</span>
        </div>
        <form class="login-card" @submit.prevent="submit">
          <h1>登录</h1>
          <label class="login-field">
            <UserRound :size="17" /><span class="sr-only">用户名</span>
            <input v-model="form.username" autocomplete="username" placeholder="请输入用户名" />
          </label>
          <label class="login-field">
            <LockKeyhole :size="17" /><span class="sr-only">密码</span>
            <input v-model="form.password" type="password" autocomplete="current-password" placeholder="请输入密码" />
          </label>
          <div class="captcha-row">
            <label class="login-field">
              <ShieldCheck :size="17" /><span class="sr-only">验证码</span>
              <input v-model="form.captcha" placeholder="请输入验证码" />
            </label>
            <button class="captcha-image mono" type="button">V7A9</button>
          </div>
          <button class="login-submit" type="submit">登录</button>
        </form>
        <p class="login-footnote">代码与索引数据仅在已配置的服务范围内使用</p>
      </div>
    </section>
  </main>
</template>
