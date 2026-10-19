import test from 'node:test';
import assert from 'node:assert/strict';
import { achieved } from '../lib/report-metrics.ts';
test('device metrics count connection evidence without treating CRM outcomes as calls',()=>{
 const calls=[{type:'OUTGOING',durationSec:120},{type:'OUTGOING',durationSec:0},{type:'INCOMING',durationSec:60},{type:'MISSED',durationSec:0}];
 const outcomes=[{dispositionId:'interested'},{dispositionId:'interested'},{dispositionId:'converted'}];
 assert.equal(achieved('calls',calls,outcomes),4);
 assert.equal(achieved('connected',calls,outcomes),2);
 assert.equal(achieved('outgoing',calls,outcomes),2);
 assert.equal(achieved('minutes',calls,outcomes),3);
 assert.equal(achieved('interested',calls,outcomes),2);
 assert.equal(achieved('converted',calls,outcomes),1);
 assert.equal(achieved('connected',[],outcomes),0);
});
