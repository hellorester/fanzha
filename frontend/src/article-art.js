import transfer from './assets/knowledge-transfer.webp'
import evidence from './assets/knowledge-evidence.webp'
import phone from './assets/knowledge-phone.webp'

// Category artwork is decorative; article titles and content remain the source of truth.
export const articleArt=article=>article?.cover_id?'/api/attachments/'+encodeURIComponent(article.cover_id):article.category==='典型案例'?phone:article.category==='防范提示'?transfer:evidence
