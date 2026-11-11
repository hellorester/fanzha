import test from 'node:test'
import assert from 'node:assert/strict'
import {webcrypto} from 'node:crypto'
import {createRequestKey} from '../src/request-key.js'

const uuidV4=/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

test('request keys work with the native secure-context API',()=>{
 const keys=Array.from({length:100},()=>createRequestKey(webcrypto))
 assert.ok(keys.every(key=>uuidV4.test(key)))
 assert.equal(new Set(keys).size,keys.length)
})

test('LAN HTTP without randomUUID can create distinct request keys',()=>{
 const httpCrypto={getRandomValues:bytes=>webcrypto.getRandomValues(bytes)}
 assert.equal(typeof httpCrypto.randomUUID,'undefined')
 const keys=Array.from({length:100},()=>createRequestKey(httpCrypto))
 assert.ok(keys.every(key=>uuidV4.test(key)))
 assert.equal(new Set(keys).size,keys.length)
})

test('HTTP fallback retains random bytes and sets UUID version and variant',()=>{
 const httpCrypto={getRandomValues:bytes=>{bytes.fill(255);return bytes}}
 assert.equal(createRequestKey(httpCrypto),'ffffffff-ffff-4fff-bfff-ffffffffffff')
})
