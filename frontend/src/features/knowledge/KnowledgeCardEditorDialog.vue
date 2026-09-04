<script setup lang="ts">
import { Upload } from '@element-plus/icons-vue';
import { computed, reactive, ref, shallowRef, watch } from 'vue';
import { ElMessage } from 'element-plus';
import {
  intelligenceApi,
  type CardInput,
  type CodeReference,
  type KnowledgeAttachment,
  type KnowledgeCard,
  type KnowledgeEnforcement,
  type KnowledgeKind,
  type KnowledgeSeverity,
} from '@/api/intelligence';
import { engineeringProjectsApi, type EngineeringProject } from '@/api/engineeringProjects';
import { repositoryGovernanceApi, type RepositoryMember } from '@/api/repositoryGovernance';
import { useAuthStore } from '@/stores/authStore';
import KnowledgeAccountSelect from './KnowledgeAccountSelect.vue';
import KnowledgeAttachmentList from './KnowledgeAttachmentList.vue';
import KnowledgeMarkdownEditor from './KnowledgeMarkdownEditor.vue';
import KnowledgeCodeReferenceSelector from './KnowledgeCodeReferenceSelector.vue';

const props = defineProps<{
  modelValue: boolean;
  repositoryId: string;
  card: KnowledgeCard | null;
  busy: boolean;
}>();
const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  submit: [value: CardInput];
  openCode: [reference: CodeReference];
}>();

const auth = useAuthStore();
const expanded = ref<string[]>([]);
const form = reactive<CardInput>(emptyForm());
const engineeringText = reactive({
  paths: '', symbols: '', modules: '', services: '',
  tests: '', instructions: '', prohibitedPaths: '',
});
const items = shallowRef<KnowledgeAttachment[]>([]);
const uploading = shallowRef(false);
const codeReferences = shallowRef<CodeReference[]>([]);
const members = shallowRef<RepositoryMember[]>([]);
const membersLoading = shallowRef(false);
const engineeringProjects = shallowRef<EngineeringProject[]>([]);
const topologyLoading = shallowRef(false);

const kindOptions: { value: KnowledgeKind; label: string }[] = [
  { value: 'REFERENCE', label: '参考资料' },
  { value: 'BUSINESS_RULE', label: '业务规则' },
  { value: 'ARCH_DECISION', label: '架构决策' },
  { value: 'API_CONTRACT', label: '接口契约' },
  { value: 'DATA_CONSTRAINT', label: '数据约束' },
  { value: 'TEST_OBLIGATION', label: '测试义务' },
  { value: 'SECURITY_POLICY', label: '安全策略' },
  { value: 'RUNBOOK', label: '运行手册' },
  { value: 'INCIDENT_LESSON', label: '事故经验' },
  { value: 'OWNERSHIP', label: '责任归属' },
  { value: 'TECH_DEBT', label: '技术债' },
];
const severityOptions: { value: KnowledgeSeverity; label: string }[] = [
  { value: 'INFO', label: '提示' },
  { value: 'WARNING', label: '警告' },
  { value: 'CRITICAL', label: '严重' },
];
const enforcementOptions: { value: KnowledgeEnforcement; label: string; hint: string }[] = [
  { value: 'REFERENCE', label: '仅参考', hint: '不产生测试或审批要求' },
  { value: 'ADVISORY', label: '建议执行', hint: '命中时提示开发者确认' },
  { value: 'REQUIRED', label: '必须执行', hint: '发布前必须有负责人、范围和当前代码证据' },
];

const repositoryOptions = computed(() => [...new Map(engineeringProjects.value
  .flatMap(project => project.repositories)
  .map(repository => [repository.repositoryId, repository])).values()]);
const contractOptions = computed(() => [...new Map(engineeringProjects.value
  .flatMap(project => project.contracts)
  .map(contract => [contract.id, contract])).values()]);
const referenceHasObligations = computed(() => form.enforcement === 'REFERENCE'
  && Boolean(engineeringText.tests.trim() || form.obligations.requiredApproverAccountIds.length
    || engineeringText.instructions.trim() || engineeringText.prohibitedPaths.trim()
    || form.obligations.knowledgeUpdateRequired));
const requiredIncomplete = computed(() => form.enforcement === 'REQUIRED'
  && (!form.ownerAccountId || !hasScope()));

