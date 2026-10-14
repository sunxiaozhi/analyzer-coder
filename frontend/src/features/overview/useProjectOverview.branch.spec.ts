import { effectScope, reactive } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import * as api from '@/api/repositories';
import { branchesApi } from '@/api/branches';
import { useProjectOverview } from './useProjectOverview';
let repositories: any;
let branches: any;
afterEach(() => vi.useRealTimers());
vi.mock('@/stores/repositoryStore',()=>({useRepositoryStore:()=>repositories}));
vi.mock('@/stores/branchContextStore',()=>({useBranchContextStore:()=>branches}));
vi.mock('@/api/repositories',()=>({
  getBranchOverview:vi.fn(),
}));
vi.mock('@/api/branches',()=>({branchesApi:{codeOperation:vi.fn(),prepareVectors:vi.fn(),preparationJobs:vi.fn()}}));
vi.mock('element-plus',()=>({ElMessage:{success:vi.fn(),warning:vi.fn(),error:vi.fn()}}));
function pin(contentVersion='s') {
  branches.selectedBranchId='release';
  branches.context={contextId:'ctx-'+contentVersion,repositoryId:'p',branchId:'release',branchName:'release',contentVersion:contentVersion,commitSha:contentVersion};
  branches.identity='release:'+contentVersion;
}
function overview(contentVersion='s') {
  return {preparation:{contentVersion:contentVersion,commitSha:contentVersion,state:'READY'},codeFacts:{contentVersion:contentVersion,commitSha:contentVersion},health:{contentVersion:contentVersion,commitSha:contentVersion}} as api.BranchOverview;
}
beforeEach(()=>{
  vi.resetAllMocks();
  repositories=reactive({selectedRepositoryId:'p',selectedRepository:{sourceType:'LOCAL_GIT'}});
  branches=reactive({selectedBranchId:null,context:null,identity:'pending',error:null,loading:false,refresh:vi.fn().mockResolvedValue(undefined)});
  vi.mocked(api.getBranchOverview).mockResolvedValue(overview());
  vi.mocked(branchesApi.preparationJobs).mockResolvedValue([]);
});
it('does not read default overview data when a Git branch context is missing',async()=>{
  const scope=effectScope();
  const result=scope.run(useProjectOverview)!;
  try {
    await flushPromises();
    expect(result.error.value).toContain('当前分支版本未就绪');
    expect(api.getBranchOverview).not.toHaveBeenCalled();
  } finally {scope.stop();}
});
it('reads all overview facts from the pinned context and submits only branch operations',async()=>{
  pin();
  const scope=effectScope();
  const result=scope.run(useProjectOverview)!;
  try {
    await flushPromises();
    expect(api.getBranchOverview).toHaveBeenCalledWith('p','ctx-s');
    expect(result.preparation.value?.contentVersion).toBe('s');
    await result.prepare();
    await result.retryStage('graph');
    expect(branchesApi.codeOperation).toHaveBeenNthCalledWith(1,'p','release','PREPARE');
    expect(branchesApi.codeOperation).toHaveBeenNthCalledWith(2,'p','release','GRAPH','ctx-s');
  } finally {scope.stop();}
});
it('rejects mixed branch overview measurements',async()=>{
  pin();
  vi.mocked(api.getBranchOverview).mockResolvedValue({...overview(),health:{...overview('other').health}});
  const scope=effectScope();
  const result=scope.run(useProjectOverview)!;
  try {
    await flushPromises();
    expect(result.error.value).toContain('版本不一致');
    expect(result.preparation.value).toBeNull();
  } finally {scope.stop();}
});

it('shows loading while the branch contentVersion is being pinned and loads the overview when ready', async () => {
  branches.loading = true;
  branches.identity = 'loading';
  const scope = effectScope();
  const result = scope.run(useProjectOverview)!;
  try {
    await flushPromises();
    expect(result.loading.value).toBe(true);
    expect(result.error.value).toBeNull();
    expect(api.getBranchOverview).not.toHaveBeenCalled();

    pin();
    branches.loading = false;
    await flushPromises();
    expect(api.getBranchOverview).toHaveBeenCalledWith('p', 'ctx-s');
    expect(result.preparation.value?.contentVersion).toBe('s');
    expect(result.loading.value).toBe(false);
    expect(result.error.value).toBeNull();
  } finally { scope.stop(); }
});

it('tracks the selected branch task and refreshes overview after completion', async () => {
  vi.useFakeTimers(); pin();
  let rows: any[] = [];
  vi.mocked(branchesApi.preparationJobs).mockImplementation(async () => rows);
  const job = { id: 'job', branchId: 'release', kind: 'PREPARE', stage: 'QUEUED', status: 'QUEUED', error: null, contentVersion: 's' } as const;
  vi.mocked(branchesApi.codeOperation).mockImplementation(async () => { rows = [job]; return job; });
  const scope = effectScope(); const result = scope.run(useProjectOverview)!;
  try {
    await flushPromises(); await result.prepare(); await flushPromises();
    expect(result.preparing.value).toBe(true);
    await result.prepare(); expect(branchesApi.codeOperation).toHaveBeenCalledTimes(1);
    rows = [{ ...job, stage: 'COMPLETED', status: 'SUCCEEDED' }];
    await vi.advanceTimersByTimeAsync(2500); await flushPromises();
    expect(branches.refresh).toHaveBeenCalledTimes(1);
    expect(api.getBranchOverview).toHaveBeenCalledTimes(2);
    expect(result.preparing.value).toBe(false);
  } finally { scope.stop(); }
});

it('retries the failed task kind with its required context rather than always preparing everything', async () => {
  pin();
  const scope = effectScope(); const result = scope.run(useProjectOverview)!;
  try {
    await flushPromises();
    await result.retryTask('SYNC'); await result.retryTask('CONTENT'); await result.retryTask('VECTORS');
    expect(branchesApi.codeOperation).toHaveBeenNthCalledWith(1, 'p', 'release', 'SYNC');
    expect(branchesApi.codeOperation).toHaveBeenNthCalledWith(2, 'p', 'release', 'CONTENT', 'ctx-s');
    expect(branchesApi.prepareVectors).toHaveBeenCalledWith(branches.context);
  } finally { scope.stop(); }
});
