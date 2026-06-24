<script setup lang="ts">
import { Edit, Key, Plus, Refresh, UserFilled } from '@element-plus/icons-vue'
import { computed, reactive, shallowRef, watch } from 'vue'
import {
  ElButton,
  ElCheckbox,
  ElCheckboxGroup,
  ElDialog,
  ElForm,
  ElFormItem,
  ElIcon,
  ElInput,
  ElMain,
  ElScrollbar,
  ElSwitch,
  ElTag
} from 'element-plus'
import type { AuthUser, ProjectRecord, UserEditForm, UserForm } from '../../types'

const userForm = defineModel<UserForm>('userForm', { required: true })
const userEditForm = defineModel<UserEditForm>('userEditForm', { required: true })

const props = defineProps<{
  authBusy: boolean
  authMessage: string
  projects: ProjectRecord[]
  users: AuthUser[]
}>()

const emit = defineEmits<{
  createUser: []
  refreshUsers: []
  updateUser: []
  updateUserPassword: [userId: string, password: string]
  updateUserAccess: [userId: string, projectIds: string[]]
}>()

const accessDrafts = reactive<Record<string, string[]>>({})
const passwordForm = reactive({ userId: '', username: '', password: '' })
const createDialogOpen = shallowRef(false)
const editDialogOpen = shallowRef(false)
const passwordDialogOpen = shallowRef(false)
const adminCount = computed(() => props.users.filter((user) => user.isAdmin).length)
const memberCount = computed(() => props.users.length - adminCount.value)

function saveUserAccess(userId: string) {
  emit('updateUserAccess', userId, [...(accessDrafts[userId] ?? [])])
}

function projectNames(projectIds: string[]) {
  const names = new Map(props.projects.map((project) => [project.id, project.name]))
  return projectIds.map((projectId) => names.get(projectId) ?? projectId).join('、') || '未分配项目'
}

function openEditDialog(user: AuthUser) {
  userEditForm.value = {
    id: user.id,
    username: user.username,
    displayName: user.displayName,
    isAdmin: user.isAdmin
  }
  editDialogOpen.value = true
}

function openPasswordDialog(user: AuthUser) {
  passwordForm.userId = user.id
  passwordForm.username = user.username
  passwordForm.password = ''
  passwordDialogOpen.value = true
}

function submitPassword() {
  emit('updateUserPassword', passwordForm.userId, passwordForm.password)
}

watch(
  () => props.users,
  (users) => {
    for (const user of users) {
      accessDrafts[user.id] = [...user.projectIds]
    }
  },
  { immediate: true }
)

watch(
  () => props.users.length,
  (count, previousCount) => {
    if (createDialogOpen.value && count > previousCount) {
      createDialogOpen.value = false
    }
  }
)

watch(
  () => props.users,
  () => {
    if (editDialogOpen.value && userEditForm.value.id) {
      editDialogOpen.value = false
    }
    if (passwordDialogOpen.value && passwordForm.userId) {
      passwordDialogOpen.value = false
      passwordForm.password = ''
    }
  }
)
</script>

