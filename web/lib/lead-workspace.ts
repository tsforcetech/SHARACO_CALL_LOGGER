type Data = Record<string, unknown>;
export const pendingLead = (r: Data) => !r.terminal && !r.doNotCall && !r.lastAttemptId && !r.lastContactAt && (!r.status || r.status === 'new');
export function compareLeads(a: Data, b: Data) {
  const time = (r: Data) => Number(r.importStartedAt || r.createdAt || 0);
  return time(a)-time(b) || String(a.importBatchId || '').localeCompare(String(b.importBatchId || '')) || Number(a.importRow || 0)-Number(b.importRow || 0) || String(a.id).localeCompare(String(b.id));
}
// Give each employee a consecutive, balanced block in selection order.
export function assignedEmployee(index: number, total: number, employees: string[]) {
  if (!employees.length || index < 0 || index >= total) throw Error('Invalid assignment');
  const size = Math.floor(total / employees.length), extra = total % employees.length;
  let end = 0;
  for (let i=0; i<employees.length; i++) { end += size + (i < extra ? 1 : 0); if (index < end) return employees[i]; }
  throw Error('Invalid assignment');
}
export function staffName(uid: string, members: Data[], employees: Data[]) {
  const employee = employees.find(e => e.uid === uid);
  const member = members.find(m => m.id === uid);
  return String(employee?.name || member?.name || member?.email || uid);
}
