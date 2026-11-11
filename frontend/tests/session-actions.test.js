import test from 'node:test'
import assert from 'node:assert/strict'
import {endSession} from '../src/session-actions.js'

test('logout succeeds normally without refreshing the session',async()=>{
 let requests=0,refreshes=0
 await endSession({request:async()=>{requests++},refreshSession:async()=>{refreshes++}})
 assert.equal(requests,1)
 assert.equal(refreshes,0)
})

test('stale mobile page refreshes CSRF once and retries logout',async()=>{
 let requests=0,refreshes=0
 await endSession({
  request:async()=>{requests++;if(requests===1)throw Object.assign(new Error('stale'),{status:403})},
  refreshSession:async()=>{refreshes++}
 })
 assert.equal(requests,2)
 assert.equal(refreshes,1)
})

test('an already expired session is treated as logged out',async()=>{
 let refreshes=0
 await endSession({request:async()=>{throw Object.assign(new Error('expired'),{status:401})},refreshSession:async()=>{refreshes++}})
 assert.equal(refreshes,0)
})

test('unrelated failures remain visible to the user',async()=>{
 const failure=Object.assign(new Error('offline'),{status:503})
 await assert.rejects(()=>endSession({request:async()=>{throw failure},refreshSession:async()=>{}}),failure)
})