<template>
  <ElMain class="page-surface accounts-page console-page">
    <section class="account-command console-command">
      <div class="account-heading console-heading">
        <ElIcon><UserFilled /></ElIcon>
        <div>
          <h2>账号管理</h2>
          <span>系统账号与项目授权 · {{ users.length }} 个账号</span>
        </div>
      </div>

      <div class="account-command-actions console-command-actions">
        <ElTag type="warning" effect="plain">管理员 {{ adminCount }}</ElTag>
        <ElTag type="info" effect="plain">成员 {{ memberCount }}</ElTag>
        <ElButton type="primary" :icon="Plus" @click="createDialogOpen = true">
          新建账号
        </ElButton>
        <ElButton :icon="Refresh" :loading="authBusy" @click="$emit('refreshUsers')">
          刷新
        </ElButton>
      </div>
    </section>

    <section class="account-workbench console-workbench">
      <ElScrollbar class="account-list-scroll">
        <div class="account-list">
          <div class="account-list-head">
            <span>账号</span>
            <span>角色</span>
            <span>项目权限</span>
            <span>操作</span>
          </div>
          <div v-for="user in users" :key="user.id" class="account-row">
            <div class="account-identity">
              <strong>{{ user.displayName }}</strong>
              <span>{{ user.username }}</span>
            </div>
            <div>
              <ElTag v-if="user.isAdmin" type="warning" effect="plain">管理员</ElTag>
              <ElTag v-else type="info" effect="plain">项目成员</ElTag>
            </div>
            <div class="account-projects">
              <span class="account-project-summary">{{ user.isAdmin ? '全部项目' : projectNames(user.projectIds) }}</span>
              <ElCheckboxGroup v-model="accessDrafts[user.id]" class="access-project-checks" :disabled="user.isAdmin">
                <ElCheckbox v-for="project in projects" :key="project.id" :label="project.id">
                  {{ project.name }}
                </ElCheckbox>
              </ElCheckboxGroup>
            </div>
            <div class="account-actions">
              <ElButton size="small" :icon="Edit" @click="openEditDialog(user)">
                编辑
              </ElButton>
              <ElButton size="small" :icon="Key" @click="openPasswordDialog(user)">
                改密
              </ElButton>
              <ElButton
                size="small"
                type="primary"
                plain
                :disabled="user.isAdmin"
                :loading="authBusy"
                @click="saveUserAccess(user.id)"
              >
                保存授权
              </ElButton>
            </div>
          </div>
        </div>
      </ElScrollbar>
    </section>

    <ElDialog v-model="createDialogOpen" title="新建账号" width="520px">
      <ElForm label-position="top" class="control-form account-dialog-form">
        <ElFormItem label="账号">
          <ElInput v-model="userForm.username" clearable placeholder="例如 zhangsan" />
        </ElFormItem>
        <ElFormItem label="显示名称">
          <ElInput v-model="userForm.displayName" clearable placeholder="例如 张三" />
        </ElFormItem>
        <ElFormItem label="初始密码">
          <ElInput v-model="userForm.password" show-password type="password" placeholder="至少 6 位" />
        </ElFormItem>
        <ElFormItem label="管理员">
          <ElSwitch v-model="userForm.isAdmin" />
        </ElFormItem>
        <ElFormItem label="可访问项目">
          <ElCheckboxGroup v-model="userForm.projectIds" class="access-project-checks account-dialog-projects">
            <ElCheckbox v-for="project in projects" :key="project.id" :label="project.id">
              {{ project.name }}
            </ElCheckbox>
          </ElCheckboxGroup>
        </ElFormItem>
        <p v-if="authMessage" class="account-message">{{ authMessage }}</p>
      </ElForm>

      <template #footer>
        <div class="dialog-actions">
          <ElButton :disabled="authBusy" @click="createDialogOpen = false">取消</ElButton>
          <ElButton type="primary" :loading="authBusy" @click="$emit('createUser')">
            创建账号
          </ElButton>
        </div>
      </template>
    </ElDialog>

    <ElDialog v-model="editDialogOpen" title="编辑账号" width="520px">
      <ElForm label-position="top" class="control-form account-dialog-form">
        <ElFormItem label="账号">
          <ElInput v-model="userEditForm.username" clearable />
        </ElFormItem>
        <ElFormItem label="显示名称">
          <ElInput v-model="userEditForm.displayName" clearable />
        </ElFormItem>
        <ElFormItem label="管理员">
          <ElSwitch v-model="userEditForm.isAdmin" />
        </ElFormItem>
        <p v-if="authMessage" class="account-message">{{ authMessage }}</p>
      </ElForm>

      <template #footer>
        <div class="dialog-actions">
          <ElButton :disabled="authBusy" @click="editDialogOpen = false">取消</ElButton>
          <ElButton type="primary" :loading="authBusy" @click="$emit('updateUser')">
            保存
          </ElButton>
        </div>
      </template>
    </ElDialog>

    <ElDialog v-model="passwordDialogOpen" title="修改密码" width="460px">
      <ElForm label-position="top" class="control-form account-dialog-form">
        <ElFormItem label="账号">
          <ElInput v-model="passwordForm.username" disabled />
        </ElFormItem>
        <ElFormItem label="新密码">
          <ElInput v-model="passwordForm.password" show-password type="password" placeholder="至少 6 位" />
        </ElFormItem>
        <p v-if="authMessage" class="account-message">{{ authMessage }}</p>
      </ElForm>

      <template #footer>
        <div class="dialog-actions">
          <ElButton :disabled="authBusy" @click="passwordDialogOpen = false">取消</ElButton>
          <ElButton type="primary" :loading="authBusy" @click="submitPassword">
            修改密码
          </ElButton>
        </div>
      </template>
    </ElDialog>
  </ElMain>
</template>

<style scoped>
.accounts-page {
  gap: 0;
  grid-template-rows: auto minmax(0, 1fr);
  padding: 0;
}

.account-command {
  align-items: center;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbfd 100%);
  border-bottom: 1px solid var(--line);
  box-shadow: var(--shadow-sm);
  display: flex;
  gap: 16px;
  justify-content: space-between;
  padding: 16px 24px;
}

.account-heading {
  align-items: center;
  display: flex;
  gap: 12px;
  min-width: 0;
}

.account-heading > .el-icon {
  align-items: center;
  background: var(--accent-soft);
  border-radius: 8px;
  color: var(--accent);
  display: inline-flex;
  flex: 0 0 40px;
  height: 40px;
  justify-content: center;
  width: 40px;
}

.account-heading h2 {
  color: var(--text);
  font-size: 1.08rem;
  margin: 0;
}

.account-heading span {
  color: var(--text-faint);
  display: block;
  font-size: 0.78rem;
  margin-top: 3px;
}

.account-command-actions {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: flex-end;
}

.account-command-actions .el-button {
  min-width: 112px;
}

.account-workbench {
  padding: 18px 24px 24px;
}

@media (max-width: 760px) {
  .account-command {
    align-items: stretch;
    flex-direction: column;
    padding: 14px 16px;
  }

  .account-command-actions {
    align-items: stretch;
    display: grid;
    justify-content: stretch;
  }

  .account-command-actions .el-button {
    width: 100%;
  }

  .account-workbench {
    padding: 14px 16px;
  }
}
</style>
