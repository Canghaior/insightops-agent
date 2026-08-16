import { createPinia } from 'pinia'
import { createApp } from 'vue'
import { ElIcon } from 'element-plus'
import 'element-plus/es/components/icon/style/css'

import App from './App.vue'
import router from './router'
import './styles/main.css'

const app = createApp(App)
app.component('ElIcon', ElIcon)
app.use(createPinia()).use(router).mount('#app')
