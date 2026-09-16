import { effectScope, reactive } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { beforeEach, expect, it, vi } from 'vitest';
import * as api from '@/api/repositories';
import { branchesApi } from '@/api/branches';
import { useProjectOverview } from './useProjectOverview';
let repositories: any;
let branches: any;
vi.mock('@/stores/repositoryStore',()=>({useRepositoryStore:()=>repositories}));
vi.mock('@/stores/branchContextStore',()=>({useBranchContextStore:()=>branches}));
vi.mock('@/api/repositories',()=>({
  getBranchOverview:vi.fn(),getRepositoryProfile:vi.fn(),getProjectCodeFacts:vi.fn(),
  getProjectHealthOverview:vi.fn(),prepareRepository:vi.fn(),retryPreparationStage:vi.fn(),getIndexJob:vi.fn(),
}));
vi.mock('@/api/branches',()=>({branchesApi:{codeOperation:vi.fn(),prepareVectors:vi.fn()}}));
vi.mock('element-plus',()=>({ElMessage:{success:vi.fn(),warning:vi.fn(),error:vi.fn()}}));
function pin(snapshot='s') {
  branches.selectedBranchId='release';
  branches.context={contextId:'ctx-'+snapshot,repositoryId:'p',branchId:'release',branchName:'release',snapshotId:snapshot,commitSha:snapshot};
  branches.identity='release:'+snapshot;
}
function overview(snapshot='s') {
  return {preparation:{snapshotId:snapshot,commitSha:snapshot,state:'READY'},codeFacts:{snapshotId:snapshot,commitSha:snapshot},health:{snapshotId:snapshot,commitSha:snapshot}} as api.BranchOverview;
}
beforeEach(()=>{
  vi.resetAllMocks();
  repositories=reactive({selectedRepositoryId:'p',selectedRepository:{sourceType:'LOCAL_GIT'}});
  branches=reactive({selectedBranchId:null,context:null,identity:'pending',error:null,loading:false});
  vi.mocked(api.getBranchOverview).mockResolvedValue(overview());
});
it('does not read default overview data when a Git branch context is missing',async()=>{
  const scope=effectScope();
  const result=scope.run(useProjectOverview)!;
  try {
    await flushPromises();
    expect(result.error.value).toContain('分支快照未就绪');
    expect(api.getBranchOverview).not.toHaveBeenCalled();
    expect(api.getRepositoryProfile).not.toHaveBeenCalled();
  } finally {scope.stop();}
});
it('reads all overview facts from the pinned context and submits only branch operations',async()=>{
  pin();
  const scope=effectScope();
  const result=scope.run(useProjectOverview)!;
  try {
    await flushPromises();
    expect(api.getBranchOverview).toHaveBeenCalledWith('p','ctx-s');
    expect(result.preparation.value?.snapshotId).toBe('s');
    await result.prepare();
    await result.retryStage('graph');
    expect(branchesApi.codeOperation).toHaveBeenNthCalledWith(1,'p','release','PREPARE');
    expect(branchesApi.codeOperation).toHaveBeenNthCalledWith(2,'p','release','GRAPH','ctx-s');
    expect(api.prepareRepository).not.toHaveBeenCalled();
    expect(api.retryPreparationStage).not.toHaveBeenCalled();
    expect(api.getRepositoryProfile).not.toHaveBeenCalled();
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

it('shows loading while the branch snapshot is being pinned and loads the overview when ready', async () => {
  branches.loading = true;
  branches.identity = 'loading';
  const scope = effectScope();
  const result = scope.run(useProjectOverview)!;
  try {
    await flushPromises();
    expect(result.loading.value).toBe(true);
    expect(result.error.value).toBeNull();
    expect(api.getBranchOverview).not.toHaveBeenCalled();
    expect(api.getRepositoryProfile).not.toHaveBeenCalled();

    pin();
    branches.loading = false;
    await flushPromises();
    expect(api.getBranchOverview).toHaveBeenCalledWith('p', 'ctx-s');
    expect(result.preparation.value?.snapshotId).toBe('s');
    expect(result.loading.value).toBe(false);
    expect(result.error.value).toBeNull();
  } finally { scope.stop(); }
});