import test from 'node:test';
import assert from 'node:assert/strict';
import {pendingLead,compareLeads,assignedEmployee,staffName} from '../lib/lead-workspace.ts';
test('staff labels use employee uid, not shared Google name or employee document id',()=>{
 const members=[{id:'a',name:'Shared account'},{id:'b',name:'Shared account'},{id:'owner',name:'Owner'}];
 const staff=[{id:'E1',uid:'a',name:'Asha'},{id:'E2',uid:'b',name:'Ravi'}];
 assert.equal(staffName('a',members,staff),'Asha'); assert.equal(staffName('b',members,staff),'Ravi'); assert.equal(staffName('owner',members,staff),'Owner');
});
test('all saved outcomes leave pending without needing terminal status',()=>{
 assert.equal(pendingLead({status:'new'}),true);
 for(const status of ['interested','busy','no_answer','call_back','converted'])assert.equal(pendingLead({status,lastAttemptId:'saved'}),false);
 assert.equal(pendingLead({status:'new',lastContactAt:123}),false);
 assert.equal(pendingLead({status:'new',doNotCall:true}),false);
});
test('sorting respects file rows, batches, and existing creation order rather than phone ids',()=>{
 const rows=[{id:'phone_1',createdAt:300,importStartedAt:100,importBatchId:'x',importRow:3},{id:'phone_9',createdAt:200,importStartedAt:100,importBatchId:'x',importRow:2},{id:'old9',createdAt:2},{id:'old1',createdAt:3}];
 assert.deepEqual(rows.sort(compareLeads).map(r=>r.id),['old9','old1','phone_9','phone_1']);
});
test('assignment keeps contiguous balanced blocks including fewer leads than staff',()=>{
 assert.deepEqual(Array.from({length:7},(_,i)=>assignedEmployee(i,7,['a','b','c'])),['a','a','a','b','b','c','c']);
 assert.deepEqual([0,1].map(i=>assignedEmployee(i,2,['a','b','c'])),['a','b']);
});
