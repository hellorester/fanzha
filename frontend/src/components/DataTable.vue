<script setup>
import {computed,ref,watch,onBeforeUnmount} from 'vue'
import {fmt,money,statusTone} from '../api'
import AppIcon from './AppIcon.vue'
const props=defineProps({rows:{type:Array,default:()=>[]},columns:Array,empty:{default:'暂无记录'},selectable:{default:true},server:{default:null},resource:{default:''},actionLabel:{default:'查看详情'}})
const emit=defineEmits(['select','query'])
const search=ref(''),page=ref(1),status=ref(''),from=ref(''),to=ref(''),kind=ref(''),level=ref(''),source=ref(''),roleFilter=ref(''),enabledFilter=ref(''),expanded=ref(false)
const statuses=computed(()=>props.server?.states||[...new Set(props.rows.map(r=>r.state||r.processing).filter(Boolean))])
const filtered=computed(()=>props.server?props.rows:props.rows.filter(r=>(!status.value||(r.state||r.processing)===status.value)&&(!search.value||props.columns.some(c=>String(r[c.key]??'').toLowerCase().includes(search.value.toLowerCase())))))
const total=computed(()=>props.server?.total??filtered.value.length)
const pages=computed(()=>Math.max(1,Math.ceil(total.value/10)))
const visible=computed(()=>props.server?props.rows:filtered.value.slice((page.value-1)*10,page.value*10))
const advanced=computed(()=>props.server&&!['banks','blacklist'].includes(props.resource))
const activeCount=computed(()=>[from.value,to.value,kind.value,level.value,source.value,roleFilter.value,enabledFilter.value].filter(Boolean).length)
let queryTimer
 function emitQuery(){if(!props.server)return;clearTimeout(queryTimer);queryTimer=setTimeout(()=>emit('query',{page:page.value,q:search.value,state:status.value,from:from.value,to:to.value,kind:kind.value,level:level.value,source:source.value,role:roleFilter.value,enabled:enabledFilter.value}),250)}
 function reset(){search.value='';status.value='';from.value='';to.value='';kind.value='';level.value='';source.value='';roleFilter.value='';enabledFilter.value='';page.value=1}
 watch([search,status,from,to,kind,level,source,roleFilter,enabledFilter],()=>{page.value=1;emitQuery()})
