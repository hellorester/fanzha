// Own only files uploaded by this open form, never materials already saved on a record.
export function createAttachmentDraft(request) {
  const files = new Set()
  let closed = false
  async function remove(id) {
    await request('/attachments/' + id, undefined, 'DELETE')
    files.delete(id)
  }
  return {
    async upload(body) {
      const file = await request('/attachments', body)
      files.add(file.id)
      if (closed) {
        await remove(file.id)
        return null
      }
      return file
    },
    remove,
    commit() { files.clear(); closed = true },
    async close() {
      closed = true
      // A failed cleanup is retried by the server's expired-draft sweep.
      return Promise.allSettled([...files].map(remove))
    }
  }
}
