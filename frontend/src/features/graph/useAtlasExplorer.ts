import { computed, onBeforeUnmount, onMounted, shallowRef, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { getCodeAtlas, atlasEdgeKey, type AtlasNode, type AtlasView, type AtlasExploreOptions } from '@/api/codeAtlas';
import { intelligenceApi, type GraphResult } from '@/api/intelligence';
import { useRepositoryStore } from '@/stores/repositoryStore';
import { useBranchContextStore } from '@/stores/branchContextStore';
import { useBranchReadScope } from '@/features/branches/useBranchReadScope';

export function useAtlasExplorer() {
  const repositories = useRepositoryStore(), branches = useBranchContextStore();
  const scope = useBranchReadScope(), route = useRoute(), router = useRouter();
  const data = shallowRef<AtlasView | null>(null), selectedId = shallowRef('');
  const query = shallowRef(''), module = shallowRef(''), loading = shallowRef(false), error = shallowRef('');
  const notice = shallowRef(''), depth = shallowRef(1), limit = shallowRef(240);
  const impact = shallowRef<GraphResult | null>(null), pathIndex = shallowRef(-1);
  const selected = computed(() => data.value?.nodes.find(n => n.id === selectedId.value) ?? null);
  let revision = 0;
  const expansionLimits = new Map<string, number>();
  function validate(result: { contentVersion: string }) {
    if (branches.context && result.contentVersion !== branches.context.contentVersion)
      throw new Error('图谱与当前分支内容版本不一致，请重新构建该分支图谱。');
    if (typeof route.query.contentVersion === 'string' && result.contentVersion !== route.query.contentVersion)
      throw new Error('目标文件内容版本已更新，请重新从联合检索打开。');
  }
  function merge(result: AtlasView) {
    if (!data.value) { data.value = result; return; }
    if (result.contentVersion !== data.value.contentVersion || result.repositoryId !== data.value.repositoryId)
      throw new Error('图谱内容版本已更新，请刷新后继续展开。');
    const nodes = new Map(data.value.nodes.map(n => [n.id, n]));
    result.nodes.forEach(n => { if (nodes.has(n.id) || nodes.size < 1200) nodes.set(n.id, n); });
    const edges = new Map(data.value.edges.map(e => [atlasEdgeKey(e), e]));
    result.edges.forEach(e => {
      if (!nodes.has(e.source) || !nodes.has(e.target)) return;
      const key = atlasEdgeKey(e), old = edges.get(key);
      edges.set(key, old ? { ...e, count: Math.max(old.count, e.count),
        sourceLines: [...new Set([...(old.sourceLines ?? []), ...(e.sourceLines ?? [])])].sort((a, b) => a - b),
        sourceLinesTruncated: old.sourceLinesTruncated || e.sourceLinesTruncated } : e);
    });
    data.value = { ...data.value, nodes: [...nodes.values()], edges: [...edges.values()] };
    notice.value = result.partial ? '本次展开仍有未加载关系；再次展开可加载更多，或选择边界节点继续查看。' : '';
    if (nodes.size >= 1200) notice.value = '当前画布已达 1200 个节点；可搜索目标符号，继续查看局部关系。';
  }
  async function load(more = false) {
    const id = repositories.selectedRepositoryId, ticket = ++revision;
    if (!more) { expansionLimits.clear(); data.value = null; selectedId.value = ''; impact.value = null; pathIndex.value = -1; limit.value = 240; }
    error.value = ''; notice.value = '';
    if (!id) { loading.value = false; return; }
    if (scope.blocked.value) { loading.value = branches.loading; if (!branches.loading) error.value = scope.reason.value; return; }
    if (typeof route.query.contentVersion === 'string' && branches.context && route.query.contentVersion !== branches.context.contentVersion) {
      loading.value = false; error.value = '目标文件与当前分支内容版本不一致，请重新从联合检索打开。'; return;
    }
    loading.value = true;
    try {
      const options = more ? { limit: limit.value } : undefined;
      let result = options
        ? await getCodeAtlas(id, module.value, query.value.trim(), branches.context?.contextId, options)
        : await getCodeAtlas(id, module.value, query.value.trim(), branches.context?.contextId);
      const targetPath = typeof route.query.path === 'string' ? route.query.path : '';
      if (targetPath && query.value === route.query.symbol && !result.nodes.some(n => n.filePath === targetPath)) {
        if (ticket !== revision) return;
        result = await getCodeAtlas(id, module.value, targetPath, branches.context?.contextId);
      }
      if (ticket !== revision || id !== repositories.selectedRepositoryId) return;
      validate(result);
      if (more) { merge(result); data.value = { ...data.value!, totalNodes: result.totalNodes, totalEdges: result.totalEdges, partial: result.partial }; }
      else data.value = result;
      const target = result.nodes.find(n => n.filePath === targetPath && n.label === route.query.symbol)
        ?? result.nodes.find(n => n.filePath === targetPath);
      if (target) selectedId.value = target.id;
      else if (query.value && !more) selectedId.value = result.nodes.find(n => (n.label + ' ' + n.qualifiedName + ' ' + n.filePath).toLowerCase().includes(query.value.toLowerCase()))?.id ?? '';
    } catch (e) { if (ticket === revision) error.value = e instanceof Error ? e.message : '图谱加载失败'; }
    finally { if (ticket === revision) loading.value = false; }
  }
  async function expand(direction: AtlasExploreOptions['direction'] = 'both') {
    if (!selected.value || !data.value || loading.value || scope.blocked.value) return;
    if (selected.value.kind === 'MODULE') { module.value = selected.value.module; query.value = ''; await load(); return; }
    const current = data.value, focusId = selected.value.id, ticket = ++revision;
    const expansionKey = JSON.stringify([focusId, direction, depth.value]);
    const requestLimit = Math.min(1200, (expansionLimits.get(expansionKey) ?? 0) + limit.value);
    loading.value = true; error.value = ''; notice.value = '';
    try {
      const result = await getCodeAtlas(current.repositoryId, '', '', branches.context?.contextId,
        { focusId, direction, depth: depth.value, limit: requestLimit });
      if (ticket !== revision) return;
      validate(result); merge(result); expansionLimits.set(expansionKey, requestLimit);
    } catch (e) { if (ticket === revision) error.value = e instanceof Error ? e.message : '展开失败'; }
    finally { if (ticket === revision) loading.value = false; }
  }
  async function traceImpact() {
    if (!selected.value || !data.value || loading.value || scope.blocked.value) return;
    const current = data.value, node = selected.value, ticket = ++revision;
    loading.value = true; error.value = ''; impact.value = null; pathIndex.value = -1;
    try {
      const result = await intelligenceApi.graph(current.repositoryId, node.qualifiedName || node.label, depth.value, 'BOTH', branches.context?.contextId);
      if (ticket !== revision) return;
      validate(result);
      const byId = new Map(current.nodes.map(n => [n.id, n]));
      merge({ ...current, nodes: result.nodes.map(n => byId.get(n.id) ?? {
        id: n.id, label: n.symbol, qualifiedName: n.symbol, kind: n.kind, filePath: n.filePath,
        startLine: n.startLine ?? 0, endLine: n.endLine ?? 0, module: n.filePath.split('/')[0] || 'external', count: 1,
      }), edges: result.edges.map(e => ({ source: e.source, target: e.target, kind: e.relation, count: 1, sourceLines: e.sourceLine ? [e.sourceLine] : [] })), partial: false });
      impact.value = result;
      notice.value = result.paths.length ? '' : '当前深度内没有可展示的传播路径。';
    } catch (e) { if (ticket === revision) error.value = e instanceof Error ? e.message : '影响路径加载失败'; }
    finally { if (ticket === revision) loading.value = false; }
  }
  function select(node: AtlasNode) { selectedId.value = node.id; pathIndex.value = -1; }
  function clearSelection() { selectedId.value = ''; pathIndex.value = -1; }
  function overview() { module.value = ''; query.value = ''; void load(); }
  function loadMore() { limit.value = Math.min(1200, limit.value + 240); void load(true); }
  function openSource(node: AtlasNode, line?: number) {
    if (!data.value) return;
    void router.push({ name: 'search', query: { path: node.filePath, contentVersion: data.value.contentVersion,
      startLine: String(line || node.startLine || 1), branchId: branches.context?.branchId, contextId: branches.context?.contextId } });
  }
  watch(() => [repositories.selectedRepositoryId, branches.identity, route.query.path, route.query.symbol, route.query.contentVersion], () => {
    module.value = ''; query.value = typeof route.query.symbol === 'string' ? route.query.symbol : typeof route.query.path === 'string' ? route.query.path : '';
    void load();
  }, { immediate: true });
  onMounted(() => { if (!repositories.repositories.length) void repositories.loadRepositories(); });
  onBeforeUnmount(() => { revision++; });
  return { repositories, branches, data, selected, query, module, loading, error, notice, depth, limit,
    impact, pathIndex, select, clearSelection, load, overview, loadMore, expand, traceImpact, openSource };
}