watch(page,emitQuery)
watch(()=>props.rows,()=>{if(!props.server)page.value=1})
onBeforeUnmount(()=>clearTimeout(queryTimer))
</script>
<template>
 <div class="table-toolbar">
  <div class="table-tools" :class="{'tools-left':['notices','logs'].includes(resource)}">
    <label class="search"><AppIcon name="search" :size="17"/><input v-model="search" :placeholder="resource==='blacklist'?'类型、来源或完整线索值':resource==='banks'?'机构、户名或完整账号':resource==='users'?'账号、名称、角色或归属':'搜索编号、名称或关键词'" :maxlength="resource==='blacklist'?500:80" aria-label="搜索记录"><button v-if="search" type="button" class="icon-button" aria-label="清空搜索" @click="search=''"><AppIcon name="close" :size="14"/></button></label>
   <select v-if="statuses.length" v-model="status" aria-label="按状态筛选"><option value="">全部状态</option><option v-for="s in statuses" :key="s">{{s}}</option></select>
   <button v-if="advanced" class="filter-toggle" :class="{selected:expanded||activeCount}" :aria-expanded="expanded" @click="expanded=!expanded"><AppIcon name="filter" :size="16"/>筛选<span v-if="activeCount" class="filter-count">{{activeCount}}</span></button>
   <button v-if="search||status||activeCount" class="text-button reset-filter" @click="reset">重置</button>
   <span class="count">共 <strong>{{fmt(total)}}</strong> 条记录</span>
  </div>
  <div v-if="advanced&&expanded" class="secondary-filters">
    <label v-if="resource!=='users'"><span>开始日期</span><input type="date" v-model="from" aria-label="列表开始日期"></label>
    <label v-if="resource!=='users'"><span>结束日期</span><input type="date" v-model="to" aria-label="列表结束日期"></label>
   <label v-if="['reports','commands','applications'].includes(resource)"><span>业务类型</span><select v-model="kind" aria-label="按类型筛选"><option value="">全部类型</option><option v-for="k in resource==='reports'?['号码','账户','网址']:resource==='commands'?['止付','返还','解除']:['返还','申诉']" :key="k">{{k}}</option></select></label>
   <label v-if="['alerts','visits','reports'].includes(resource)"><span>风险等级</span><select v-model="level" aria-label="按风险筛选"><option value="">全部风险</option><option v-for="l in ['高','中','低']" :key="l">{{l}}</option></select></label>
   <label v-if="resource==='reports'"><span>线索来源</span><select v-model="source" aria-label="按来源筛选"><option value="">全部来源</option><option>群众举报</option><option value="模拟监测">监测线索</option></select></label>
    <label v-if="resource==='users'"><span>业务角色</span><select v-model="roleFilter" aria-label="按账号角色筛选"><option value="">全部角色</option><option value="CENTER">反诈中心</option><option value="PUBLIC">群众服务</option><option value="PAYMENT">支付机构</option><option value="GRID">社区网格</option></select></label>
    <label v-if="resource==='users'"><span>账号状态</span><select v-model="enabledFilter" aria-label="按账号状态筛选"><option value="">全部状态</option><option value="true">启用</option><option value="false">停用</option></select></label>
  </div>
 </div>
 <div class="table-scroll" :class="{'application-list':resource==='applications','record-list':resource!=='applications'}">
  <table><thead><tr><th v-for="c in columns" :key="c.key" scope="col">{{c.label}}</th><th v-if="selectable" class="align-right" scope="col">操作</th></tr></thead>
   <tbody><tr v-for="r in visible" :key="r.id||r.code"><td v-for="c in columns" :key="c.key" :data-label="c.label" :class="{'mono':c.key==='code','wide-cell':c.wide}"><span v-if="c.badge" class="badge" :class="statusTone(String(c.format?c.format(r[c.key],r):r[c.key]||''))">{{c.format?c.format(r[c.key],r):fmt(r[c.key])}}{{c.key==='level'?'风险':''}}</span><span v-else>{{c.format?c.format(r[c.key],r):fmt(r[c.key])}}</span></td><td v-if="selectable" class="align-right" data-label="操作"><button class="text-button row-action" @click="emit('select',r)">{{actionLabel}}<AppIcon name="chevron" :size="14"/></button></td></tr></tbody>
  </table>
  <div v-if="resource==='applications'&&visible.length" class="application-cards">
   <article v-for="r in visible" :key="r.id||r.code" class="application-card" :aria-label="r.kind+'申请 '+r.code">
    <header>
     <div><h3>{{r.kind}}申请</h3><span class="mono">{{r.code}}</span></div>
     <span class="badge" :class="statusTone(r.progress_state||r.state)">{{fmt(r.progress_state||r.state)}}</span>
    </header>
    <p class="application-original">原止付 <span class="mono">{{fmt(r.original_code)}}</span></p>
    <dl v-if="r.kind==='返还'" class="application-amounts">
     <div><dt>申请金额</dt><dd>{{r.amount==null?'—':money(r.amount)}}</dd></div>
     <div><dt>核定金额</dt><dd>{{r.approved==null?'—':money(r.approved)}}</dd></div>
     <div class="application-credited"><dt>实际入账</dt><dd>{{r.credited==null?'—':money(r.credited)}}</dd></div>
    </dl>
    <footer><span>申请状态 <strong>{{fmt(r.state)}}</strong></span><button v-if="selectable" class="text-button row-action" @click="emit('select',r)">查看办理详情<AppIcon name="chevron" :size="14"/></button></footer>
   </article>
  </div>
  <div v-if="!visible.length" class="empty"><div class="empty-symbol"><AppIcon name="inbox" :size="30"/></div><strong>{{search||status||activeCount?'没有匹配的记录':empty}}</strong><button v-if="search||status||activeCount" class="small" @click="reset">清除筛选</button></div>
 </div>
 <div class="pagination"><span>{{total?'显示 '+((page-1)*10+1)+'–'+Math.min(page*10,total)+' 条':'共 0 条'}}<span class="pagination-size">每页 10 条</span></span><div><button class="icon-button page-button previous-page" aria-label="上一页" :disabled="page<=1" @click="page--"><AppIcon name="chevron" :size="16"/></button><span class="page-number">{{page}}<span>/ {{pages}}</span></span><button class="icon-button page-button" aria-label="下一页" :disabled="page>=pages" @click="page++"><AppIcon name="chevron" :size="16"/></button></div></div>
</template>