watch(() => [props.modelValue, props.card] as const, () => {
  if (!props.modelValue) return;
  const cardReferences = props.card?.codeReferences ?? [];
  Object.assign(form, props.card ? {
    title: props.card.title,
    cardType: props.card.cardType,
    content: props.card.content,
    tags: [...props.card.tags],
    knowledgeKind: props.card.knowledgeKind,
    severity: props.card.severity,
    enforcement: props.card.enforcement,
    ownerAccountId: props.card.ownerAccountId,
    scope: {
      pathPatterns: [...props.card.scope.pathPatterns],
      symbols: [...props.card.scope.symbols],
      modules: [...props.card.scope.modules],
      repositoryIds: [...(props.card.scope.repositoryIds ?? [])],
      serviceNames: [...(props.card.scope.serviceNames ?? [])],
      contractIds: [...(props.card.scope.contractIds ?? [])],
    },
    obligations: {
      requiredTests: [...props.card.obligations.requiredTests],
      requiredApproverAccountIds: [...props.card.obligations.requiredApproverAccountIds],
      instructions: [...props.card.obligations.instructions],
      prohibitedPathPatterns: [...(props.card.obligations.prohibitedPathPatterns ?? [])],
      knowledgeUpdateRequired: props.card.obligations.knowledgeUpdateRequired ?? false,
    },
    attachmentIds: props.card.attachments.map(item => item.id),
    codeReferences: cardReferences.filter(item => item.chunkId).map(item => ({ chunkId: item.chunkId! })),
  } : emptyForm());
  items.value = props.card ? [...props.card.attachments] : [];
  codeReferences.value = [...cardReferences];
  engineeringText.paths = form.scope.pathPatterns.join('\n');
  engineeringText.symbols = form.scope.symbols.join('\n');
  engineeringText.modules = form.scope.modules.join('\n');
  engineeringText.services = form.scope.serviceNames.join('\n');
  engineeringText.tests = form.obligations.requiredTests.join('\n');
  engineeringText.instructions = form.obligations.instructions.join('\n');
  engineeringText.prohibitedPaths = form.obligations.prohibitedPathPatterns.join('\n');
  expanded.value = props.card && props.card.enforcement !== 'REFERENCE' ? ['engineering'] : [];
  void Promise.all([loadMembers(), loadTopology()]);
}, { immediate: true });

watch(() => form.enforcement, enforcement => {
  if (enforcement !== 'REFERENCE' && !expanded.value.includes('engineering')) {
    expanded.value = [...expanded.value, 'engineering'];
  }
});

function emptyForm(): CardInput {
  return {
    title: '', cardType: '模块说明', content: '', tags: [],
    knowledgeKind: 'REFERENCE', severity: 'INFO', enforcement: 'REFERENCE',
    ownerAccountId: auth.account?.id ?? null,
    scope: { pathPatterns: [], symbols: [], modules: [], repositoryIds: [], serviceNames: [], contractIds: [] },
    obligations: {
      requiredTests: [], requiredApproverAccountIds: [], instructions: [],
      prohibitedPathPatterns: [], knowledgeUpdateRequired: false,
    },
    attachmentIds: [], codeReferences: [],
  };
}

function lines(value: string) {
  return [...new Set(value.split(/\r?\n/).map(item => item.trim()).filter(Boolean))];
}

function hasScope() {
  return Boolean(engineeringText.paths.trim() || engineeringText.symbols.trim()
    || engineeringText.modules.trim() || form.scope.repositoryIds.length
    || engineeringText.services.trim() || form.scope.contractIds.length);
}

function useCurrentAccount() {
  form.ownerAccountId = auth.account?.id ?? null;
}

async function loadMembers() {
  if (!props.modelValue || !props.repositoryId) return;
  membersLoading.value = true;
  try {
    members.value = await repositoryGovernanceApi.members(props.repositoryId);
  } catch (error) {
    members.value = auth.account ? [{
      accountId: auth.account.id,
      username: auth.account.username,
      displayName: auth.account.displayName,
      accountRole: auth.account.role,
      enabled: true,
      relationship: 'MAINTAIN',
      permissionLevel: 'MAINTAIN',
    }] : [];
    ElMessage.error(error instanceof Error ? error.message : '仓库成员加载失败');
  } finally {
    membersLoading.value = false;
  }
}

