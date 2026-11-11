<script setup>
import {ref,onMounted,onBeforeUnmount,nextTick,useId} from 'vue'
import {api,fmt,money} from '../api'
import AppIcon from './AppIcon.vue'
const props=defineProps({kind:{type:String,required:true}})
const emit=defineEmits(['close'])
const result=ref(null),busy=ref(false),error=ref(''),dialog=ref(null),id=useId()
let active=true,previous=document.activeElement
async function run(){
 if(busy.value)return
 busy.value=true;error.value='';result.value=null
 try{const data=await api(props.kind==='funds'?'/reconciliation':'/audit/verify');if(active)result.value=data}
 catch(e){if(active)error.value=e.message}finally{if(active)busy.value=false}
}
function keydown(event){
 if(event.key==='Escape'){event.preventDefault();event.stopPropagation();emit('close');return}
 if(event.key!=='Tab')return
 const items=[...dialog.value.querySelectorAll('button:not(:disabled),summary,a[href]')].filter(item=>item.getClientRects().length)
 const first=items[0],last=items.at(-1)
 if(event.shiftKey&&(document.activeElement===first||document.activeElement===dialog.value)){event.preventDefault();last?.focus()}
 else if(!event.shiftKey&&(document.activeElement===last||document.activeElement===dialog.value)){event.preventDefault();first?.focus()}
}
onMounted(()=>{dialog.value?.focus({preventScroll:true});void run()})
onBeforeUnmount(()=>{active=false;nextTick(()=>{if(previous?.isConnected)previous.focus({preventScroll:true})})})
</script>

<template>
 <div class="modal-backdrop" @click.self="emit('close')">
  <section ref="dialog" class="dialog integrity-dialog" :class="{'audit-dialog':kind==='audit'}" role="dialog" aria-modal="true" :aria-labelledby="id+'-title'" tabindex="-1" @keydown="keydown">
   <header class="dialog-header"><h2 :id="id+'-title'">{{kind==='funds'?'资金对账':'日志校验'}}</h2><button type="button" class="icon-button" aria-label="关闭窗口" @click="emit('close')"><AppIcon name="close" :size="20"/></button></header>
   <div class="dialog-body" :aria-busy="busy">
    <p v-if="busy" class="integrity-loading" role="status">{{kind==='funds'?'正在对账…':'正在校验…'}}</p>
    <div v-if="error" class="error-box" role="alert">{{error}}<button type="button" class="text-button" @click="run">重试</button></div>
    <template v-if="result">
     <div class="integrity-result" role="status"><span class="badge" :class="result.ok?'green':'red'">{{result.ok?'核验通过':'发现差异'}}</span><span>{{kind==='funds'?`${result.accounts.length} 个账户 · ${result.issueCount} 项差异`:result.message+' · '+result.checked+' 条记录'}}</span><time>本次校验 {{fmt(result.checkedAt)}}</time></div>
     <template v-if="kind==='funds'">
      <div class="integrity-table"><table><thead><tr><th scope="col">账户</th><th scope="col">期初基线</th><th scope="col">可用余额</th><th scope="col">剩余冻结</th><th scope="col">返还转出</th><th scope="col">返还入账</th><th scope="col">结果</th></tr></thead><tbody>
       <tr v-for="account in result.accounts" :key="account.id">
        <td data-label="账户">{{account.account}}<small>#{{account.id}}</small></td>
        <td data-label="期初基线">{{money(account.opening)}}<small>{{account.baseline}}</small></td>
        <td data-label="可用余额">{{money(account.available)}}</td>
        <td data-label="剩余冻结">{{money(account.frozen)}}</td>
        <td data-label="返还转出">{{money(account.outflow)}}</td>
        <td data-label="返还入账">{{money(account.inflow)}}</td>
        <td data-label="结果"><span class="badge" :class="account.ok?'green':'red'">{{account.ok?'一致':'有差异'}}</span></td>
       </tr>
      </tbody></table></div>
      <details v-for="account in result.accounts.filter(a=>!a.ok)" :key="account.id" open><summary>账户 #{{account.id}} · {{account.issues.length}} 项差异</summary><ul><li v-for="(issue,index) in account.issues" :key="index">{{issue.check}} · 记录 #{{issue.reference}}<span v-if="issue.expected!=null"> · 应为 {{money(issue.expected)}}，实为 {{money(issue.actual)}}</span></li></ul></details>
     </template>
     <p v-if="kind==='audit'&&result.sealedAt" class="integrity-baseline">审计链建立于 {{fmt(result.sealedAt)}}</p>
     <p v-if="kind==='audit'&&!result.ok" class="error-box">请检查记录 #{{result.firstInvalid}} 及其前后链路。</p>
    </template>
   </div>
  </section>
 </div>
</template>

<style scoped>
.integrity-dialog{width:980px;max-width:100%;display:flex;flex-direction:column;overflow:hidden;outline:none}
.integrity-dialog>.dialog-body{flex:1}
.audit-dialog{width:580px}
.integrity-loading{margin:0;padding:28px 0;color:#64748b;text-align:center}
.integrity-result{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin:0 0 18px;font-size:13px}
.integrity-result time{margin-left:auto;color:#64748b;font-size:12px}
.integrity-baseline{font-size:12px;color:#64748b;margin:12px 0 0}
.integrity-table{overflow:auto}
table{width:100%;border-collapse:collapse;font-size:13px}
th,td{text-align:left;padding:12px 10px;border-bottom:1px solid #e8edf4;white-space:nowrap}
th{font-weight:500;color:#53637c}
small{display:block;font-size:11px;color:#64748b;margin-top:5px}
details{margin-top:16px;font-size:13px}
summary{cursor:pointer;font-weight:600}
li{margin:8px 0;line-height:1.6}
@media(max-width:700px){
 .integrity-result{gap:8px}
 .integrity-result time{flex-basis:100%;margin-left:0}
 .integrity-table table{display:block}
 .integrity-table thead{display:none}
 .integrity-table tbody{display:grid;gap:12px}
 .integrity-table tr{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px;padding:16px;border:1px solid var(--line);border-radius:6px}
 .integrity-table td{display:block;min-width:0;padding:0;border:0;white-space:normal;overflow-wrap:anywhere}
 .integrity-table td::before{content:attr(data-label);display:block;color:#64748b;font-size:12px;margin-bottom:4px}
 .integrity-table td:first-child{grid-column:1/-1}
}
</style>
