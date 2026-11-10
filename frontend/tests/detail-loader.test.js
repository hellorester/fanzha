import test from 'node:test'
import assert from 'node:assert/strict'
import {createDetailLoader} from '../src/detail-loader.js'
function fixture(){
 const requests=[],state={detail:null,error:null}
 const loader=createDetailLoader({request:path=>new Promise((resolve,reject)=>requests.push({path,resolve,reject})),start:(type,row)=>{state.detail={type,id:row.id,loading:true}},commit:detail=>{state.detail=detail},fail:error=>{state.error=error.message}})
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
