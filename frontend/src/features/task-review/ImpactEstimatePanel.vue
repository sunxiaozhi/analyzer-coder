<script setup lang="ts">
import { computed, reactive, shallowRef, watch } from 'vue';
import { ElMessage } from 'element-plus';
import {
  AlertTriangle,
  ArrowRight,
  BrainCircuit,
  Check,
  CheckCircle2,
  Clipboard,
  FileCode2,
  GitCommitHorizontal,
  Network,
  SearchCheck,
  ShieldQuestion,
  TestTube2,
  X,
} from 'lucide-vue-next';
import { useRoute, useRouter } from 'vue-router';
import {
  createChangeAnalysis,
  type ChangeAnalysisCandidateState,
  type ChangeCandidateEvidence,
  type ChangeDependencyImpact,
  type ChangeImpactAnalysis,
  type ChangeTestSuggestion,
} from '@/api/changeAnalysis';
import { intelligenceApi, type AskModel } from '@/api/intelligence';
import { useRepositoryStore } from '@/stores/repositoryStore';

const route = useRoute();
const router = useRouter();
const repositories = useRepositoryStore();
const task = shallowRef('');
const loading = shallowRef(false);
const modelsLoading = shallowRef(false);
const askModels = shallowRef<AskModel[]>([]);
const selectedModelId = shallowRef<string | null>(null);
const analysis = shallowRef<ChangeImpactAnalysis | null>(null);
const candidateStates = reactive<Record<string, ChangeAnalysisCandidateState>>({});

const repository = computed(() => repositories.selectedRepository);
const taskValue = computed(() => task.value.trim());
const confirmedCandidates = computed(() =>
  analysis.value?.candidates.filter(item => candidateState(item) === 'CONFIRMED') ?? [],
);
const excludedCount = computed(() =>
  analysis.value?.candidates.filter(item => candidateState(item) === 'EXCLUDED').length ?? 0,
);
const reviewedCount = computed(() => confirmedCandidates.value.length + excludedCount.value);
const coverageTone = computed(() => analysis.value?.evidenceCoverage.level.toLowerCase() ?? 'low');
const hasBlockingUnknowns = computed(() =>
  analysis.value?.unknowns.some(item => item.severity === 'HIGH') ?? false,
);
const selectedModel = computed(() =>
  askModels.value.find(item => item.id === selectedModelId.value) ?? null,
);
const parserLabel = computed(() => {
  if (!analysis.value) return '';
  return analysis.value.intent.parserMode === 'MODEL'
    ? `模型解析 · ${analysis.value.intent.provider ?? '已配置模型'}`
    : '规则解析 · 已自动降级';
});

let modelContextVersion = 0;

async function loadModels(repositoryId: string | null) {
  const version = ++modelContextVersion;
  askModels.value = [];
  selectedModelId.value = null;
  if (!repositoryId) return;
  modelsLoading.value = true;
  try {
    const result = await intelligenceApi.askModels(repositoryId);
    if (version !== modelContextVersion || repositoryId !== repositories.selectedRepositoryId) return;
    askModels.value = result;
    selectedModelId.value = result.find(item => item.available)?.id ?? null;
  } catch (error) {
    if (version === modelContextVersion) {
      ElMessage.warning(error instanceof Error ? error.message : '无法加载语义模型，将使用规则解析');
    }
  } finally {
    if (version === modelContextVersion) modelsLoading.value = false;
  }
}

function candidateKey(item: ChangeCandidateEvidence) {
  return item.chunkId ?? `${item.filePath}:${item.startLine ?? 0}`;
}

function candidateState(item: ChangeCandidateEvidence): ChangeAnalysisCandidateState {
  return candidateStates[candidateKey(item)] ?? 'PENDING';
}

function setCandidateState(item: ChangeCandidateEvidence, state: ChangeAnalysisCandidateState) {
  candidateStates[candidateKey(item)] = candidateState(item) === state ? 'PENDING' : state;
}

async function analyze() {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId) {
    ElMessage.warning('请先选择仓库');
    return;
  }
  if (taskValue.value.length < 2) {
    ElMessage.warning('请描述要修改的行为或问题');
    return;
  }
  loading.value = true;
  try {
    analysis.value = await createChangeAnalysis(repositoryId, taskValue.value, selectedModelId.value);
    Object.keys(candidateStates).forEach(key => delete candidateStates[key]);
    if (analysis.value.intent.parserMode === 'RULES' && selectedModelId.value) {
      ElMessage.warning('所选模型未完成有效解析，本次已自动降级为规则解析');
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '变更影响分析失败');
  } finally {
    loading.value = false;
  }
}

