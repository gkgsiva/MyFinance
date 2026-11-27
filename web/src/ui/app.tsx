import { useStore } from '../store/store';
import { Icons, ToastHost, back, go, useRoute } from './common';
import { EditorHost, openAdd } from './editor';
import { Home } from './home';
import { Entries } from './entries';
import { ImportScreen } from './importScreen';
import { Insights } from './insights';
import { Cards } from './cards';
import { CloudSettings } from './cloud';
import { SmsScreen } from './sms';
import { Payees } from './payees';
import { Accounts, Backup, Categories, DuplicatesScreen, Family, Imports, More, Profile, Report, ReversalsScreen, Trash } from './more';

const TABS = [
  { id: 'home', label: 'Home', icon: Icons.home },
  { id: 'entries', label: 'Entries', icon: Icons.list },
  { id: 'import', label: 'Upload', icon: Icons.upload },
  { id: 'insights', label: 'Insights', icon: Icons.chart },
  { id: 'more', label: 'More', icon: Icons.more },
];

const SUB: Record<string, [string, () => preact.JSX.Element]> = {
  cards: ['Card bills', Cards],
  'more/profile': ['Name and salary month', Profile],
  'more/accounts': ['Accounts and cards', Accounts],
  'more/categories': ['Categories', Categories],
  'more/payees': ['Payees', Payees],
  'more/family': ['Family & Android', Family],
  'more/cloud': ['Statements from cloud', CloudSettings],
  'import/sms': ['Bank messages (SMS)', SmsScreen],
  'more/report': ['Monthly report', Report],
  'more/backup': ['Backup', Backup],
  'more/trash': ['Recently deleted', Trash],
  'more/imports': ['Import history', Imports],
  'more/duplicates': ['Possible duplicates', DuplicatesScreen],
  'more/reversals': ['Reversed and refunded', ReversalsScreen],
};

export function App() {
  useStore();
  const route = useRoute();
  const sub = SUB[route];
  const tab = sub ? (TABS.find((t) => route.startsWith(t.id + '/'))?.id ?? 'home') : TABS.some((t) => t.id === route) ? route : 'home';
  const title = sub ? sub[0] : { home: 'MyFinance', entries: 'Entries', import: 'Upload statements', insights: 'Insights', more: 'More' }[tab];
  const Screen = sub ? sub[1] : { home: Home, entries: Entries, import: ImportScreen, insights: Insights, more: More }[tab as 'home'];
  return (
    <div class="app">
      <header class="topbar">
        {sub && <button class="back" onClick={() => back(tab)} aria-label="Back">‹ Back</button>}
        <h1>{title}</h1>
      </header>
      <main><Screen /></main>
      {(tab === 'home' || tab === 'entries') && !sub && <button class="fab" aria-label="Add an entry" onClick={openAdd}>+</button>}
      <nav class="tabbar" aria-label="Sections">
        {TABS.map((t) => (
          <button key={t.id} aria-current={tab === t.id && !sub ? 'page' : undefined} onClick={() => go(t.id)}>
            {t.icon}<span>{t.label}</span>
          </button>
        ))}
      </nav>
      <EditorHost />
      <ToastHost />
    </div>
  );
}
