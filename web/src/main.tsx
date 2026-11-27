import { render } from 'preact';
import { registerSW } from 'virtual:pwa-register';
import { App } from './ui/app';
import { store } from './store/store';
import { persist } from './store/db';
import { autoSync } from './ui/cloud';
import { checkSignIn } from './ui/signin';
import './style.css';

registerSW({ immediate: true });
checkSignIn();
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') checkSignIn(); });

store.load().then(() => { persist(); autoSync(); }).catch((e) => console.error(e));
render(<App />, document.getElementById('app')!);
