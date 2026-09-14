export function branchContextOptions(contextId?: string | null) {
  return contextId
    ? { headers: { 'X-Branch-Context': contextId } }
    : undefined;
}

export function withBranchContext(
  contextId: string | null | undefined,
  options: RequestInit = {},
): RequestInit {
  if (!contextId) return options;
  return {
    ...options,
    headers: {
      ...Object.fromEntries(new Headers(options.headers).entries()),
      'X-Branch-Context': contextId,
    },
  };
}