async function loadTopology() {
  if (!props.modelValue) return;
  topologyLoading.value = true;
  try {
    engineeringProjects.value = await engineeringProjectsApi.list();
  } catch (error) {
    engineeringProjects.value = [];
    ElMessage.error(error instanceof Error ? error.message : '工程项目拓扑加载失败');
  } finally {
    topologyLoading.value = false;
  }
}

function clearObligations() {
  engineeringText.tests = '';
  form.obligations.requiredApproverAccountIds = [];
  engineeringText.instructions = '';
  engineeringText.prohibitedPaths = '';
  form.obligations.knowledgeUpdateRequired = false;
}

async function choose(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files ?? []);
  if (!files.length) return;
  uploading.value = true;
  try {
    for (const file of files) {
      const attachment = await intelligenceApi.uploadAttachment(props.repositoryId, file);
      items.value = [...items.value, attachment];
    }
    ElMessage.success(`已上传 ${files.length} 个附件`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '附件上传失败');
  } finally {
    uploading.value = false;
    input.value = '';
  }
}

function remove(id: string) { items.value = items.value.filter(item => item.id !== id); }
function insert(item: KnowledgeAttachment) {
  const alt = item.originalName.replace(/\.[^.]+$/, '');
  form.content += `${form.content.endsWith('\n') || !form.content ? '' : '\n'}![${alt}](knowledge-attachment://${item.id})\n`;
}

function save() {
  form.tags = [...new Set(form.tags.map(value => value.trim()).filter(Boolean))];
  form.attachmentIds = items.value.map(item => item.id);
  form.codeReferences = codeReferences.value.filter(item => item.chunkId).map(item => ({ chunkId: item.chunkId! }));
  form.scope = {
    pathPatterns: lines(engineeringText.paths),
    symbols: lines(engineeringText.symbols),
    modules: lines(engineeringText.modules),
    repositoryIds: [...form.scope.repositoryIds],
    serviceNames: lines(engineeringText.services),
    contractIds: [...form.scope.contractIds],
  };
  form.obligations = {
    requiredTests: lines(engineeringText.tests),
    requiredApproverAccountIds: [...form.obligations.requiredApproverAccountIds],
    instructions: lines(engineeringText.instructions),
    prohibitedPathPatterns: lines(engineeringText.prohibitedPaths),
    knowledgeUpdateRequired: form.obligations.knowledgeUpdateRequired,
  };
  form.ownerAccountId = form.ownerAccountId?.trim() || null;
  emit('submit', {
    ...form,
    tags: [...form.tags],
    scope: { ...form.scope },
    obligations: { ...form.obligations },
    attachmentIds: [...form.attachmentIds],
    codeReferences: [...form.codeReferences],
  });
}
</script>

