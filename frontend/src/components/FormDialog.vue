<script setup>
import {computed,reactive,ref,onMounted,onBeforeUnmount,useId} from 'vue'
import {api} from '../api'
import {createAttachmentDraft} from '../attachment-draft'
import AppIcon from './AppIcon.vue'
const props=defineProps({title:String,description:String,fields:Array,initial:Object,submit:Function,button:{default:'确认提交'}})
const emit=defineEmits(['close','done'])
const data=reactive({...props.initial}),error=ref(''),busy=ref(false),uploads=reactive({}),dialog=ref(null),formId=useId()
const draft=createAttachmentDraft(api)
props.fields.forEach(f=>{if(data[f.key]===undefined)data[f.key]=f.default??(f.type==='checkbox'?false:f.type==='file'?[]:'')})
const groups=computed(()=>{
 const result=[]
 for(const field of props.fields){
  if(!result.length||(field.section&&field.section!==result.at(-1).title))result.push({title:field.section||'',items:[]})
  const items=result.at(-1).items
  if(field.type==='checkbox'){
   if(items.at(-1)?.type!=='checks')items.push({type:'checks',key:field.key,fields:[]})
   items.at(-1).fields.push(field)
  }else items.push({type:'field',key:field.key,field})
 }
 return result
})
let previous
onMounted(()=>{previous=document.activeElement;document.body.classList.add('modal-open');dialog.value?.querySelector('input:not([type="file"]),select,textarea')?.focus({preventScroll:true})})
onBeforeUnmount(()=>{void draft.close();document.body.classList.remove('modal-open');previous?.focus?.({preventScroll:true})})
async function close(){if(busy.value)return;busy.value=true;await draft.close();emit('close')}
function keydown(e){
 if(e.key==='Escape')void close()
 if(e.key==='Tab'){
  const nodes=[...dialog.value.querySelectorAll('button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),a[href]')].filter(n=>n.getClientRects().length)
  if(e.shiftKey&&document.activeElement===nodes[0]){e.preventDefault();nodes.at(-1)?.focus()}
  else if(!e.shiftKey&&document.activeElement===nodes.at(-1)){e.preventDefault();nodes[0]?.focus()}
 }
}
async function upload(e,field){
 error.value='';busy.value=true
 try{
  const selected=[...e.target.files]
  if(selected.length+(uploads[field.key]?.length||0)>10)throw Error('最多上传10个文件')
  for(const file of selected){
   if(file.size>10*1024*1024)throw Error(file.name+' 超过10MB')
   const body=new FormData();body.append('file',file)
   const added=await draft.upload(body)
   if(!added)break
   uploads[field.key]=[...(uploads[field.key]||[]),added]
   data[field.key]=uploads[field.key].map(f=>f.id)
  }
 }catch(e){error.value=e.message}finally{busy.value=false;e.target.value=''}
}
async function removeFile(field,index){
 error.value='';busy.value=true
 try{await draft.remove(uploads[field.key][index].id);uploads[field.key].splice(index,1);data[field.key]=uploads[field.key].map(x=>x.id)}
 catch(e){error.value=e.message}finally{busy.value=false}
}
async function send(){
 error.value='';busy.value=true
 try{
  const missing=props.fields.find(f=>f.type==='file'&&f.required&&!data[f.key]?.length)
  if(missing)throw Error('请上传'+missing.label)
  const result=await props.submit({...data});draft.commit();emit('done',result)
 }catch(e){error.value=e.message}finally{busy.value=false}
}
</script>
<template>
 <div class="modal-backdrop" @click.self="close">
  <section ref="dialog" class="dialog form-dialog" role="dialog" aria-modal="true" :aria-labelledby="formId+'-title'" @keydown="keydown">
   <header class="dialog-header"><h2 :id="formId+'-title'">{{title}}</h2><button type="button" class="icon-button" aria-label="关闭窗口" :disabled="busy" @click="close"><AppIcon name="close" :size="20"/></button></header>
   <form class="dialog-form" @submit.prevent="send">
    <div class="dialog-body">
     <p v-if="description" class="dialog-description"><AppIcon name="info" :size="16"/><span>{{description}}</span></p>
     <section v-for="(group,index) in groups" :key="index" class="form-section">
      <h3 v-if="group.title" class="form-section-title">{{group.title}}</h3>
      <div class="form-grid">
       <template v-for="item in group.items" :key="item.key">
        <div v-if="item.type==='checks'" class="check-group full">
         <label v-for="field in item.fields" :key="field.key" class="check-option" :class="{'check-option-wide':field.wide||field.help}">
          <input type="checkbox" v-model="data[field.key]" :disabled="busy"><span><span class="check-label">{{field.label}}</span><small v-if="field.help">{{field.help}}</small></span>
         </label>
        </div>
        <div v-else class="field" :class="{full:item.field.wide||['textarea','file'].includes(item.field.type)}">
         <label :for="formId+'-'+item.key">{{item.field.label}}<span v-if="item.field.required" class="required-mark" aria-hidden="true">*</span><span v-if="item.field.optional" class="optional-mark">选填</span></label>
         <textarea v-if="item.field.type==='textarea'" :id="formId+'-'+item.key" v-model="data[item.key]" :required="item.field.required" :maxlength="item.field.max||2000" :rows="item.field.rows||3" :placeholder="item.field.placeholder" :disabled="busy"></textarea>
         <select v-else-if="item.field.type==='select'" :id="formId+'-'+item.key" v-model="data[item.key]" :required="item.field.required" :disabled="busy"><option value="" :disabled="item.field.required">{{item.field.required?'请选择':'请选择（可不选）'}}</option><option v-for="o in item.field.options" :key="o.value??o" :value="o.value??o">{{o.label??o}}</option></select>
         <template v-else-if="item.field.type==='file'">
          <label class="upload-control" :class="{disabled:busy}"><input :id="formId+'-'+item.key" type="file" multiple accept=".png,.jpg,.jpeg,.pdf,.docx,.txt" :disabled="busy" @change="upload($event,item.field)"><span class="upload-button"><AppIcon name="upload" :size="16"/>{{busy?'正在处理…':'选择文件'}}</span><span class="upload-hint">支持多份材料，单个文件不超过 10 MB</span></label>
          <small class="field-help">PNG、JPG、PDF、DOCX、TXT</small>
          <div v-for="(file,i) in uploads[item.key]||[]" :key="file.id" class="upload-item"><AppIcon name="file" :size="16"/><span>{{file.filename}}</span><button type="button" class="icon-button" :aria-label="'移除 '+file.filename" :disabled="busy" @click="removeFile(item.field,i)"><AppIcon name="close" :size="16"/></button></div>
         </template>
         <div v-else class="input-wrap"><input :id="formId+'-'+item.key" v-model="data[item.key]" :type="item.field.type||'text'" :required="item.field.required" :step="item.field.step||'any'" :min="item.field.min" :max="item.field.max" :maxlength="item.field.maxlength||500" :placeholder="item.field.placeholder" :autocomplete="item.field.type==='password'?'new-password':'off'" :disabled="busy" :class="{'has-suffix':item.field.unit}"><span v-if="item.field.unit" class="input-suffix">{{item.field.unit}}</span></div>
         <small v-if="item.field.help" class="field-help">{{item.field.help}}</small>
        </div>
       </template>
      </div>
     </section>
     <div v-if="error" class="error-box" role="alert"><AppIcon name="alert" :size="16"/>{{error}}</div>
    </div>
    <footer class="dialog-footer"><span class="required-note"><span class="required-mark">*</span> 为必填项</span><div><button type="button" :disabled="busy" @click="close">取消</button><button type="submit" class="primary" :disabled="busy">{{busy?'正在处理…':button}}</button></div></footer>
   </form>
  </section>
 </div>
</template>
