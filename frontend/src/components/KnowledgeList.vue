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
  <button v-for="a in articles" :key="a.id" type="button" class="knowledge-row" :aria-label="'阅读 '+a.title" @click="$emit('select',a)">
   <span class="knowledge-cover"><img :src="articleArt(a)" alt="" loading="lazy"/></span>
   <span class="knowledge-copy"><span class="knowledge-meta"><span>{{articleCategory(a.category)}}</span><span v-if="manage" class="badge" :class="statusTone(a.state)">{{a.state}}</span></span><span class="knowledge-title">{{a.title}}</span><span class="knowledge-summary">{{a.summary}}</span><time v-if="a.updated_at" :datetime="a.updated_at">{{fmt(a.updated_at).slice(0,10)}}</time></span>
   <span class="knowledge-arrow" aria-hidden="true"><AppIcon name="chevron" :size="19"/></span>
  </button>
  <p v-if="!articles.length" class="knowledge-empty">暂无文章</p>
 </div>
</template>
