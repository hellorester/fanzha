import test from 'node:test'
import assert from 'node:assert/strict'
import {createDetailLoader} from '../src/detail-loader.js'
function fixture(){
 const requests=[],state={detail:null,error:null}
 const loader=createDetailLoader({request:path=>new Promise((resolve,reject)=>requests.push({path,resolve,reject})),start:(type,row)=>{state.detail={type,id:row.id,loading:true};state.error=null},commit:detail=>{state.detail=detail;state.error=detail.error||null}})
 return {loader,requests,state}
}
test('last selected detail wins when responses finish in reverse order',async()=>{
 const {loader,requests,state}=fixture();const a=loader.open('reports',{id:1}),b=loader.open('reports',{id:2})
 requests[1].resolve({code:'B'});await b;requests[0].resolve({code:'A'});await a
 assert.equal(state.detail.id,2);assert.equal(state.detail.data.code,'B')
})
test('closing, navigation or account change invalidates pending success and failure',async()=>{
 for(const reject of [false,true]){const {loader,requests,state}=fixture();const pending=loader.open('reports',{id:1});loader.invalidate();state.detail=null
  if(reject)requests[0].reject(new Error('stale error'));else requests[0].resolve({code:'A'});await pending
  assert.equal(state.detail,null);assert.equal(state.error,null)
 }
})
test('stale failure cannot replace the latest successful record',async()=>{
 const {loader,requests,state}=fixture();const a=loader.open('reports',{id:1}),b=loader.open('reports',{id:2});requests[1].resolve({code:'B'});await b;requests[0].reject(new Error('A failed'));await a
 assert.equal(state.detail.id,2);assert.equal(state.error,null)
})
test('reviewer risk-list detail uses supplied row without a nonexistent request',async()=>{
 const {loader,requests,state}=fixture();await loader.open('blacklist',{id:3,kind:'号码',value:'13800000009',source:'课程资料',enabled:true})
 assert.equal(requests.length,0);assert.equal(state.detail.data.source,'课程资料');assert.equal(state.detail.loading,false)
})

test('failed detail discards the linked summary and finishes loading with a local error',async()=>{
 const {loader,requests,state}=fixture()
 const pending=loader.open('commands',{id:24,code:'JC24',kind:'解除',state:'拒绝执行',actual:0})
 requests[0].reject(new Error('指令不存在或无权访问'));await pending
 assert.deepEqual(state.detail,{type:'commands',id:24,label:'JC24',data:{},loading:false,error:'指令不存在或无权访问'})
})

test('retrying a failed detail replaces the error with complete server data',async()=>{
 const {loader,requests,state}=fixture();const first=loader.open('commands',{id:24})
 requests[0].reject(new Error('连接暂时中断'));await first
 const retry=loader.open('commands',{id:24});assert.equal(state.error,null)
 requests[1].resolve({id:24,code:'JC24',kind:'解除',state:'已解除',receipts:[]});await retry
 assert.equal(state.detail.loading,false);assert.equal(state.detail.data.state,'已解除');assert.equal(state.error,null)
})
