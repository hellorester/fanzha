import { reactive } from 'vue'
export const session=reactive({user:null,csrf:'',ready:false})
export async function api(path,body,method){
 const response=await fetch('/api'+path,{method:method||(body===undefined?'GET':'POST'),credentials:'same-origin',headers:{...(body instanceof FormData?{}:body===undefined?{}:{'Content-Type':'application/json'}),...(session.csrf?{'X-CSRF-Token':session.csrf}:{})},body:body===undefined?undefined:body instanceof FormData?body:JSON.stringify(body)})
 const data=await response.json().catch(()=>({message:'服务未返回有效数据'}))
 if(!response.ok){if(response.status===401&&path!='/login'){session.user=null;const fresh=await fetch('/api/session',{credentials:'same-origin'}).then(r=>r.json()).catch(()=>null);if(fresh?.csrf)session.csrf=fresh.csrf;location.hash='welcome'}const e=new Error(data.message||'操作未完成');e.status=response.status;throw e}
 if(data.csrf)session.csrf=data.csrf
 return data
}
export async function boot(){const data=await api('/session');session.user=data.user;session.ready=true;return data}
export const fmt=(v)=>v==null||v===''?'—':typeof v==='boolean'?(v?'是':'否'):typeof v==='number'?new Intl.NumberFormat('zh-CN',{maximumFractionDigits:2}).format(v):String(v).replace(/^(\d{4}-\d{2}-\d{2})T/,'$1 ')
export const money=(v)=>new Intl.NumberFormat('zh-CN',{style:'currency',currency:'CNY'}).format(Number(v||0))
export const businessName=v=>String(v??'').replace(/^演示居民(?=[一二三\d])/,'居民').replace(/^模拟账户(?=\d)/,'账户').replace(/（模拟）$/,'').replace(/^模拟监测$/,'监测线索')
export const localTime=()=>{let d=new Date();return new Date(d-d.getTimezoneOffset()*60000).toISOString().slice(0,19)}
export const roleNames={CENTER:'反诈中心',PUBLIC:'群众服务',PAYMENT:'支付机构',GRID:'网格劝阻'}
export const statusTone=(s)=>/失效|已取消|办理已结束/.test(s)?'gray':/拒绝|驳回|未完成|未处理|失败|高|超时/.test(s)?'red':/已闭环|已解除|已执行|已完成|已归档|已回传|核定通过|已发布|已成功入账|低/.test(s)?'green':/受理|待|中|已送达|已核实|已归并|部分入账/.test(s)?'amber':'blue'