function openEvidence(item: ChangeCandidateEvidence | ChangeTestSuggestion) {
  if (!item.filePath) return;
  void router.push({ name: 'search', query: {
    path: item.filePath,
    startLine: item.startLine ?? undefined,
    endLine: item.endLine ?? item.startLine ?? undefined,
  }});
}

function openModule(moduleId: string) {
  void router.push({ name: 'search', query: { q: moduleId } });
}

function openDependencySample(edge: ChangeDependencyImpact) {
  const source = edge.samples[0]?.filePath;
  if (source) void router.push({ name: 'search', query: { path: source } });
}

function continueAsk() {
  if (!analysis.value) return;
  const confirmed = confirmedCandidates.value.map(item => item.filePath);
  const evidenceText = confirmed.length
    ? `\n已人工确认相关文件：${confirmed.join('、')}`
    : '\n候选文件尚未人工确认，请先核验引用。';
  void router.push({ name: 'ask', query: {
    q: `为以下改动制定可执行的修改与测试计划：${analysis.value.task}${evidenceText}`,
  }});
}

async function copyScope() {
  if (!analysis.value) return;
  const lines = [
    `# 变更影响核验`,
    ``,
    `- 任务：${analysis.value.task}`,
    `- Commit：${analysis.value.commitSha ?? 'unknown'}`,
    `- Snapshot：${analysis.value.snapshotId}`,
    `- 证据覆盖等级：${analysis.value.evidenceCoverage.label}`,
    ``,
    `## 已确认相关`,
    ...confirmedCandidates.value.map(item =>
      `- ${item.filePath}${item.startLine ? `:${item.startLine}` : ''} · Snapshot ${item.snapshotId} · SHA-256 ${item.contentHash}`,
    ),
    ``,
    `## 已排除`,
    ...(analysis.value.candidates
      .filter(item => candidateState(item) === 'EXCLUDED')
      .map(item => `- ${item.filePath}`)),
    ``,
    `## 未知项`,
    ...analysis.value.unknowns.map(item => `- [${item.severity}] ${item.detail}`),
  ];
  try {
    await navigator.clipboard.writeText(lines.join('\n'));
    ElMessage.success('核验范围已复制');
  } catch {
    ElMessage.error('复制失败，请检查浏览器剪贴板权限');
  }
}

watch(
  () => route.query.task,
  (value) => {
    if (typeof value === 'string' && value.trim()) task.value = value.trim();
  },
  { immediate: true },
);
watch(
  () => repositories.selectedRepositoryId,
  (repositoryId) => {
    analysis.value = null;
    void loadModels(repositoryId);
  },
  { immediate: true },
);
</script>

