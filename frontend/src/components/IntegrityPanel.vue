<script setup>
import {ref} from 'vue'
import {api,fmt,money} from '../api'
const props=defineProps({kind:{type:String,required:true},showHeader:{type:Boolean,default:true}})
const result=ref(null),busy=ref(false),error=ref('')
async function run(){
 busy.value=true;error.value='';result.value=null
 try{result.value=await api(props.kind==='funds'?'/reconciliation':'/audit/verify')}
 catch(e){error.value=e.message}finally{busy.value=false}
}
defineExpose({run,busy})
</script>

<template>
 <section class="integrity-panel" :class="{compact:!showHeader,empty:!showHeader&&!busy&&!error&&!result}">
  <div v-if="showHeader" class="panel-heading"><h3>{{kind==='funds'?'资金一致性核验':'日志完整性核验'}}</h3><button :disabled="busy" @click="run">{{busy?'正在核验…':kind==='funds'?'资金对账':'校验日志'}}</button></div>
  <p v-if="error" class="error-box" role="alert">{{error}}</p>
  <template v-if="result">
   <div class="integrity-result" role="status"><span class="badge" :class="result.ok?'green':'red'">{{result.ok?'核验通过':'发现差异'}}</span><span>{{kind==='funds'?`${result.accounts.length} 个账户 · ${result.issueCount} 项差异`:result.message+' · '+result.checked+' 条记录'}}</span><time>本次校验 {{fmt(result.checkedAt)}}</time></div>
   <template v-if="kind==='funds'">
    <div class="integrity-table"><table><thead><tr><th>账户</th><th>期初基线</th><th>可用余额</th><th>剩余冻结</th><th>返还转出</th><th>返还入账</th><th>结果</th></tr></thead><tbody><tr v-for="account in result.accounts" :key="account.id"><td>{{account.account}}<small>#{{account.id}}</small></td><td>{{money(account.opening)}}<small>{{account.baseline}}</small></td><td>{{money(account.available)}}</td><td>{{money(account.frozen)}}</td><td>{{money(account.outflow)}}</td><td>{{money(account.inflow)}}</td><td><span class="badge" :class="account.ok?'green':'red'">{{account.ok?'一致':'有差异'}}</span></td></tr></tbody></table></div>
    <details v-for="account in result.accounts.filter(a=>!a.ok)" :key="account.id" open><summary>账户 #{{account.id}} · {{account.issues.length}} 项差异</summary><ul><li v-for="(issue,index) in account.issues" :key="index">{{issue.check}} · 记录 #{{issue.reference}}<span v-if="issue.expected!=null"> · 应为 {{money(issue.expected)}}，实为 {{money(issue.actual)}}</span></li></ul></details>
   </template>
   <p v-if="kind==='audit'&&result.sealedAt" class="integrity-baseline">审计链建立于 {{fmt(result.sealedAt)}}</p>
   <p v-if="kind==='audit'&&!result.ok" class="error-box">请检查记录 #{{result.firstInvalid}} 及其前后链路。</p>
  </template>
 </section>
</template>

<style scoped>
.integrity-panel{border-bottom:1px solid var(--border,#e4e8ef);padding:0 22px 18px}
.integrity-panel.empty{display:none}
.integrity-panel.compact{padding-top:10px}
.panel-heading{padding:18px 0 8px;margin:0}.panel-heading h3{font-size:14px}
.integrity-result{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin:8px 0 16px;font-size:13px}.integrity-result time{margin-left:auto;color:#64748b}
.integrity-baseline{font-size:12px;color:#64748b;margin-top:12px}
.integrity-table{overflow:auto}table{width:100%;border-collapse:collapse;font-size:13px}th,td{text-align:left;padding:12px 10px;border-bottom:1px solid #e8edf4;white-space:nowrap}th{font-weight:500;color:#53637c}small{display:block;font-size:11px;color:#64748b;margin-top:5px}
details{margin-top:16px;font-size:13px}summary{cursor:pointer;font-weight:600}li{margin:8px 0;line-height:1.6}
</style>
