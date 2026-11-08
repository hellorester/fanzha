import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig({plugins:[vue()],build:{emptyOutDir:true},server:{port:5173,strictPort:true,proxy:{'/api':'http://127.0.0.1:8088'}}})
