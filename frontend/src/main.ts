import { createPinia } from 'pinia';
import { createApp } from 'vue';
import ElementPlus from 'element-plus';
import App from './App.vue';
import { router } from './router';
import 'element-plus/dist/index.css';
import './styles/main.css';
import './styles/design-alignment.css';

createApp(App)
  .use(createPinia())
  .use(router)
  .use(ElementPlus)
  .mount('#app');
