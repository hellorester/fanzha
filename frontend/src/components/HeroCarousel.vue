<script setup>
import {ref,computed,onMounted,onBeforeUnmount,watch} from 'vue'
import AppIcon from './AppIcon.vue'
import hero from '../assets/portal-hero.webp'
import transfer from '../assets/knowledge-transfer.webp'
import phone from '../assets/knowledge-phone.webp'
const props=defineProps({paused:Boolean})
defineEmits(['explore'])
const slides=[
 {id:'report',theme:'blue',image:hero,eyebrow:'全民反诈 · 共筑防线',lines:['发现可疑线索','及时举报'],action:'查看举报指南',category:'举报指南'},
 {id:'transfer',theme:'cream',image:transfer,eyebrow:'转账之前 · 多想一步',lines:['先核实，再操作','守好自己的钱袋子'],action:'阅读防范提示',category:'防范提示'},
 {id:'caller',theme:'mint',image:phone,eyebrow:'识别骗局 · 拒绝套路',lines:['退款要先转账？','多留一份警惕'],action:'了解典型案例',category:'典型案例'}
]
const active=ref(0),userPaused=ref(false),keyboardFocused=ref(false),hidden=ref(false),reducedMotion=ref(false),motionOverride=ref(false)
const playbackEnabled=computed(()=>!userPaused.value&&(!reducedMotion.value||motionOverride.value))
const running=computed(()=>!props.paused&&playbackEnabled.value&&!keyboardFocused.value&&!hidden.value)
let timer,startPoint
function schedule(){clearTimeout(timer);if(running.value)timer=setTimeout(()=>{active.value=(active.value+1)%slides.length},5000)}
function select(index){active.value=(index+slides.length)%slides.length;schedule()}
function toggle(){if(playbackEnabled.value){userPaused.value=true;motionOverride.value=false}else{userPaused.value=false;motionOverride.value=true;keyboardFocused.value=false}}
function focus(event){keyboardFocused.value=event.target.matches(':focus-visible')}
function blur(event){if(!event.currentTarget.contains(event.relatedTarget))keyboardFocused.value=false}
function pointer(){keyboardFocused.value=false}
function down(event){if(event.target.closest('button,a')||!event.isPrimary)return;startPoint={x:event.clientX,y:event.clientY}}
function up(event){if(!startPoint)return;const dx=event.clientX-startPoint.x,dy=event.clientY-startPoint.y;startPoint=null;if(Math.abs(dx)>50&&Math.abs(dx)>Math.abs(dy)*1.4)select(active.value+(dx<0?1:-1))}
function visibility(){hidden.value=document.hidden}
let motionQuery
function motion(){reducedMotion.value=Boolean(motionQuery?.matches);if(reducedMotion.value)motionOverride.value=false}
watch([running,active],schedule)
onMounted(()=>{visibility();motionQuery=window.matchMedia?.('(prefers-reduced-motion: reduce)');motion();motionQuery?.addEventListener?.('change',motion);document.addEventListener('visibilitychange',visibility);schedule()})
onBeforeUnmount(()=>{clearTimeout(timer);motionQuery?.removeEventListener?.('change',motion);document.removeEventListener('visibilitychange',visibility)})
</script>
<template>
 <section class="hero-carousel" :class="['theme-'+slides[active].theme,{'is-playing':running}]" role="region" aria-roledescription="轮播图" aria-label="反诈专题" @focusin="focus" @focusout="blur" @keydown.left.prevent="select(active-1)" @keydown.right.prevent="select(active+1)" @pointerdown="pointer();down($event)" @pointerup="up" @pointercancel="startPoint=null">
  <article v-for="(slide,index) in slides" :key="slide.id" class="hero-slide" :class="['slide-'+slide.theme,{'is-active':index===active}]" :aria-hidden="index!==active" :inert="index!==active">
   <img :src="slide.image" alt="" class="hero-art" :fetchpriority="index===0?'high':'auto'" draggable="false"/>
   <div class="hero-copy"><span class="hero-eyebrow">{{slide.eyebrow}}</span><h1><span v-for="line in slide.lines" :key="line">{{line}}</span></h1><button class="hero-article-link" @click="$emit('explore',slide.category)">{{slide.action}}<AppIcon name="arrow" :size="17"/></button></div>
  </article>
  <div class="carousel-footer"><div class="carousel-pages" aria-label="轮播分页"><button v-for="(slide,index) in slides" :key="slide.id" :class="{active:active===index}" :aria-label="'切换到第'+(index+1)+'张：'+slide.lines[0]" :aria-current="active===index?'true':undefined" @click="select(index)"><span></span></button><span class="carousel-counter" :aria-live="running?'off':'polite'">{{String(active+1).padStart(2,'0')}} / 03</span></div><div class="carousel-controls"><button :aria-label="playbackEnabled?'暂停自动轮播':'播放自动轮播'" @click="toggle"><AppIcon :name="playbackEnabled?'pause':'play'" :size="15"/></button><button aria-label="上一张专题" @click="select(active-1)"><AppIcon class="carousel-previous" name="chevron" :size="16"/></button><button aria-label="下一张专题" @click="select(active+1)"><AppIcon name="chevron" :size="16"/></button></div></div>
 </section>
</template>
