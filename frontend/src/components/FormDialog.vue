<script setup>
import {computed,reactive,ref,onMounted,onBeforeUnmount,useId} from 'vue'
import {api} from '../api'
import {createAttachmentDraft} from '../attachment-draft'
import AppIcon from './AppIcon.vue'
const props=defineProps({title:String,description:String,fields:Array,initial:Object,summary:Function,submit:Function,validate:Function,button:{default:'确认提交'}})
const emit=defineEmits(['close','done','conflict'])
const data=reactive({...props.initial}),error=ref(''),busy=ref(false),uploads=reactive({}),dialog=ref(null),formId=useId()
const draft=createAttachmentDraft(api)
props.fields.forEach(f=>{
 const initialFiles=(f.initialFiles||[]).map(file=>({...file,committed:true}))
 if(initialFiles.length){uploads[f.key]=initialFiles;data[f.key]=initialFiles.map(file=>file.id)}
 else if(data[f.key]===undefined)data[f.key]=f.default??(f.type==='checkbox'?false:f.type==='file'?[]:'')
})
const summaryItems=computed(()=>props.summary?.(data)||[])
const visibleFields=computed(()=>props.fields.filter(field=>!field.when||field.when(data)))
const isRequired=field=>typeof field.required==='function'?field.required(data):Boolean(field.required)
const fieldValue=(field,key)=>typeof field[key]==='function'?field[key](data):field[key]
const groups=computed(()=>{
 const result=[]
 for(const field of visibleFields.value){
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
  const selected=[...e.target.files],limit=field.maxFiles||10,current=uploads[field.key]||[],replacing=limit===1&&selected.length===1
  if(!selected.length)return
  if(selected.length>limit||!replacing&&selected.length+current.length>limit)throw Error('最多上传'+limit+'个文件')
  for(const file of selected){
   if(file.size>10*1024*1024)throw Error(file.name+' 超过10MB')
   const body=new FormData();body.append('file',file)
   const added=await draft.upload(body)
   if(!added)break
   if(replacing){for(const old of current)if(!old.committed)await draft.remove(old.id);uploads[field.key]=[added]}
   else uploads[field.key]=[...(uploads[field.key]||[]),added]
   data[field.key]=uploads[field.key].map(f=>f.id)
  }
 }catch(e){error.value=e.message}finally{busy.value=false;e.target.value=''}
}
async function removeFile(field,index){
 error.value='';busy.value=true
 try{const file=uploads[field.key][index];if(!file.committed)await draft.remove(file.id);uploads[field.key].splice(index,1);data[field.key]=uploads[field.key].map(x=>x.id)}
 catch(e){error.value=e.message}finally{busy.value=false}
}
async function send(){
 error.value='';busy.value=true
 try{
   const validation=props.validate?.(data)
   if(validation)throw Error(validation)
   const missing=visibleFields.value.find(f=>f.type==='file'&&isRequired(f)&&!data[f.key]?.length)
  if(missing)throw Error('请上传'+missing.label)
  const result=await props.submit({...data});draft.commit();emit('done',result)
 }catch(e){error.value=e.message;if(e.status===409)emit('conflict')}finally{busy.value=false}
}
</script>
<template>
 <div class="modal-backdrop" @click.self="close">
  <section ref="dialog" class="dialog form-dialog" role="dialog" aria-modal="true" :aria-labelledby="formId+'-title'" @keydown="keydown">
   <header class="dialog-header"><h2 :id="formId+'-title'">{{title}}</h2><button type="button" class="icon-button" aria-label="关闭窗口" :disabled="busy" @click="close"><AppIcon name="close" :size="20"/></button></header>
   <form class="dialog-form" @submit.prevent="send">
    <div class="dialog-body">
      <p v-if="description" class="dialog-description">{{description}}</p>
     <dl v-if="summaryItems.length" class="form-summary"><div v-for="item in summaryItems" :key="item.label"><dt>{{item.label}}</dt><dd>{{item.value}}</dd></div></dl>
     <section v-for="(group,index) in groups" :key="index" class="form-section">
      <h3 v-if="group.title" class="form-section-title">{{group.title}}</h3>
      <div class="form-grid">
       <template v-for="item in group.items" :key="item.key">
        <div v-if="item.type==='checks'" class="check-group full">
         <label v-for="field in item.fields" :key="field.key" class="check-option" :class="{'check-option-wide':field.wide||field.help}">
           <input type="checkbox" v-model="data[field.key]" :disabled="busy||field.disabled" @change="error='';field.change?.(data)"><span><span class="check-label">{{field.label}}</span><small v-if="field.help">{{field.help}}</small></span>
         </label>
        </div>
        <div v-else class="field" :class="{full:item.field.wide||['textarea','file'].includes(item.field.type)}">
          <label :for="formId+'-'+item.key">{{item.field.label}}<span v-if="isRequired(item.field)" class="required-mark" aria-hidden="true">*</span><span v-if="item.field.optional" class="optional-mark">选填</span></label>
           <textarea v-if="item.field.type==='textarea'" :id="formId+'-'+item.key" v-model="data[item.key]" :required="isRequired(item.field)" :maxlength="item.field.max||2000" :rows="item.field.rows||3" :placeholder="item.field.placeholder" :disabled="busy||item.field.disabled" @input="error=''"></textarea>
           <select v-else-if="item.field.type==='select'" :id="formId+'-'+item.key" v-model="data[item.key]" :required="isRequired(item.field)" :disabled="busy||item.field.disabled" @change="error='';item.field.change?.(data)"><option value="" :disabled="isRequired(item.field)">{{isRequired(item.field)?'请选择':'请选择（可不选）'}}</option><option v-for="o in item.field.options" :key="o.value??o" :value="o.value??o" :disabled="o.disabled">{{o.label??o}}</option></select>
         <template v-else-if="item.field.type==='file'">
           <label class="upload-control" :class="{disabled:busy||item.field.disabled}"><input :id="formId+'-'+item.key" type="file" :multiple="(item.field.maxFiles||10)>1" :accept="item.field.accept||'.png,.jpg,.jpeg,.pdf,.docx,.txt'" :disabled="busy||item.field.disabled" @change="upload($event,item.field)"><span class="upload-button"><AppIcon name="upload" :size="16"/>{{busy?'正在处理…':uploads[item.key]?.length&&item.field.maxFiles===1?'更换图片':'选择文件'}}</span><span class="upload-hint">{{item.field.fileHint||'PNG / JPG / PDF / DOCX / TXT · 单个不超过 10 MB'}}</span></label>
          <div v-for="(file,i) in uploads[item.key]||[]" :key="file.id" class="upload-item" :class="{'image-upload-item':item.field.image}"><img v-if="item.field.image" :src="'/api/attachments/'+file.id" :alt="'封面预览：'+file.filename"><AppIcon v-else name="file" :size="16"/><span>{{file.filename}}</span><button type="button" class="icon-button" :aria-label="'移除 '+file.filename" :disabled="busy" @click="removeFile(item.field,i)"><AppIcon name="close" :size="16"/></button></div>
         </template>
          <div v-else class="input-wrap"><input :id="formId+'-'+item.key" v-model="data[item.key]" :type="item.field.type||'text'" :required="isRequired(item.field)" :step="item.field.step||'any'" :min="fieldValue(item.field,'min')" :max="fieldValue(item.field,'max')" :minlength="item.field.minlength" :maxlength="item.field.maxlength||500" :placeholder="item.field.placeholder" :autocomplete="item.field.type==='password'?'new-password':'off'" :disabled="busy||item.field.disabled" :readonly="fieldValue(item.field,'readonly')" :class="{'has-suffix':item.field.unit}" @input="error=''"><span v-if="item.field.unit" class="input-suffix">{{item.field.unit}}</span></div>
         <div v-if="item.field.help||item.field.actionLabel" class="field-support"><small v-if="item.field.help" class="field-help">{{item.field.help}}</small><button v-if="item.field.actionLabel" type="button" class="text-button" :disabled="busy" @click="item.field.action?.(data)">{{item.field.actionLabel}}</button></div>
        </div>
       </template>
      </div>
     </section>
     <div v-if="error" class="error-box" role="alert"><AppIcon name="alert" :size="16"/>{{error}}</div>
    </div>
    <footer class="dialog-footer"><div><button type="button" :disabled="busy" @click="close">取消</button><button type="submit" class="primary" :disabled="busy">{{busy?'正在处理…':button}}</button></div></footer>
   </form>
  </section>
 </div>
</template>
