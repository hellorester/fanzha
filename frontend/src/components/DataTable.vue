<script setup>
import {computed,ref,watch} from 'vue'
import {fmt,statusTone} from '../api'
const props=defineProps({rows:{type:Array,default:()=>[]},columns:Array,empty:{default:'暂无记录'},selectable:{default:true},server:{default:null},resource:{default:''}})
const emit=defineEmits(['select','query'])
const search=ref(''),page=ref(1),status=ref(''),from=ref(''),to=ref(''),kind=ref(''),level=ref(''),source=ref('')
const statuses=computed(()=>props.server?.states||[...new Set(props.rows.map(r=>r.state||r.processing).filter(Boolean))])
const filtered=computed(()=>props.server?props.rows:props.rows.filter(r=>(!status.value||(r.state||r.processing)===status.value)&&(!search.value||props.columns.some(c=>String(r[c.key]??'').toLowerCase().includes(search.value.toLowerCase())))))
const pages=computed(()=>Math.max(1,Math.ceil((props.server?.total??filtered.value.length)/10)))
const visible=computed(()=>props.server?props.rows:filtered.value.slice((page.value-1)*10,page.value*10))
let queryTimer
function emitQuery(){if(!props.server)return;clearTimeout(queryTimer);queryTimer=setTimeout(()=>emit('query',{page:page.value,q:search.value,state:status.value,from:from.value,to:to.value,kind:kind.value,level:level.value,source:source.value}),250)}
watch([search,status,from,to,kind,level,source],()=>{page.value=1;emitQuery()})
watch(page,emitQuery)
watch(()=>props.rows,()=>{if(!props.server)page.value=1})
</script>
<template>
 <div class="table-tools"><label class="search"><span aria-hidden="true">⌕</span><input v-model="search" placeholder="搜索编号、名称或关键词" aria-label="搜索记录"></label><select v-if="statuses.length" v-model="status" aria-label="按状态筛选"><option value="">全部状态</option><option v-for="s in statuses" :key="s">{{s}}</option></select><span class="muted count">共 {{server?.total??filtered.length}} 条</span></div>
 <div v-if="server&&!['banks','users','blacklist'].includes(resource)" class="table-tools secondary-filters"><input type="date" v-model="from" aria-label="列表开始日期"><span>至</span><input type="date" v-model="to" aria-label="列表结束日期"><select v-if="['reports','commands','applications'].includes(resource)" v-model="kind" aria-label="按类型筛选"><option value="">全部类型</option><option v-for="k in resource==='reports'?['号码','账户','网址']:resource==='commands'?['止付','返还','解除']:['返还','申诉']">{{k}}</option></select><select v-if="['alerts','visits','reports'].includes(resource)" v-model="level" aria-label="按风险筛选"><option value="">全部风险</option><option v-for="l in ['高','中','低']">{{l}}</option></select><select v-if="resource==='reports'" v-model="source" aria-label="按来源筛选"><option value="">全部来源</option><option>群众举报</option><option>模拟监测</option></select></div>
 <div class="table-scroll"><table><thead><tr><th v-for="c in columns" :key="c.key">{{c.label}}</th><th v-if="selectable" class="align-right">操作</th></tr></thead><tbody><tr v-for="r in visible" :key="r.id||r.code"><td v-for="c in columns" :key="c.key" :data-label="c.label" :class="{'mono':c.key==='code','wide-cell':c.wide}"><span v-if="c.badge" class="badge" :class="statusTone(String(r[c.key]||''))">{{fmt(r[c.key])}}{{c.key==='level'?'风险':''}}</span><span v-else>{{c.format?c.format(r[c.key],r):fmt(r[c.key])}}</span></td><td v-if="selectable" class="align-right" data-label="操作"><button class="text-button" @click="emit('select',r)">查看详情 <span aria-hidden="true">→</span></button></td></tr></tbody></table><div v-if="!visible.length" class="empty"><div class="empty-symbol">▤</div><strong>{{empty}}</strong><p>记录会在业务办理后出现在这里。</p></div></div>
 <div class="pagination"><span>每页 10 条 · 第 {{page}} / {{pages}} 页</span><div><button class="small" :disabled="page<=1" @click="page--">上一页</button><button class="small" :disabled="page>=pages" @click="page++">下一页</button></div></div>
</template>
