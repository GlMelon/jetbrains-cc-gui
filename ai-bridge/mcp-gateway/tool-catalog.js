export function buildCatalog(revision, supervisors) {
  const tools = [];
  for (const supervisor of supervisors.values()) {
    tools.push(...(supervisor.tools ?? []));
  }
  return { revision: Number(revision), tools };
}
