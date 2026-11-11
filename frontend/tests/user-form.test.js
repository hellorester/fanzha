import test from 'node:test'
import assert from 'node:assert/strict'
import {normalizeUserData,personOptions,resetUserRole,resetVerification,userSummary} from '../src/user-form.js'

test('role changes clear fields that belong to the previous role',()=>{
 const data={role:'PAYMENT',orgId:2,communityId:3,victimId:4,verification:'VERIFIED',admin:true}
 resetUserRole(data)
 assert.deepEqual(data,{role:'PAYMENT',orgId:'',communityId:'',victimId:'',verification:'UNVERIFIED',admin:false})
})

test('verification status and person cannot become contradictory',()=>{
 const data={verification:'UNVERIFIED',victimId:8}
 resetVerification(data)
 assert.equal(data.victimId,'')
 assert.deepEqual(normalizeUserData({...data,username:'public4',displayName:'居民四',role:'PUBLIC',password:'Demo2026!',enabled:true,admin:true,orgId:1,communityId:2}),{
  username:'public4',displayName:'居民四',role:'PUBLIC',password:'Demo2026!',orgId:'',communityId:'',victimId:'',verified:false,enabled:true,admin:false
 })
})

test('only the selected role contributes ownership fields',()=>{
 const base={username:'staff',displayName:'工作人员',password:'Demo2026!',enabled:true,verification:'VERIFIED',victimId:9,orgId:2,communityId:3,admin:true}
 assert.deepEqual(normalizeUserData({...base,role:'PAYMENT'}),{username:'staff',displayName:'工作人员',role:'PAYMENT',password:'Demo2026!',orgId:2,communityId:'',victimId:'',verified:false,enabled:true,admin:false})
 assert.deepEqual(normalizeUserData({...base,role:'GRID'}),{username:'staff',displayName:'工作人员',role:'GRID',password:'Demo2026!',orgId:'',communityId:3,victimId:'',verified:false,enabled:true,admin:false})
 assert.deepEqual(normalizeUserData({...base,role:'CENTER'}),{username:'staff',displayName:'工作人员',role:'CENTER',password:'Demo2026!',orgId:'',communityId:'',victimId:'',verified:false,enabled:true,admin:true})
})

test('person choices and confirmation summary include identity context',()=>{
 const meta={victims:[{id:2,name:'居民二',community_name:'云栖社区',identity:'**************5678'}],orgs:[],communities:[]}
 assert.equal(personOptions(meta.victims)[0].label,'居民二 · 云栖社区 · 证件 **************5678')
 assert.deepEqual(userSummary({role:'PUBLIC',verification:'VERIFIED',victimId:2,enabled:true},meta,{PUBLIC:'群众服务'}),[
  {label:'业务角色',value:'群众服务'},
  {label:'实名状态',value:'居民二 · 云栖社区 · 证件 **************5678'},
  {label:'账号状态',value:'启用'}
 ])
})
