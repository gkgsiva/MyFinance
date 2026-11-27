import { render } from 'preact';
import { registerSW } from 'virtual:pwa-register';
import { App } from './ui/app';
import { store } from './store/store';
import { persist } from './store/db';
import { autoSync } from './ui/cloud';
import './style.css';

registerSW({ immediate: true });

store.load().then(() => { persist(); autoSync(); }).catch((e) => console.error(e));
render(<App />, document.getElementById('app')!);
