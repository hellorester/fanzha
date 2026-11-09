<script setup>
import {ref,computed} from 'vue'
import AppIcon from './AppIcon.vue'
const questions=[
 {question:'“客服”说退款前要先转账，你会？',options:['回到原购物平台核实','按照对方要求转账'],correct:0,explanation:'通过原购物平台的官方客服核实，不要按陌生来电的要求转账。'},
 {question:'对方索要短信验证码来办理退款，你会？',options:['把验证码告诉对方','拒绝提供，另行核实'],correct:1,explanation:'验证码不要提供给他人，也不要按陌生人的要求开启屏幕共享。'},
 {question:'准备提交举报，哪些材料更有帮助？',options:['原始号码、交易与聊天记录','只记录自己的猜测'],correct:0,explanation:'保留原始记录，并按时间说明经过。材料暂时不完整，也可以先提交线索。'}
]
const index=ref(0),choice=ref(null),score=ref(0),finished=ref(false)
const current=computed(()=>questions[index.value])
function answer(i){if(choice.value!==null)return;choice.value=i;if(i===current.value.correct)score.value++}
function next(){if(index.value===questions.length-1)finished.value=true;else{index.value++;choice.value=null}}
function reset(){index.value=0;choice.value=null;score.value=0;finished.value=false}
</script>
<template>
 <aside class="portal-quiz"><header><h2><AppIcon name="shield" :size="21"/>防骗小测</h2><span v-if="!finished">{{index+1}} / {{questions.length}}</span></header>
  <template v-if="!finished"><h3>{{current.question}}</h3><div class="quiz-options"><button v-for="(option,i) in current.options" :key="option" :disabled="choice!==null" :class="{'is-correct':choice!==null&&i===current.correct,'is-wrong':choice===i&&i!==current.correct}" @click="answer(i)"><span>{{i===0?'A':'B'}}</span>{{option}}<AppIcon v-if="choice!==null&&i===current.correct" name="check" :size="15"/></button></div><div v-if="choice!==null" class="quiz-feedback" role="status"><strong>{{choice===current.correct?'答对了':'再记住这一点'}}</strong><p>{{current.explanation}}</p><button class="text-button" @click="next">{{index===questions.length-1?'查看结果':'下一题'}}<AppIcon name="arrow" :size="15"/></button></div><p v-else class="quiz-hint">选一个答案，看看你判断得对不对。</p></template>
  <div v-else class="quiz-complete" role="status"><span class="quiz-result"><strong>{{score}}</strong> / {{questions.length}}</span><h3>{{score===questions.length?'这几种套路，已经识破':'多了解一点，多一份防范'}}</h3><p>遇到可疑情况，先暂停，再核实。</p><button @click="reset"><AppIcon name="refresh" :size="15"/>再测一次</button></div>
 </aside>
</template>
