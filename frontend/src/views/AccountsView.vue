<script setup lang="ts">
import { Search } from '@element-plus/icons-vue';
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';

const query = ref('');
const role = ref('全部角色');
const state = ref('全部状态');
const dialog = ref(false);
const form = reactive({ name: '', username: '', role: '成员' });
const accounts = ref([
  { name: '张三', initial: '张', note: '系统创建者', username: 'zhangsan', role: '管理员', state: '正常', login: '今天 09:42', ip: '192.168.10.24' },
  { name: '李娜', initial: '李', note: '本地账号', username: 'lina', role: '成员', state: '正常', login: '昨天 18:16', ip: '192.168.10.31' },
  { name: '王强', initial: '王', note: '本地账号', username: 'wangqiang', role: '成员', state: '正常', login: '3 天前', ip: '192.168.10.18' },
  { name: '陈敏', initial: '陈', note: '本地账号', username: 'chenmin', role: '只读', state: '未激活', login: '—', ip: '—' },
  { name: '赵磊', initial: '赵', note: '本地账号', username: 'zhaolei', role: '成员', state: '已停用', login: '2026-06-28', ip: '192.168.10.45' },
]);
const rows = computed(() => accounts.value.filter((item) => (!query.value || item.name.includes(query.value) || item.username.includes(query.value)) && (role.value === '全部角色' || item.role === role.value) && (state.value === '全部状态' || item.state === state.value)));
function addAccount() {
  if (!form.name || !form.username) return ElMessage.warning('请填写姓名和登录账号');
  accounts.value.unshift({ name: form.name, initial: form.name.slice(0, 1), note: '本地账号', username: form.username, role: form.role, state: '未激活', login: '—', ip: '—' });
  dialog.value = false;
}
function handlePageAction(event: Event) {
  const detail = (event as CustomEvent).detail;
  if (detail.route === 'accounts' && detail.label === '新增账号') dialog.value = true;
}
onMounted(() => window.addEventListener('page-primary-action', handlePageAction));
onBeforeUnmount(() => window.removeEventListener('page-primary-action', handlePageAction));
</script>

<template>
  <section class="page account-design">
    <div class="summary-strip">
      <div><span>账号总数</span><b>5</b></div><div><span>正常</span><b>3</b></div><div><span>未激活</span><b>1</b></div><div><span>已停用</span><b>1</b></div>
    </div>
    <div class="surface">
      <div class="toolbar">
        <el-input v-model="query" :prefix-icon="Search" placeholder="搜索姓名、账号" clearable />
        <el-select v-model="role"><el-option v-for="x in ['全部角色','管理员','成员','只读']" :key="x" :label="x" :value="x" /></el-select>
        <el-select v-model="state"><el-option v-for="x in ['全部状态','正常','未激活','已停用']" :key="x" :label="x" :value="x" /></el-select>
      </div>
      <el-table :data="rows">
        <el-table-column label="用户" min-width="190"><template #default="{row}"><div class="user-cell"><span>{{row.initial}}</span><div class="primary-cell"><b>{{row.name}}</b><span>{{row.note}}</span></div></div></template></el-table-column>
        <el-table-column prop="username" label="登录账号" min-width="150"><template #default="{row}"><span class="mono">{{row.username}}</span></template></el-table-column>
        <el-table-column label="角色" width="140"><template #default="{row}"><el-tag effect="plain" type="info">{{row.role}}</el-tag></template></el-table-column>
        <el-table-column label="状态" width="125"><template #default="{row}"><el-tag effect="plain" :type="row.state==='正常'?'primary':'info'">{{row.state}}</el-tag></template></el-table-column>
        <el-table-column prop="login" label="最近登录" width="160" />
        <el-table-column prop="ip" label="最近 IP" min-width="150"><template #default="{row}"><span class="mono muted">{{row.ip}}</span></template></el-table-column>
        <el-table-column label="操作" width="64"><template #default><button class="more-button">•••</button></template></el-table-column>
      </el-table>
    </div>
    <el-dialog v-model="dialog" title="新增账号" width="460"><el-form label-position="top"><el-form-item label="姓名" required><el-input v-model="form.name" /></el-form-item><el-form-item label="登录账号" required><el-input v-model="form.username" /></el-form-item><el-form-item label="角色"><el-select v-model="form.role" style="width:100%"><el-option label="成员" value="成员" /><el-option label="只读" value="只读" /></el-select></el-form-item></el-form><template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" @click="addAccount">创建</el-button></template></el-dialog>
  </section>
</template>
