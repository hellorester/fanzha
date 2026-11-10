import test from 'node:test'
import assert from 'node:assert/strict'
import {dispatchTargets} from '../src/dispatch-targets.js'

const banks=[{id:1},{id:2},{id:3}]
const freeze=(id,bank_id,state,dispatch_reusable=false)=>({id,bank_id,state,dispatch_reusable,kind:'止付'})

test('unscanned expiry and timeout cannot appear as reusable account choices',()=>{
 const targets=dispatchTargets(banks,[{...freeze(1,1,'已延期'),dispatch_deadline_passed:true},{...freeze(2,2,'受理中'),dispatch_deadline_passed:true}])
 assert.deepEqual(targets.map(b=>[b.dispatchLabel,b.disabled]),[['已到期，不再复用',true],['已超时，请复核重发',true],['新派发',false]])
})

test('failed accounts require retry while untouched accounts allow first dispatch',()=>{
 const targets=dispatchTargets(banks,[freeze(1,1,'拒绝执行'),freeze(2,2,'未处理')])
 assert.deepEqual(targets.map(b=>[b.id,b.dispatchLabel,b.disabled]),[[1,'请复核重发',true],[2,'请复核重发',true],[3,'新派发',false]])
})

test('a live retry is reused despite failed history; later failure targets retry again',()=>{
 const history=[freeze(2,1,'已送达',true),freeze(1,1,'拒绝执行')]
 assert.deepEqual(dispatchTargets(banks,history)[0],{id:1,dispatchLabel:'复用现有指令',disabled:false})
 history[0]={...history[0],state:'未处理',dispatch_reusable:false}
 assert.deepEqual(dispatchTargets(banks,history)[0],{id:1,dispatchLabel:'请复核重发',disabled:true})
})

test('released freezes stay historical and aftercare commands do not create new dispatch targets',()=>{
 const targets=dispatchTargets(banks,[freeze(1,1,'已解除'),{id:2,bank_id:1,kind:'返还',dispatch_reusable:true},{id:3,bank_id:2,kind:'解除',dispatch_reusable:true}])
 assert.equal(targets[0].disabled,true)
 assert.equal(targets[0].dispatchLabel,'已办理，不再新建')
 assert.equal(targets[1].dispatchLabel,'新派发')
})
