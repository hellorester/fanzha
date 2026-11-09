<script setup>
import AppIcon from './AppIcon.vue'
import {fmt,statusTone} from '../api'
import {articleArt} from '../article-art'
import {articleCategory} from '../article-categories'
defineProps({articles:{type:Array,default:()=>[]},manage:Boolean,compact:Boolean})
defineEmits(['select'])
</script>
<template>
 <div class="knowledge-list" :class="{'knowledge-list-compact':compact}">
  <article v-for="a in articles" :key="a.id" class="knowledge-row">
   <button class="knowledge-cover" :aria-label="'阅读 '+a.title" @click="$emit('select',a)"><img :src="articleArt(a)" alt="" loading="lazy"/></button>
   <div class="knowledge-copy"><div class="knowledge-meta"><span>{{articleCategory(a.category)}}</span><span v-if="manage" class="badge" :class="statusTone(a.state)">{{a.state}}</span></div><h3><button @click="$emit('select',a)">{{a.title}}</button></h3><p>{{a.summary}}</p><time v-if="a.updated_at" :datetime="a.updated_at">{{fmt(a.updated_at).slice(0,10)}}</time></div>
   <button class="icon-button knowledge-arrow" :aria-label="'查看 '+a.title" @click="$emit('select',a)"><AppIcon name="chevron" :size="19"/></button>
  </article>
  <p v-if="!articles.length" class="knowledge-empty">暂无文章</p>
 </div>
</template>