<template>
  <el-dialog :model-value="modelValue" :title="card ? '编辑工程知识' : '新建工程知识'"
    width="960" top="3vh" destroy-on-close @update:model-value="emit('update:modelValue', $event)">
    <div class="editor-thesis">
      <span>工程知识</span>
      <p>先记录清楚，再决定它对开发任务是参考、建议还是必须执行。</p>
      <b :data-enforcement="form.enforcement">
        {{ enforcementOptions.find(item => item.value === form.enforcement)?.label }}
      </b>
    </div>

    <el-form label-position="top" class="knowledge-card-form">
      <section class="form-section basic-section">
        <div class="section-marker"><span>01</span><small>内容</small></div>
        <div class="section-body">
          <div class="form-grid title-grid">
            <el-form-item label="标题" required>
              <el-input v-model="form.title" maxlength="200" show-word-limit />
            </el-form-item>
            <el-form-item label="工程知识类型">
              <el-select v-model="form.knowledgeKind" class="full-width" filterable>
                <el-option v-for="option in kindOptions" :key="option.value"
                  :label="option.label" :value="option.value" />
              </el-select>
            </el-form-item>
          </div>
          <div class="form-grid policy-grid">
            <el-form-item label="业务分类">
              <el-input v-model="form.cardType" maxlength="40" placeholder="例如：模块说明、开发规范" />
            </el-form-item>
            <el-form-item label="严重程度">
              <el-segmented v-model="form.severity" :options="severityOptions" block />
            </el-form-item>
            <el-form-item label="执行级别">
              <el-select v-model="form.enforcement" class="full-width">
                <el-option v-for="option in enforcementOptions" :key="option.value"
                  :label="option.label" :value="option.value">
                  <div class="enforcement-option"><b>{{ option.label }}</b><span>{{ option.hint }}</span></div>
                </el-option>
              </el-select>
            </el-form-item>
          </div>
          <el-form-item label="知识正文（Markdown）" required>
            <KnowledgeMarkdownEditor v-model="form.content" :repository-id="repositoryId" class="full-width" />
          </el-form-item>
          <el-form-item label="标签">
            <el-select v-model="form.tags" class="full-width" multiple filterable allow-create
              default-first-option placeholder="输入标签后按回车，可添加多个" />
          </el-form-item>
        </div>
      </section>

      <el-collapse v-model="expanded" class="engineering-collapse">
        <el-collapse-item name="engineering">
          <template #title>
            <div class="collapse-title">
              <span>02—05</span>
              <div><b>适用范围、开发要求与代码证据</b><small>普通参考知识可以跳过</small></div>
              <em>{{ codeReferences.length }} 条代码证据</em>
            </div>
          </template>

          <div class="engineering-spine">
            <section class="form-section">
              <div class="section-marker"><span>02</span><small>范围</small></div>
              <div class="section-body">
                <div class="section-heading"><h3>这条知识适用于哪里</h3><p>每行一项；路径使用仓库相对通配规则。</p></div>
                <div class="form-grid three-columns">
                  <el-form-item label="路径规则">
                    <el-input v-model="engineeringText.paths" type="textarea" :rows="4"
                      placeholder="backend/src/**/refund/**" />
                  </el-form-item>
                  <el-form-item label="符号">
                    <el-input v-model="engineeringText.symbols" type="textarea" :rows="4"
                      placeholder="RefundService&#10;approveRefund" />
                  </el-form-item>
                  <el-form-item label="模块">
                    <el-input v-model="engineeringText.modules" type="textarea" :rows="4" placeholder="backend" />
                  </el-form-item>
                </div>
                <div class="form-grid three-columns cross-scope-grid">
                  <el-form-item label="工程项目仓库">
                    <el-select v-model="form.scope.repositoryIds" class="full-width" multiple filterable
                      collapse-tags collapse-tags-tooltip :loading="topologyLoading"
                      placeholder="从工程项目中选择仓库">
                      <el-option v-for="repository in repositoryOptions" :key="repository.repositoryId"
                        :value="repository.repositoryId"
                        :label="`${repository.repositoryName}（${repository.serviceName}）`" />
                    </el-select>
                    <small v-if="!topologyLoading && !repositoryOptions.length">尚未配置跨仓工程项目时可留空。</small>
                  </el-form-item>
                  <el-form-item label="工程服务名">
                    <el-input v-model="engineeringText.services" type="textarea" :rows="3"
                      placeholder="order-service" />
                  </el-form-item>
                  <el-form-item label="已验证契约">
                    <el-select v-model="form.scope.contractIds" class="full-width" multiple filterable
                      collapse-tags collapse-tags-tooltip :loading="topologyLoading"
                      placeholder="从工程项目中选择契约">
                      <el-option v-for="contract in contractOptions" :key="contract.id" :value="contract.id"
                        :disabled="!contract.current"
                        :label="`${contract.name}（${contract.contractKey}）${contract.current ? '' : ' · 证据待刷新'}`" />
                    </el-select>
                    <small v-if="!topologyLoading && !contractOptions.length">尚未登记跨仓契约时可留空。</small>
                  </el-form-item>
                </div>
              </div>
            </section>

            <section class="form-section">
              <div class="section-marker"><span>03</span><small>要求</small></div>
              <div class="section-body">
                <div class="section-heading"><h3>命中后需要做什么</h3><p>参考知识不产生强制义务。</p></div>
                <el-alert v-if="referenceHasObligations" type="warning" :closable="false">
                  <template #title>
                    当前执行级别为“仅参考”，请清空下方要求或调整执行级别。
                    <el-button link type="warning" @click="clearObligations">清空要求</el-button>
                  </template>
                </el-alert>
                <div class="form-grid three-columns">
                  <el-form-item label="必需测试">
                    <el-input v-model="engineeringText.tests" type="textarea" :rows="4"
                      placeholder="./mvnw test&#10;npm run test" />
                  </el-form-item>
                  <el-form-item label="必需审批人">
                    <KnowledgeAccountSelect
                      v-model="form.obligations.requiredApproverAccountIds"
                      :members="members"
                      :loading="membersLoading"
                      multiple
                      placeholder="选择一个或多个仓库成员"
                    />
                  </el-form-item>
                  <el-form-item label="补充开发要求">
                    <el-input v-model="engineeringText.instructions" type="textarea" :rows="4"
                      placeholder="修改接口时同步更新契约测试" />
                  </el-form-item>
                </div>
                <div class="form-grid ci-policy-grid">
                  <el-form-item label="禁止修改路径（CI）">
                    <el-input v-model="engineeringText.prohibitedPaths" type="textarea" :rows="3"
                      placeholder="deploy/production/**&#10;security/keys/**" />
                    <small>只按真实改动路径和已审核的“必须执行”知识判断，不解析自然语言。</small>
                  </el-form-item>
                  <el-form-item label="知识同步（CI）">
                    <el-switch v-model="form.obligations.knowledgeUpdateRequired"
                      active-text="命中代码变化时，要求先发布更高修订的当前知识" />
                  </el-form-item>
                </div>
              </div>
            </section>

            <section class="form-section">
              <div class="section-marker"><span>04</span><small>负责</small></div>
              <div class="section-body owner-row">
                <div class="section-heading"><h3>谁负责确认它仍然有效</h3><p>必须执行的知识发布前需要负责人。</p></div>
                <el-form-item label="负责人">
                  <div class="owner-control">
                    <KnowledgeAccountSelect
                      v-model="form.ownerAccountId"
                      :members="members"
                      :loading="membersLoading"
                      placeholder="选择负责维护这条知识的成员"
                    />
                    <el-button :disabled="!auth.account" @click="useCurrentAccount">设为我</el-button>
                  </div>
                </el-form-item>
                <el-alert v-if="requiredIncomplete" type="warning" :closable="false"
                  title="当前草稿可以保存，但发布前必须补全负责人和至少一种适用范围。" />
              </div>
            </section>

            <section class="form-section">
              <div class="section-marker"><span>05</span><small>证据</small></div>
              <div class="section-body">
                <div class="section-heading"><h3>把知识绑定到当前代码</h3><p>代码引用用于验证来源版本，不等同于适用范围。</p></div>
                <KnowledgeCodeReferenceSelector v-model="codeReferences" :repository-id="repositoryId"
                  @open-code="emit('openCode', $event)" />
                <el-form-item label="图片与附件" class="attachment-field">
                  <div class="upload-area">
                    <label class="upload-button">
                      <el-icon><Upload /></el-icon>{{ uploading ? '上传中…' : '选择文件' }}
                      <input type="file" multiple :disabled="uploading" @change="choose" />
                    </label>
                    <span>图片最大 10 MiB；文档最大 50 MiB；每个修订最多 20 个。</span>
                  </div>
                  <KnowledgeAttachmentList :items="items" :repository-id="repositoryId" removable
                    @remove="remove" @insert="insert" />
                </el-form-item>
              </div>
            </section>
          </div>
        </el-collapse-item>
      </el-collapse>

      <el-alert type="info" :closable="false"
        title="保存会生成草稿和未评审修订；评审、来源验证和发布仍是三个独立动作。" />
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="busy"
        :disabled="uploading || referenceHasObligations || !form.title.trim() || !form.content.trim()"
        @click="save">{{ card ? '保存新修订' : '创建草稿' }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.editor-thesis { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 16px; margin: -8px 0 18px; padding: 12px 14px; color: #25313c; border-left: 4px solid #2f6f94; background: #f2f7fa; }
.editor-thesis > span { color: #2f6f94; font: 700 13px/1.2 Consolas, monospace; letter-spacing: .1em; }
.editor-thesis p { margin: 0; font-size: 15px; }
.editor-thesis b { padding: 4px 9px; color: #50606d; border: 1px solid #aebdc8; border-radius: 999px; font-size: 14px; }
.editor-thesis b[data-enforcement='ADVISORY'] { color: #8a5a1d; border-color: #d5a258; background: #fff8e9; }
.editor-thesis b[data-enforcement='REQUIRED'] { color: #983d32; border-color: #d78a7f; background: #fff2f0; }
.knowledge-card-form { max-height: calc(94vh - 176px); padding-right: 8px; overflow: auto; overscroll-behavior: contain; }
.form-section { display: grid; grid-template-columns: 58px minmax(0, 1fr); }
.section-marker { position: relative; display: flex; align-items: center; flex-direction: column; gap: 2px; color: #2f6f94; }
.section-marker::after { position: absolute; top: 42px; bottom: 0; width: 1px; background: #cbd9e2; content: ''; }
.section-marker span { font: 700 14px/1 Consolas, monospace; }
.section-marker small { color: #768794; font-size: 13px; letter-spacing: .12em; }
.section-body { min-width: 0; padding: 0 0 20px 12px; }
.form-grid { display: grid; gap: 14px; }
.title-grid { grid-template-columns: minmax(0, 2fr) minmax(220px, 1fr); }
.policy-grid { grid-template-columns: minmax(180px, .8fr) minmax(230px, 1fr) minmax(200px, 1fr); }
.three-columns { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.cross-scope-grid { margin-top: 14px; padding-top: 14px; border-top: 1px dashed #d4e0e7; }
.ci-policy-grid { grid-template-columns: minmax(0, 1.4fr) minmax(260px, 1fr); margin-top: 14px; }
.full-width { width: 100%; }
.enforcement-option { display: grid; line-height: 1.35; }
.enforcement-option span { color: var(--el-text-color-secondary); font-size: 13px; }
.engineering-collapse { margin: 2px 0 16px 58px; border-top-color: #cbd9e2; border-bottom-color: #cbd9e2; }
.collapse-title { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 12px; width: 100%; padding-right: 12px; }
.collapse-title > span { color: #2f6f94; font: 700 13px/1 Consolas, monospace; }
.collapse-title > div { display: grid; line-height: 1.3; }
.collapse-title small { color: var(--el-text-color-secondary); font-weight: 400; }
.collapse-title em { color: #667784; font-size: 14px; font-style: normal; }
.engineering-spine { padding-top: 18px; }
.engineering-spine .form-section:last-child .section-marker::after { display: none; }
.section-heading { display: flex; align-items: baseline; gap: 10px; margin-bottom: 12px; }
.section-heading h3 { margin: 0; color: #25313c; font-size: 15px; }
.section-heading p { margin: 0; color: #768794; font-size: 14px; }
.owner-row { display: grid; grid-template-columns: minmax(0, 1fr) minmax(300px, .75fr); column-gap: 20px; }
.owner-row .section-heading { display: block; }
.owner-row .section-heading p { margin-top: 4px; }
.owner-row .el-alert { grid-column: 1 / -1; }
.owner-control { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; width: 100%; }
.field-error { display: block; margin-top: 4px; color: var(--el-color-danger); }
.attachment-field { margin-top: 20px; }
.attachment-field :deep(.el-form-item__content) { display: grid; gap: 10px; }
.upload-area { display: flex; align-items: center; gap: 12px; }
.upload-area > span { color: var(--el-text-color-secondary); font-size: 14px; line-height: 1.5; }
.upload-button { display: inline-flex; flex: none; align-items: center; gap: 6px; padding: 7px 13px; color: #2f6f94; border: 1px solid #2f6f94; border-radius: 6px; cursor: pointer; }
.upload-button input { display: none; }
@media (max-width: 760px) {
  .editor-thesis { grid-template-columns: 1fr auto; }
  .editor-thesis p { grid-column: 1 / -1; grid-row: 2; }
  .form-section { grid-template-columns: 38px minmax(0, 1fr); }
  .engineering-collapse { margin-left: 38px; }
  .title-grid, .policy-grid, .three-columns, .ci-policy-grid, .owner-row { grid-template-columns: 1fr; }
  .owner-control { grid-template-columns: 1fr; }
  .owner-row .el-alert { grid-column: auto; }
  .section-heading { display: block; }
  .section-heading p { margin-top: 4px; }
  .upload-area { align-items: flex-start; flex-direction: column; }
}
</style>
