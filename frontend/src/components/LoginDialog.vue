<script setup>
import {ref,computed,onMounted,onBeforeUnmount,useId} from 'vue'
import AppIcon from './AppIcon.vue'
const props=defineProps({busy:Boolean,error:String})
const emit=defineEmits(['close','submit','account'])
const username=defineModel('username'),password=defineModel('password'),dialog=ref(null),id=useId()
const identity=defineModel('role',{default:'PUBLIC'})
const identities=[{value:'PUBLIC',label:'群众用户',icon:'user',account:'public2'},{value:'CENTER',label:'反诈中心',icon:'shield',account:'center'},{value:'PAYMENT',label:'支付机构',icon:'bank',account:'pay1'},{value:'GRID',label:'社区网格',icon:'pin',account:'grid3'}]
const selected=computed(()=>identities.find(item=>item.value===identity.value)||identities[0])
let previous
onMounted(()=>{previous=document.activeElement;dialog.value?.querySelector('input[type=radio]:checked')?.focus({preventScroll:true})})
onBeforeUnmount(()=>previous?.focus?.({preventScroll:true}))
function keys(e){if(e.key==='Escape'&&!props.busy)emit('close');if(e.key==='Tab'){const nodes=[...dialog.value.querySelectorAll('button:not(:disabled),input:not(:disabled)')].filter(n=>n.getClientRects().length&&(n.type!=='radio'||n.checked));if(!nodes.length){e.preventDefault();return}if(e.shiftKey&&document.activeElement===nodes[0]){e.preventDefault();nodes.at(-1)?.focus()}else if(!e.shiftKey&&document.activeElement===nodes.at(-1)){e.preventDefault();nodes[0]?.focus()}}}
</script>
<template>
 <div class="modal-backdrop" @click.self="!busy&&$emit('close')">
  <section ref="dialog" class="dialog account-dialog" role="dialog" aria-modal="true" :aria-labelledby="id+'-title'" @keydown="keys">
   <header><h2 :id="id+'-title'">账号登录</h2><button class="icon-button" aria-label="关闭登录" :disabled="busy" @click="$emit('close')"><AppIcon name="close" :size="20"/></button></header>
   <form @submit.prevent="$emit('submit')">
    <fieldset class="login-identities" :disabled="busy"><legend>选择登录身份</legend><div><label v-for="item in identities" :key="item.value" :class="{selected:identity===item.value}"><input v-model="identity" type="radio" :name="id+'-role'" :value="item.value" :aria-label="item.label"/><AppIcon :name="item.icon" :size="20"/><span>{{item.label}}</span><AppIcon v-if="identity===item.value" name="check" :size="14"/></label></div></fieldset>
    <label class="field"><span>账号</span><input v-model="username" required autocomplete="username" :placeholder="'请输入'+selected.label+'账号'" :disabled="busy"></label>
    <label class="field"><span>密码</span><input v-model="password" type="password" required autocomplete="current-password" placeholder="请输入密码" :disabled="busy"></label>
    <div v-if="error" class="error-box" role="alert">{{error}}</div>
    <button type="submit" class="primary account-submit" :disabled="busy">{{busy?'正在登录…':'登录'}}</button>
   </form>
   <div v-if="identity==='PUBLIC'" class="login-quick public-account-quick"><span>演示群众账号</span><button class="text-button" :disabled="busy" @click="$emit('account','public2')">居民二（受害人）</button><button class="text-button" :disabled="busy" @click="$emit('account','public3')">居民三（冻结账户户主）</button></div>
   <div class="login-quick"><span>{{selected.label}}</span><button class="text-button" :disabled="busy" @click="$emit('account',selected.account)">填入快捷账号<AppIcon name="arrow" :size="14"/></button></div>
  </section>
 </div>
</template>
