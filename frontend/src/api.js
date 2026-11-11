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
const presentationNames={'演示居民1':'居民一','演示居民2':'居民二','演示居民3':'居民三','模拟资金操作':'资金操作','使用离线模拟坐标':'手动录入坐标','创建虚拟演示资料与默认配置':'初始化基础资料与默认配置'}
export const businessName=v=>{const text=String(v??'');return presentationNames[text]||text.replace(/^演示居民(?=[一二三])/,'居民').replace(/^账户持有人(?=[一二三\d])/,'居民').replace(/^模拟账户(?=\d)/,'账户').replace(/（模拟）$/,'').replace(/^模拟监测$/,'监测线索').replace(/^课程模拟(?=风险)/,'').replace(/^演示时钟：/,'时效检查：')}
export const localTime=()=>{let d=new Date();return new Date(d-d.getTimezoneOffset()*60000).toISOString().slice(0,19)}
export const roleNames={CENTER:'反诈中心',PUBLIC:'群众服务',PAYMENT:'支付机构',GRID:'网格劝阻'}
const fundsProgressTones={
 '已成功入账':'green','部分入账':'amber','部分入账（已结束）':'gray',
 '执行失败':'red','等待执行':'blue','等待核定':'amber',
 '未通过核定':'red','申请已失效':'gray','办理已结束':'gray'
}
export const statusTone=(s)=>Object.hasOwn(fundsProgressTones,s)?fundsProgressTones[s]:/失效|已取消|已结束|停用|未核验|不适用/.test(s)?'gray':/拒绝|驳回|未完成|未处理|失败|高|超时|冲突/.test(s)?'red':/已闭环|已解除|已执行|已完成|已归档|已回传|核定通过|已发布|已核验|启用|低/.test(s)?'green':/受理|待|中|已送达|已核实|已归并/.test(s)?'amber':'blue'
