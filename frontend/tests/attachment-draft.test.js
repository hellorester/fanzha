import test from 'node:test'
import assert from 'node:assert/strict'
import {createAttachmentDraft} from '../src/attachment-draft.js'

test('cancel deletes drafts but never committed business materials', async () => {
  const deleted=[]
  const draft=createAttachmentDraft(async(path,body,method)=>method==='DELETE'?deleted.push(path):{id:body})
  await draft.upload('first');await draft.upload('second');await draft.remove('first');await draft.close()
  assert.deepEqual(deleted,['/attachments/first','/attachments/second'])
  const saved=createAttachmentDraft(async(path,body,method)=>method==='DELETE'?deleted.push(path):{id:body})
  await saved.upload('bound');saved.commit();await saved.close()
  assert.equal(deleted.length,2)
})
test('an upload finishing after navigation is cleaned immediately', async () => {
  let resolveUpload;const deleted=[]
  const draft=createAttachmentDraft(async(path,body,method)=>method==='DELETE'?deleted.push(path):new Promise(resolve=>{resolveUpload=resolve}))
  const upload=draft.upload('slow');await draft.close();resolveUpload({id:'late'})
  assert.equal(await upload,null);assert.deepEqual(deleted,['/attachments/late'])
})
test('failed removal remains tracked so closing can retry it', async () => {
  let attempts=0
  const draft=createAttachmentDraft(async(path,body,method)=>{
    if(method!=='DELETE')return {id:'retry'}
    if(++attempts===1)throw new Error('offline')
  })
  await draft.upload('test');await assert.rejects(draft.remove('retry'));await draft.close()
  assert.equal(attempts,2)
})