<template>
  <section class="impact-page">
    <header class="impact-header">
      <div>
        <span class="eyebrow">需求影响预估</span>
        <h1>从任务描述形成调查线索</h1>
        <p>未读取真实 Git Diff。结果来自当前仓库快照的检索与一跳模块依赖，只用于提前调查。</p>
      </div>
      <div v-if="repository" class="repository-stamp">
        <GitCommitHorizontal :size="17" />
        <span><small>{{ repository.name }}</small><strong>{{ repository.commit?.slice(0, 10) ?? '未发布' }}</strong></span>
        <em v-if="repository.dirty">有未发布变更</em>
      </div>
    </header>

    <section class="analysis-input">
      <el-input
        v-model="task"
        type="textarea"
        :rows="3"
        maxlength="1000"
        resize="none"
        placeholder="例如：为登录接口增加失败次数限制，并确认对认证流程、配置和测试的影响"
        aria-label="改动目标"
        @keydown.ctrl.enter="analyze"
      />
      <div class="input-actions">
        <div class="parser-control">
          <BrainCircuit :size="15" />
          <span>语义解析</span>
          <el-select
            v-model="selectedModelId"
            :loading="modelsLoading"
            clearable
            placeholder="规则解析"
            aria-label="语义解析模型"
          >
            <el-option
              v-for="item in askModels"
              :key="item.id"
              :label="`${item.name} · ${item.model}`"
              :value="item.id"
              :disabled="!item.available"
            >
              <span>{{ item.name }} · {{ item.model }}</span>
              <small>{{ item.available ? '可用' : item.availability }}</small>
            </el-option>
          </el-select>
          <small v-if="!modelsLoading && !selectedModel">未选择可用模型时，自动使用规则解析</small>
        </div>
        <el-button type="primary" :loading="loading" :disabled="taskValue.length < 2 || !repository" @click="analyze">
          <SearchCheck :size="15" />分析影响
        </el-button>
      </div>
    </section>

    <div v-if="analysis" class="analysis-result">
      <section class="result-main">
        <header class="result-verdict" :data-tone="coverageTone">
          <span class="verdict-icon">
            <CheckCircle2 v-if="analysis.evidenceCoverage.level === 'HIGH'" :size="20" />
            <AlertTriangle v-else :size="20" />
          </span>
          <div><span class="eyebrow">证据覆盖等级</span><h2>{{ analysis.evidenceCoverage.label }}</h2><p>{{ analysis.evidenceCoverage.detail }}</p></div>
          <div class="review-progress">
            <strong>{{ reviewedCount }}/{{ analysis.candidates.length }}</strong>
            <span>候选已核验</span>
          </div>
        </header>

        <section class="intent-trace" :data-mode="analysis.intent.parserMode">
          <header>
            <span class="trace-icon"><BrainCircuit :size="16" /></span>
            <div>
              <span class="eyebrow">任务如何被理解</span>
              <strong>{{ parserLabel }}</strong>
            </div>
            <em>{{ analysis.intent.changeType }}</em>
          </header>
          <div class="intent-body">
            <div class="intent-goal">
              <small>可验证目标</small>
              <p>{{ analysis.intent.goal }}</p>
              <div v-if="analysis.intent.entities.length || analysis.intent.candidateSymbols.length" class="intent-tags">
                <span v-for="item in [...new Set([...analysis.intent.entities, ...analysis.intent.candidateSymbols])]" :key="item">{{ item }}</span>
              </div>
            </div>
            <div class="impact-expectations">
              <small>模型建议核查</small>
              <p v-for="item in analysis.intent.expectedImpacts" :key="item">{{ item }}</p>
              <p v-if="!analysis.intent.expectedImpacts.length">未识别出明确影响面</p>
            </div>
            <div class="query-ledger">
              <small>实际执行的检索</small>
              <ol>
                <li v-for="query in analysis.retrievalQueries" :key="`${query.purpose}:${query.query}`">
                  <span>{{ query.purpose }}</span>
                  <code>{{ query.query }}</code>
                  <strong>{{ query.hitCount }}</strong>
                </li>
              </ol>
            </div>
          </div>
          <footer v-if="analysis.intent.unknowns.length">
            <span>语义待确认</span>
            <p>{{ analysis.intent.unknowns.join('；') }}</p>
          </footer>
        </section>

        <section class="candidate-section">
          <header><div><span class="eyebrow">直接证据</span><h2>候选代码与符号</h2></div><span>逐条确认，排除关键词误报</span></header>
          <div v-if="analysis.candidates.length" class="candidate-list">
            <article v-for="(item, index) in analysis.candidates" :key="candidateKey(item)" :data-state="candidateState(item)">
              <button type="button" class="candidate-source" @click="openEvidence(item)">
                <span class="file-icon"><FileCode2 :size="15" /></span>
                <span class="candidate-copy">
                  <small>{{ item.moduleId ?? '未映射模块' }} · {{ item.symbolKind ?? 'FILE' }}</small>
                  <strong>{{ item.symbolName || item.filePath.split('/').pop() }}</strong>
                  <code>{{ item.filePath }}{{ item.startLine ? `:${item.startLine}` : '' }}</code>
                  <p>{{ item.excerpt }}</p>
                  <small class="provenance">Snapshot {{ item.snapshotId.slice(0, 8) }} · SHA {{ item.contentHash.slice(0, 12) }}</small>
                  <small v-if="item.matchedQueries.length > 1">{{ item.matchedQueries.length }} 个检索角度共同命中</small>
                </span>
                <span class="score">排序 {{ index + 1 }}</span>
              </button>
              <div class="candidate-review">
                <button type="button" :class="{ active: candidateState(item) === 'CONFIRMED' }" @click="setCandidateState(item, 'CONFIRMED')"><Check :size="12" />确认相关</button>
                <button type="button" :class="{ active: candidateState(item) === 'EXCLUDED' }" @click="setCandidateState(item, 'EXCLUDED')"><X :size="12" />排除</button>
              </div>
            </article>
          </div>
          <el-empty v-else :image-size="54" description="没有找到直接代码证据，请补充接口名、类名或文件路径" />
        </section>

        <section class="scope-section">
          <header><div><span class="eyebrow">一跳范围</span><h2>模块与依赖</h2></div><span>{{ analysis.modules.length }} 模块 · {{ analysis.dependencies.length }} 关系</span></header>
          <div class="scope-grid">
            <div class="module-list">
              <button v-for="module in analysis.modules" :key="module.moduleId" type="button" @click="openModule(module.moduleId)">
                <span :data-role="module.role">{{ module.role === 'DIRECT' ? '直接' : '相关' }}</span>
                <strong>{{ module.moduleId }}</strong>
                <small>入 {{ module.incomingWeight }} · 出 {{ module.outgoingWeight }}</small>
              </button>
              <p v-if="!analysis.modules.length">候选代码尚未映射到模块。</p>
            </div>
            <div class="dependency-list">
              <button v-for="edge in analysis.dependencies" :key="`${edge.source}:${edge.target}:${edge.relation}`" type="button" @click="openDependencySample(edge)">
                <Network :size="14" />
                <span>
                  <strong>{{ edge.source }} → {{ edge.target }}</strong>
                  <small>{{ edge.relation }} · {{ edge.weight }} 条关系 · {{ edge.samples.length }} 条可核验样例</small>
                  <small v-if="edge.samples[0]">Snapshot {{ edge.samples[0].snapshotId.slice(0, 8) }} · SHA {{ edge.samples[0].contentHash.slice(0, 12) }}</small>
                </span>
                <ArrowRight :size="12" />
              </button>
              <p v-if="!analysis.dependencies.length">没有形成可追溯的一跳模块关系。</p>
            </div>
          </div>
        </section>
      </section>

      <aside class="review-ledger">
        <section class="ledger-block unknowns" :data-blocking="hasBlockingUnknowns">
          <header><ShieldQuestion :size="16" /><div><span>未知项</span><strong>{{ analysis.unknowns.length }}</strong></div></header>
          <article v-for="item in analysis.unknowns" :key="item.code" :data-severity="item.severity">
            <span>{{ item.severity }}</span><p>{{ item.detail }}</p>
          </article>
        </section>

        <section class="ledger-block risks">
          <header><AlertTriangle :size="16" /><div><span>关联风险</span><strong>{{ analysis.risks.length }}</strong></div></header>
          <article v-for="risk in analysis.risks" :key="risk.id">
            <span>{{ risk.severity }}</span><div><strong>{{ risk.title }}</strong><p>{{ risk.detail }}</p></div>
          </article>
          <p v-if="!analysis.risks.length" class="ledger-empty">当前候选范围没有匹配到已知架构风险。</p>
        </section>

        <section class="ledger-block tests">
          <header><TestTube2 :size="16" /><div><span>测试核验</span><strong>{{ analysis.tests.filter(item => item.existing).length }}</strong></div></header>
          <button v-for="item in analysis.tests" :key="item.filePath ?? item.reason" type="button" :disabled="!item.filePath" @click="openEvidence(item)">
            <span :data-existing="item.existing">{{ item.existing ? '现有' : '缺口' }}</span>
            <div>
              <strong>{{ item.filePath ?? '未找到直接相关测试' }}</strong><p>{{ item.reason }}</p>
              <small v-if="item.snapshotId && item.contentHash">Snapshot {{ item.snapshotId.slice(0, 8) }} · SHA {{ item.contentHash.slice(0, 12) }}</small>
            </div>
            <ArrowRight v-if="item.filePath" :size="12" />
          </button>
        </section>

        <section class="next-step">
          <span class="eyebrow">人工核验后</span>
          <h2>形成修改与测试计划</h2>
          <p v-if="!reviewedCount">先确认或排除候选代码，避免把检索结果直接当成修改范围。</p>
          <p v-else>已确认 {{ confirmedCandidates.length }} 项，排除 {{ excludedCount }} 项。继续问答时会带入确认后的文件清单。</p>
          <button type="button" class="primary" :disabled="!reviewedCount" @click="continueAsk">继续制定计划 <ArrowRight :size="13" /></button>
          <button type="button" :disabled="!reviewedCount" @click="copyScope"><Clipboard :size="13" />复制核验范围</button>
        </section>

        <footer class="snapshot-note">Analysis {{ analysis.analysisId.slice(0, 8) }} · Snapshot {{ analysis.snapshotId.slice(0, 8) }}</footer>
      </aside>
    </div>

    <section v-else-if="!loading" class="analysis-empty">
      <span><SearchCheck :size="24" /></span>
      <div><h2>用一个具体改动验证项目知识</h2><p>好的任务包含行为、入口或约束，例如“修改登录失败策略”，而不是泛泛地问“介绍项目”。</p></div>
    </section>
  </section>
