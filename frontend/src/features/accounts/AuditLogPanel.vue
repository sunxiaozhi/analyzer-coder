<script setup lang="ts">
import { computed,shallowRef,watch } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import AppPagination from '@/components/AppPagination.vue';
import type { AuditEvent } from '@/types/security';
import { formatRelativeTime,formatShanghaiDateTime,shanghaiDateTimeTitle } from '@/utils/dateTime';

const props=defineProps<{rows:AuditEvent[];loading:boolean;focusUsername:string;focusVersion:number}>();
const emit=defineEmits<{refresh:[]}>();
const actor=shallowRef(''),target=shallowRef(''),eventType=shallowRef(''),result=shallowRef(''),range=shallowRef<[Date,Date]|null>(null),page=shallowRef(1),pageSize=shallowRef(15);
const actors=computed(()=>unique(props.rows.map(row=>row.actorUsername)));
const targets=computed(()=>unique(props.rows.map(row=>row.targetUsername)));
const eventTypes=computed(()=>unique(props.rows.map(row=>row.eventType)));
const results=computed(()=>unique(props.rows.map(row=>row.result)));
const filteredRows=computed(()=>props.rows.filter(row=>(!actor.value||row.actorUsername===actor.value)&&(!target.value||row.targetUsername===target.value)&&(!eventType.value||row.eventType===eventType.value)&&(!result.value||row.result===result.value)&&(!range.value||(new Date(row.createdAt)>=range.value[0]&&new Date(row.createdAt)<=endOfDay(range.value[1])))));
const pagedRows=computed(()=>filteredRows.value.slice((page.value-1)*pageSize.value,page.value*pageSize.value));
const eventLabels:Record<string,string>={INITIAL_ADMIN_CREATED:'创建初始管理员',LOGIN_FAILED:'登录失败',LOGIN_SUCCEEDED:'登录成功',LOGOUT:'退出登录',ACCOUNT_LOCKED:'账号锁定',ACCOUNT_CREATED:'创建账号',ACCOUNT_UPDATED:'更新账号',ACCOUNT_DISABLED:'停用账号',ACCOUNT_UNLOCKED:'账号解锁',PASSWORD_CHANGED:'修改密码',PASSWORD_RESET:'重置密码',REPOSITORY_PERMISSION_CHANGED:'修改仓库权限',REPOSITORY_PERMISSION_REVOKED:'撤销仓库权限'};

watch(()=>[props.focusUsername,props.focusVersion] as const,([username])=>{target.value=username;page.value=1;},{immediate:true});
watch([actor,target,eventType,result,range],()=>{page.value=1;});
function unique(values:(string|null)[]):string[]{return [...new Set(values.filter((value):value is string=>Boolean(value)))].sort();}
function endOfDay(value:Date):Date{const date=new Date(value);date.setHours(23,59,59,999);return date;}
function clearFilters(){actor.value='';target.value='';eventType.value='';result.value='';range.value=null;}
function changePage(value:number){page.value=value;}
function changePageSize(value:number){pageSize.value=value;page.value=1;}
</script>

<template>
  <div class="audit-panel">
    <div class="audit-filters">
      <div class="audit-toolbar">
        <el-select v-model="actor" placeholder="操作者" clearable filterable><el-option v-for="item in actors" :key="item" :label="item" :value="item"/></el-select>
        <el-select v-model="target" placeholder="目标账号" clearable filterable><el-option v-for="item in targets" :key="item" :label="item" :value="item"/></el-select>
        <el-select v-model="eventType" placeholder="事件类型" clearable filterable><el-option v-for="item in eventTypes" :key="item" :label="eventLabels[item]??item" :value="item"/></el-select>
        <el-select v-model="result" placeholder="结果" clearable><el-option v-for="item in results" :key="item" :label="item==='SUCCESS'?'成功':'拒绝/失败'" :value="item"/></el-select>
        <el-date-picker v-model="range" class="audit-date-range" type="daterange" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期"/>
        <el-button @click="clearFilters">清空</el-button><el-button :icon="Refresh" :loading="loading" @click="emit('refresh')">刷新</el-button>
      </div>
      <el-alert v-if="focusUsername" :title="`已从账号列表定位：${focusUsername}`" type="info" show-icon closable @close="target=''"/>
    </div>
    <div class="audit-table-region">
      <el-table :data="pagedRows" v-loading="loading" empty-text="没有符合条件的审计事件">
        <el-table-column label="事件" min-width="180"><template #default="{row}"><div class="primary-cell"><b>{{eventLabels[row.eventType]??row.eventType}}</b><span class="mono">{{row.eventType}}</span></div></template></el-table-column>
        <el-table-column label="结果" width="100"><template #default="{row}"><el-tag :type="row.result==='SUCCESS'?'success':'danger'" effect="plain">{{row.result==='SUCCESS'?'成功':'拒绝'}}</el-tag></template></el-table-column>
        <el-table-column prop="actorUsername" label="操作者" min-width="120"><template #default="{row}">{{row.actorUsername??'系统'}}</template></el-table-column>
        <el-table-column prop="targetUsername" label="目标账号" min-width="120"><template #default="{row}">{{row.targetUsername??'—'}}</template></el-table-column>
        <el-table-column prop="repositoryName" label="目标仓库" min-width="140"><template #default="{row}">{{row.repositoryName??'—'}}</template></el-table-column>
        <el-table-column label="时间" width="190"><template #default="{row}"><el-tooltip :content="shanghaiDateTimeTitle(row.createdAt)" placement="top"><div class="audit-time"><span>{{formatShanghaiDateTime(row.createdAt)}}</span><small>{{formatRelativeTime(row.createdAt)}}</small></div></el-tooltip></template></el-table-column>
      </el-table>
    </div>
    <AppPagination
      :page-num="page"
      :page-size="pageSize"
      :total="filteredRows.length"
      :disabled="loading"
      @page-change="changePage"
      @size-change="changePageSize"
    />
  </div>
</template>

<style scoped>
.audit-panel{display:grid;grid-template-rows:auto minmax(0,1fr) auto;min-height:0;height:100%}
.audit-filters{display:grid;gap:12px;padding-bottom:12px}
.audit-toolbar{display:flex;flex-wrap:wrap;gap:10px;padding:10px 20px}
.audit-toolbar .el-select{width:150px}
.audit-toolbar .audit-date-range{width:260px}
.audit-table-region{min-height:0;overflow-x:hidden;overflow-y:auto;overscroll-behavior:contain}
.audit-time{display:grid;gap:2px}
.audit-time small{color:var(--el-text-color-secondary)}

@media (max-width: 760px) {
  .audit-panel{display:block;height:auto}
  .audit-toolbar{padding-inline:12px}
  .audit-toolbar .audit-date-range{width:100%}
  .audit-table-region{overflow:visible}
}
</style>
