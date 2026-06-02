<script setup lang="ts">
import { Edit, Key, Plus, Refresh } from '@element-plus/icons-vue'
import { reactive, shallowRef, watch } from 'vue'
import {
  ElButton,
  ElCheckbox,
  ElCheckboxGroup,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElMain,
  ElScrollbar,
  ElSwitch,
  ElTag
} from 'element-plus'
import type { AuthUser, ProjectRecord, UserEditForm, UserForm, UserPasswordForm } from '../../types'

const userForm = defineModel<UserForm>('userForm', { required: true })
const userEditForm = defineModel<UserEditForm>('userEditForm', { required: true })
const userPasswordForm = defineModel<UserPasswordForm>('userPasswordForm', { required: true })

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
  updateUserPassword: []
  updateUserAccess: [userId: string, projectIds: string[]]
}>()

const accessDrafts = reactive<Record<string, string[]>>({})
const createDialogOpen = shallowRef(false)
const editDialogOpen = shallowRef(false)
const passwordDialogOpen = shallowRef(false)

function saveUserAccess(userId: string) {
  emit('updateUserAccess', userId, [...(accessDrafts[userId] ?? [])])
}

function projectNames(projectIds: string[]) {
  const names = new Map(props.projects.map((project) => [project.id, project.name]))
  return projectIds.map((projectId) => names.get(projectId) ?? projectId).join('、') || '未分配项目'
}

function openEditDialog(user: AuthUser) {
  userEditForm.value.id = user.id
  userEditForm.value.username = user.username
  userEditForm.value.displayName = user.displayName
  userEditForm.value.isAdmin = user.isAdmin
  editDialogOpen.value = true
}

function openPasswordDialog(user: AuthUser) {
  userPasswordForm.value.id = user.id
  userPasswordForm.value.password = ''
  passwordDialogOpen.value = true
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
    if (passwordDialogOpen.value && userPasswordForm.value.id) {
      passwordDialogOpen.value = false
      userPasswordForm.value.password = ''
    }
  }
)
</script>

<template>
  <ElMain class="page-surface accounts-page">
    <div class="page-header">
      <div>
        <p class="eyebrow">Accounts</p>
        <h2>账号管理</h2>
      </div>
      <div class="page-actions">
        <ElTag type="info" effect="plain">{{ users.length }} 个账号</ElTag>
        <ElButton type="primary" :icon="Plus" @click="createDialogOpen = true">
          新建账号
        </ElButton>
        <ElButton :icon="Refresh" :loading="authBusy" @click="$emit('refreshUsers')">
          刷新
        </ElButton>
      </div>
    </div>

    <section class="account-workbench">
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
        <ElFormItem label="新密码">
          <ElInput v-model="userPasswordForm.password" show-password type="password" placeholder="至少 6 位" />
        </ElFormItem>
        <p v-if="authMessage" class="account-message">{{ authMessage }}</p>
      </ElForm>

      <template #footer>
        <div class="dialog-actions">
          <ElButton :disabled="authBusy" @click="passwordDialogOpen = false">取消</ElButton>
          <ElButton type="primary" :loading="authBusy" @click="$emit('updateUserPassword')">
            修改密码
          </ElButton>
        </div>
      </template>
    </ElDialog>
  </ElMain>
</template>
