<script setup>
import {ref,computed} from 'vue'
import AppIcon from './AppIcon.vue'
import KnowledgeList from './KnowledgeList.vue'
import HeroCarousel from './HeroCarousel.vue'
import SafetyQuiz from './SafetyQuiz.vue'
import {fmt,statusTone} from '../api'
import {articleCategories,articleCategory} from '../article-categories'
const props=defineProps({page:String,articles:{type:Array,default:()=>[]},busy:Boolean,error:String,queryResult:Object,queryBusy:Boolean,paused:Boolean})
const emit=defineEmits(['navigate','report','login','article','query','refresh'])
const code=defineModel('code'),token=defineModel('token'),category=ref('全部'),keyword=ref('')
const categories=computed(()=>['全部',...articleCategories,...new Set(props.articles.map(a=>articleCategory(a.category)).filter(c=>!articleCategories.includes(c)))])
const filtered=computed(()=>props.articles.filter(a=>(category.value==='全部'||articleCategory(a.category)===category.value)&&(!keyword.value.trim()||(a.title+' '+a.summary).toLocaleLowerCase().includes(keyword.value.trim().toLocaleLowerCase()))))
const isHome=computed(()=>!['articles','lookup'].includes(props.page))
const services=[{label:'我要举报',hint:'提交可疑号码、账户或网址',icon:'report',tone:'blue',action:'report'},{label:'进度查询',hint:'凭受理编号查询办理状态',icon:'search',tone:'cyan',page:'lookup'},{label:'返还申诉',hint:'办理资金返还与误冻结申诉',icon:'application',tone:'teal',action:'applications'}]
function service(item){if(item.page)emit('navigate',item.page);else if(item.action==='report')emit('report');else emit('login','applications')}
function explore(value){category.value=value;keyword.value='';emit('navigate','articles')}
</script>
<template>
 <div class="public-portal">
  <header class="portal-header"><div class="portal-header-inner"><a class="portal-brand" href="#welcome"><span><AppIcon name="shield" :size="29"/></span><strong>反诈协同处置平台</strong></a><nav class="portal-nav" aria-label="公共服务导航"><button :class="{active:isHome}" @click="$emit('navigate','welcome')">首页</button><button :class="{active:page==='articles'}" @click="explore('全部')">反诈知识</button></nav><button class="portal-signin" @click="$emit('login','dashboard')"><AppIcon name="user" :size="18"/>登录</button></div></header>
  <main class="portal-main">
   <template v-if="isHome">
    <HeroCarousel :paused="paused" @explore="explore"/>
    <nav class="portal-services" aria-label="业务办理"><button v-for="item in services" :key="item.label" @click="service(item)"><span class="service-glyph" :class="item.tone"><AppIcon :name="item.icon" :size="29"/></span><span class="service-caption"><strong>{{item.label}}</strong><small>{{item.hint}}</small></span><AppIcon class="service-arrow" name="chevron" :size="17"/></button></nav>
    <div class="portal-home-columns"><section class="portal-knowledge"><header class="portal-section-heading"><h2>反诈知识</h2><button class="text-button" @click="explore('全部')">查看全部<AppIcon name="chevron" :size="15"/></button></header><div v-if="busy" class="portal-loading">正在加载…</div><KnowledgeList v-else :articles="articles.slice(0,3)" compact @select="$emit('article',$event)"/><div v-if="error" class="error-box">{{error}}<button class="text-button" @click="$emit('refresh')">重试</button></div></section><SafetyQuiz/></div>
   </template>
   <template v-else-if="page==='articles'">
    <header class="portal-page-heading"><h1>反诈知识</h1><label class="knowledge-search"><AppIcon name="search" :size="18"/><input v-model="keyword" type="search" aria-label="搜索知识文章" placeholder="搜索标题或摘要"/><button v-if="keyword" class="icon-button" aria-label="清空文章搜索" @click="keyword=''"><AppIcon name="close" :size="15"/></button></label></header>
    <nav class="knowledge-tabs" aria-label="知识分类"><button v-for="item in categories" :key="item" :class="{active:category===item}" :aria-pressed="category===item" @click="category=item">{{item}}</button></nav>
    <div v-if="busy" class="portal-loading">正在加载…</div><KnowledgeList v-else-if="filtered.length" :articles="filtered" @select="$emit('article',$event)"/><div v-else class="knowledge-no-results" role="status"><AppIcon name="search" :size="29"/><h2>{{keyword?'没有找到相关文章':'该分类暂无文章'}}</h2><button v-if="keyword||category!=='全部'" class="text-button" @click="keyword='';category='全部'">查看全部文章</button></div><div v-if="error" class="error-box">{{error}}<button class="text-button" @click="$emit('refresh')">重试</button></div>
   </template>
   <section v-else class="portal-query"><header class="portal-page-heading"><h1>进度查询</h1></header><form @submit.prevent="$emit('query')"><label class="field"><span>受理编号</span><input v-model="code" required autocomplete="off" placeholder="请输入受理编号"></label><label class="field"><span>查询凭证</span><input v-model="token" required type="password" autocomplete="off" placeholder="请输入提交后获得的查询凭证"></label><div v-if="error" class="error-box" role="alert">{{error}}</div><button class="primary" :disabled="queryBusy">{{queryBusy?'正在查询…':'查询进度'}}</button></form><section v-if="queryResult" class="portal-query-result"><header><h2>办理结果</h2><span class="badge" :class="statusTone(queryResult.state||'')">{{queryResult.state||queryResult.processing}}</span></header><dl class="details-grid"><div v-for="(label,key) in {code:'受理编号',processing:'研判状态',state:'办理状态',created_at:'受理时间',updated_at:'更新时间'}" :key="key" class="detail-field"><dt>{{label}}</dt><dd>{{fmt(queryResult[key])}}</dd></div></dl></section></section>
  </main>
  <footer class="portal-footer"><span>反诈协同处置平台</span></footer>
  <nav class="portal-mobile-nav" aria-label="底部导航"><button :class="{active:isHome}" @click="$emit('navigate','welcome')"><AppIcon name="home" :size="21"/><span>首页</span></button><button :class="{active:page==='articles'}" @click="explore('全部')"><AppIcon name="book" :size="21"/><span>知识</span></button><button @click="$emit('login','dashboard')"><AppIcon name="user" :size="21"/><span>我的</span></button></nav>
 </div>
</template>
