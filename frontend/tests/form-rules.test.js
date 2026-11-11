import test from 'node:test'
import assert from 'node:assert/strict'
import {configFieldLimits,executionAmountMax,executionReasonRequired,reviewAmountRequired,syncExecutionResult,syncReviewDecision,syncVisitResult} from '../src/form-rules.js'

test('rejected execution clears money and requires a reason',()=>{
 const data={result:'拒绝执行',amount:'600.00'}
 syncExecutionResult(data,{requested:'600.00'})
 assert.equal(data.amount,0)
 assert.equal(executionReasonRequired(data),true)
})

test('known full amount is restored and partial amount cannot keep the full amount',()=>{
 const full={result:'全额执行',amount:''}
 syncExecutionResult(full,{requested:'600.00'})
 assert.equal(full.amount,'600.00')
 assert.equal(executionAmountMax(full,'600.00'),'600.00')
 const partial={result:'部分执行',amount:'600.00'}
 syncExecutionResult(partial,{requested:'600.00'})
 assert.equal(partial.amount,'')
 assert.equal(executionAmountMax(partial,'600.00'),599.99)
 assert.equal(executionReasonRequired(partial),true)
})

test('refund approval amount is required only for approval',()=>{
 assert.equal(reviewAmountRequired({decision:'通过'}),true)
 assert.equal(reviewAmountRequired({decision:'驳回'}),false)
 const rejected={decision:'驳回',amount:'400.00'}
 syncReviewDecision(rejected)
 assert.equal(rejected.amount,0)
 const approved={decision:'通过',amount:0}
 syncReviewDecision(approved)
 assert.equal(approved.amount,'')
})

test('finished visit and successful receipt clear no-longer-applicable reasons',()=>{
 const visit={result:'已完成',reason:'此前未完成'}
 syncVisitResult(visit)
 assert.equal(visit.reason,'')
 const receipt={result:'全额执行',amount:'',reason:'此前拒绝'}
 syncExecutionResult(receipt,{requested:'600.00'})
 assert.equal(receipt.amount,'600.00')
 assert.equal(receipt.reason,'')
 const correction={result:'全额执行',amount:'',reason:'说明为什么更正'}
 syncExecutionResult(correction,{requested:'600.00',clearReason:false})
 assert.equal(correction.reason,'说明为什么更正')
})

test('rule editor uses the same numeric bounds as backend validation',()=>{
 assert.deepEqual(configFieldLimits.medium,{min:1,max:100})
 assert.deepEqual(configFieldLimits.high,{min:1,max:100})
 for(const key of ['mergeHours','freezeHours','extensionHours','timeoutMinutes'])assert.deepEqual(configFieldLimits[key],{min:1,max:1680})
 assert.deepEqual(configFieldLimits.maxExtensions,{min:0,max:10})
})