</template>

<style scoped>
.impact-page { --blue: var(--el-color-primary, #0066cc); --text: var(--el-text-color-primary, #1d1d1f); --muted: var(--app-text-muted); --border: var(--el-border-color, #dedee3); display: grid; height: 100%; min-height: 0; align-content: start; gap: 12px; padding: 0 4px 28px 0; overflow-x: hidden; overflow-y: auto; overscroll-behavior: contain; scrollbar-gutter: stable; scroll-padding-bottom: 28px; color: var(--text); }
.eyebrow { color: var(--muted); font-size: 11px; font-weight: 650; letter-spacing: .04em; }
.impact-header { display: flex; min-height: 102px; align-items: center; justify-content: space-between; gap: 24px; padding: 17px 20px; border: 1px solid var(--border); border-radius: 7px; background: #fff; }
.impact-header h1 { margin: 6px 0 4px; font-size: 25px; font-weight: 650; }
.impact-header p { margin: 0; color: var(--muted); font-size: 12px; }
.repository-stamp { display: grid; grid-template-columns: 22px minmax(110px, auto) auto; align-items: center; gap: 8px; padding: 9px 10px; color: #616168; border: 1px solid var(--border); border-radius: 6px; background: #fafafa; }
.repository-stamp > span { display: grid; gap: 2px; }
.repository-stamp small { color: var(--app-text-muted); font-size: 11px; }
.repository-stamp strong { font: 600 11px "SFMono-Regular", Consolas, monospace; }
.repository-stamp em { padding: 3px 5px; color: #a96010; border-radius: 3px; background: #fff0dc; font-size: 11px; font-style: normal; }
.analysis-input { padding: 15px; border: 1px solid var(--border); border-radius: 7px; background: #fff; }
.analysis-input :deep(.el-textarea__inner) { min-height: 74px !important; padding: 11px 12px; border-radius: 5px; background: #fafbfc; box-shadow: 0 0 0 1px #d8dce2 inset; font-size: 12px; line-height: 1.6; }
.analysis-input :deep(.el-textarea__inner:focus) { background: #fff; box-shadow: 0 0 0 1px var(--blue) inset, 0 0 0 3px rgb(0 102 204 / 10%); }
.input-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 9px; color: var(--app-text-muted); font-size: 11px; }
.input-actions :deep(.el-button span) { gap: 6px; }
.parser-control { display: flex; min-width: 0; align-items: center; gap: 7px; color: #69737c; }
.parser-control > span { color: #555b62; font-size: 11px; font-weight: 600; white-space: nowrap; }
.parser-control > small { color: var(--app-text-muted); font-size: 11px; }
.parser-control :deep(.el-select) { width: 250px; }
.parser-control :deep(.el-select__wrapper) { min-height: 30px; border-radius: 4px; }
.parser-control :deep(.el-select-dropdown__item) { display: flex; justify-content: space-between; gap: 10px; }
.parser-control :deep(.el-select-dropdown__item small) { color: var(--app-text-muted); }
.analysis-result { display: grid; grid-template-columns: minmax(0, 1fr) 340px; align-items: start; overflow: hidden; border: 1px solid var(--border); border-radius: 7px; background: #fff; }
.result-main { min-width: 0; border-right: 1px solid #e8e8ec; }
.result-verdict { display: grid; grid-template-columns: 42px minmax(0, 1fr) auto; align-items: center; gap: 12px; padding: 15px 17px; border-bottom: 1px solid #e8e8ec; background: #f8fbfe; }
.verdict-icon { display: grid; width: 40px; height: 40px; place-items: center; color: #b56715; border-radius: 7px; background: #fff0dc; }
.result-verdict[data-tone='high'] .verdict-icon { color: #16855b; background: #eaf6f0; }
.result-verdict h2 { margin: 4px 0 3px; font-size: 17px; font-weight: 650; }
.result-verdict p { margin: 0; color: var(--muted); font-size: 12px; }
.review-progress { display: grid; justify-items: end; gap: 2px; }
.review-progress strong { color: var(--blue); font-size: 19px; }
.review-progress span { color: var(--app-text-muted); font-size: 11px; }
.intent-trace { border-bottom: 1px solid #e8e8ec; background: #fff; }
.intent-trace > header { display: grid; grid-template-columns: 34px minmax(0, 1fr) auto; align-items: center; gap: 9px; padding: 11px 16px; border-bottom: 1px solid #ececef; }
.trace-icon { display: grid; width: 32px; height: 32px; place-items: center; color: var(--blue); border-radius: 6px; background: #eaf3fd; }
.intent-trace[data-mode='RULES'] .trace-icon { color: #9a611d; background: #f8ead6; }
.intent-trace > header > div { display: grid; gap: 3px; }
.intent-trace > header strong { color: #3e444a; font-size: 11px; font-weight: 650; }
.intent-trace > header em { padding: 4px 6px; color: #4d6476; border: 1px solid #d9e5ef; border-radius: 3px; background: #f3f8fc; font: 600 11px "SFMono-Regular", Consolas, monospace; font-style: normal; }
.intent-body { display: grid; grid-template-columns: minmax(0, 1fr) minmax(150px, .65fr); }
.intent-goal, .impact-expectations, .query-ledger { padding: 11px 16px; }
.intent-goal { border-right: 1px solid #ececef; }
.intent-body small { color: var(--app-text-muted); font-size: 11px; font-weight: 600; letter-spacing: .03em; }
.intent-body p { margin: 5px 0 0; color: #515158; font-size: 12px; line-height: 1.55; }
.intent-tags { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 9px; }
.intent-tags span { padding: 3px 6px; color: #45657d; border-radius: 3px; background: #edf5fb; font: 11px "SFMono-Regular", Consolas, monospace; }
.impact-expectations p { position: relative; padding-left: 9px; }
.impact-expectations p::before { position: absolute; top: 6px; left: 0; width: 3px; height: 3px; border-radius: 50%; background: #7f9bb0; content: ''; }
.query-ledger { grid-column: 1 / -1; padding-top: 9px; border-top: 1px solid #ececef; background: #fafbfc; }
.query-ledger ol { display: grid; gap: 1px; margin: 6px 0 0; padding: 0; list-style: none; }
.query-ledger li { display: grid; grid-template-columns: 58px minmax(0, 1fr) 26px; align-items: center; gap: 7px; min-height: 25px; }
.query-ledger li span { color: #6d7881; font-size: 11px; }
.query-ledger code { overflow: hidden; color: #46525c; font: 11px "SFMono-Regular", Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.query-ledger strong { color: var(--blue); font: 600 11px "SFMono-Regular", Consolas, monospace; text-align: right; }
.intent-trace > footer { display: grid; grid-template-columns: 64px minmax(0, 1fr); gap: 8px; padding: 8px 16px; border-top: 1px solid #f0e4d5; background: #fffaf4; }
.intent-trace > footer span { color: #96601f; font-size: 11px; font-weight: 650; }
.intent-trace > footer p { margin: 0; color: #6d5c49; font-size: 12px; line-height: 1.5; }
.candidate-section > header, .scope-section > header { display: flex; align-items: end; justify-content: space-between; gap: 12px; padding: 13px 16px; border-bottom: 1px solid #ececef; }
.candidate-section header h2, .scope-section header h2 { margin: 4px 0 0; font-size: 13px; }
.candidate-section header > span, .scope-section header > span { color: var(--app-text-muted); font-size: 11px; }
.candidate-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); background: #ececef; gap: 1px; }
.candidate-list article { min-width: 0; background: #fff; box-shadow: inset 3px 0 transparent; }
.candidate-list article[data-state='CONFIRMED'] { box-shadow: inset 3px 0 #16855b; }
.candidate-list article[data-state='EXCLUDED'] { opacity: .6; box-shadow: inset 3px 0 #8c8c93; }
.candidate-source { display: grid; grid-template-columns: 30px minmax(0, 1fr) 38px; gap: 8px; width: 100%; min-height: 132px; padding: 11px; text-align: left; border: 0; background: transparent; }
.candidate-source:hover, .candidate-source:focus-visible { outline: none; background: #f5f9fd; }
.file-icon { display: grid; width: 28px; height: 28px; place-items: center; color: var(--blue); border-radius: 5px; background: #eaf3fd; }
.candidate-copy { display: grid; min-width: 0; align-content: start; gap: 4px; }
.candidate-copy small { color: var(--app-text-muted); font-size: 11px; }
.candidate-copy strong { overflow: hidden; color: #323239; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.candidate-copy code { overflow: hidden; color: #4f6474; font: 11px "SFMono-Regular", Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.candidate-copy p { display: -webkit-box; margin: 2px 0 0; overflow: hidden; color: var(--app-text-muted); font-size: 12px; line-height: 1.5; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.score { color: var(--app-text-muted); font: 11px "SFMono-Regular", Consolas, monospace; text-align: right; }
.candidate-review { display: grid; grid-template-columns: 1fr 1fr; border-top: 1px solid #ececef; }
.candidate-review button { display: flex; min-height: 30px; align-items: center; justify-content: center; gap: 4px; color: #696970; border: 0; border-right: 1px solid #ececef; background: #fafafa; font-size: 11px; }
.candidate-review button:last-child { border-right: 0; }
.candidate-review button:hover, .candidate-review button:focus-visible { color: var(--blue); outline: none; background: #f0f7fd; }
.candidate-review button:first-child.active { color: #16855b; background: #eaf6f0; }
.candidate-review button:last-child.active { color: #66666d; background: #ededf0; }
.scope-section { border-top: 1px solid #e8e8ec; }
.scope-grid { display: grid; grid-template-columns: minmax(220px, .75fr) minmax(0, 1.25fr); min-height: 190px; }
.module-list { border-right: 1px solid #ececef; }
.module-list button { display: grid; grid-template-columns: 38px minmax(0, 1fr) auto; align-items: center; gap: 7px; width: 100%; min-height: 40px; padding: 7px 10px; text-align: left; border: 0; border-bottom: 1px solid #f0f0f2; background: #fff; }
.module-list button:hover, .module-list button:focus-visible, .dependency-list button:hover, .dependency-list button:focus-visible { outline: none; background: #f3f8fc; }
.module-list button > span { padding: 3px; color: #69737c; text-align: center; border-radius: 3px; background: #edf0f2; font-size: 11px; }
.module-list button > span[data-role='DIRECT'] { color: #0066cc; background: #eaf3fd; }
.module-list strong { overflow: hidden; font: 600 11px "SFMono-Regular", Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.module-list small { color: var(--app-text-muted); font-size: 11px; }
.module-list > p, .dependency-list > p { margin: 20px; color: var(--app-text-muted); font-size: 12px; }
.dependency-list button { display: grid; grid-template-columns: 22px minmax(0, 1fr) 14px; align-items: center; gap: 7px; width: 100%; min-height: 45px; padding: 7px 10px; color: #5d6a74; text-align: left; border: 0; border-bottom: 1px solid #f0f0f2; background: #fff; }
.dependency-list button > span { display: grid; min-width: 0; gap: 3px; }
.dependency-list strong { overflow: hidden; color: #3e4a53; font: 600 11px "SFMono-Regular", Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.dependency-list small { color: var(--app-text-muted); font-size: 11px; }
.review-ledger { position: sticky; top: 0; display: grid; min-width: 0; max-height: calc(100vh - 144px); max-height: calc(100dvh - 144px); align-self: start; overflow-y: auto; overscroll-behavior: contain; scrollbar-gutter: stable; background: #fafafa; }
.ledger-block { border-bottom: 1px solid #e4e4e8; }
.ledger-block > header { display: flex; min-height: 47px; align-items: center; gap: 8px; padding: 8px 12px; color: #5e6870; background: #f3f4f5; }
.ledger-block > header > div { display: flex; flex: 1; align-items: center; justify-content: space-between; }
.ledger-block > header span { font-size: 11px; font-weight: 650; }
.ledger-block > header strong { font-size: 13px; }
.ledger-block article { display: grid; grid-template-columns: 42px minmax(0, 1fr); gap: 7px; padding: 8px 11px; border-top: 1px solid #ededf0; background: #fff; }
.ledger-block article > span, .ledger-block button > span { align-self: start; padding: 3px; color: var(--app-text-muted); text-align: center; border-radius: 3px; background: #ededf0; font-size: 11px; }
.unknowns article[data-severity='HIGH'] > span { color: #a63535; background: #fbe5e5; }
.unknowns article[data-severity='MEDIUM'] > span { color: #96601f; background: #f8ead6; }
.ledger-block article p, .ledger-block button p { margin: 0; color: #61616a; font-size: 12px; line-height: 1.5; }
.risks article > div { display: grid; gap: 3px; }
.risks article strong { color: #44444a; font-size: 11px; }
.ledger-empty { margin: 0; padding: 12px; color: var(--app-text-muted); background: #fff; font-size: 11px; }
.ledger-block > button { display: grid; grid-template-columns: 38px minmax(0, 1fr) 12px; align-items: center; gap: 7px; width: 100%; padding: 8px 11px; text-align: left; border: 0; border-top: 1px solid #ededf0; background: #fff; }
.ledger-block > button:not(:disabled):hover, .ledger-block > button:not(:disabled):focus-visible { outline: none; background: #f3f8fc; }
.ledger-block > button:disabled { cursor: default; }
.ledger-block > button div { display: grid; min-width: 0; gap: 3px; }
.ledger-block > button strong { overflow: hidden; color: #44444a; font: 500 11px "SFMono-Regular", Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.ledger-block button > span[data-existing='true'] { color: #16855b; background: #eaf6f0; }
.next-step { display: grid; gap: 7px; padding: 14px 12px; border-bottom: 1px solid #e4e4e8; }
.next-step h2 { margin: 0; font-size: 13px; }
.next-step p { margin: 0 0 3px; color: var(--app-text-muted); font-size: 12px; line-height: 1.55; }
.next-step button { display: flex; min-height: 32px; align-items: center; justify-content: center; gap: 5px; color: #5d5d64; border: 1px solid #d4d4da; border-radius: 4px; background: #fff; font-size: 11px; }
.next-step button.primary { color: #fff; border-color: var(--blue); background: var(--blue); }
.next-step button:disabled { cursor: not-allowed; opacity: .45; }
.next-step button:not(:disabled):focus-visible { outline: 2px solid rgb(0 102 204 / 25%); outline-offset: 2px; }
.snapshot-note { padding: 9px 12px; color: var(--app-text-muted); font: 11px "SFMono-Regular", Consolas, monospace; }
.analysis-empty { display: flex; min-height: 180px; align-items: center; justify-content: center; gap: 13px; padding: 24px; border: 1px dashed #c9c9cf; border-radius: 7px; background: #fff; }
.analysis-empty > span { display: grid; width: 46px; height: 46px; place-items: center; color: var(--blue); border-radius: 8px; background: #eaf3fd; }
.analysis-empty h2 { margin: 0 0 5px; font-size: 15px; }
.analysis-empty p { margin: 0; color: var(--app-text-muted); font-size: 12px; }
@media (max-width: 1120px) { .analysis-result { grid-template-columns: 1fr; } .result-main { border-right: 0; } .review-ledger { position: static; grid-template-columns: repeat(2, 1fr); max-height: none; overflow: visible; } .next-step, .snapshot-note { grid-column: 1 / -1; } }
@media (max-width: 760px) { .impact-header { align-items: stretch; flex-direction: column; } .input-actions { align-items: stretch; flex-direction: column; } .parser-control { flex-wrap: wrap; } .parser-control :deep(.el-select) { width: min(100%, 280px); } .input-actions > .el-button { width: 100%; } .intent-body { grid-template-columns: 1fr; } .intent-goal { border-right: 0; border-bottom: 1px solid #ececef; } .query-ledger { grid-column: auto; } .candidate-list { grid-template-columns: 1fr; } .scope-grid, .review-ledger { grid-template-columns: 1fr; } .module-list { border-right: 0; border-bottom: 1px solid #ececef; } .result-verdict { grid-template-columns: 42px minmax(0, 1fr); } .review-progress { grid-column: 2; justify-items: start; } .next-step, .snapshot-note { grid-column: auto; } }
</style>
