import test from 'node:test';
import assert from 'node:assert/strict';
import {metrics,normalize,initialBoundary,callId,health,matches} from '../src/domain.js';
test('central metrics use connected duration and distinct normalized numbers',()=>{
 const m=metrics([{callType:'OUTGOING',durationSeconds:60,normalizedNumber:'a'},{callType:'OUTGOING',durationSeconds:0,normalizedNumber:'a'},{callType:'INCOMING',durationSeconds:120,normalizedNumber:'b'},{callType:'MISSED',durationSeconds:0,normalizedNumber:''},{callType:'REJECTED',durationSeconds:0,normalizedNumber:'b'}]);
 assert.equal(m.total,5);assert.equal(m.connected,2);assert.equal(m.average,90);assert.equal(m.unique,2);assert.equal(m.outgoingRate,50);assert.ok(Math.abs(m.incomingRate-100/3)<1e-9);
});
test('empty reports have finite zero metrics',()=>assert.equal(metrics([]).average,0));
test('normalization preserves short codes and excludes withheld values',()=>{assert.equal(normalize('0501234567'),normalize('00971501234567'));assert.equal(normalize('-1'),'');assert.equal(normalize('999'),'short:AE:999');});
test('company midnight and daylight saving boundaries',()=>{assert.equal(initialBoundary(Date.parse('2026-10-16T10:35Z'),'Asia/Dubai','DAY_START'),Date.parse('2026-10-15T20:00Z'));assert.equal(initialBoundary(Date.parse('2026-03-08T15:00Z'),'America/New_York','DAY_START'),Date.parse('2026-03-08T05:00Z'));});
test('exact join time is preserved',()=>assert.equal(initialBoundary(123456789,'UTC','JOIN_TIME'),123456789));
test('identity is deterministic and prevents reused Android row collisions',()=>{assert.equal(callId('a','1',2),callId('a','1',2));assert.notEqual(callId('a','1',2),callId('a','1',3));assert.notEqual(callId('a','1',2),callId('b','1',2));});
test('filters intersect rather than widen access',()=>assert.equal(matches({employeeUid:'b',callType:'OUTGOING',durationSeconds:10},{employees:['a'],connection:'CONNECTED'}),false));
test('health reports observed permission and freshness',()=>{assert.equal(health({active:true,callLogPermission:false}),'PROBLEM');assert.equal(health({active:true,callLogPermission:true}),'DELAYED');assert.equal(health({active:true,callLogPermission:true,lastSuccessfulSyncAt:1000},1100),'HEALTHY');});
