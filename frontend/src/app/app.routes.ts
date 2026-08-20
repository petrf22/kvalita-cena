import { Routes } from '@angular/router';

/**
 * Anglické, jazykově neutrální segmenty (docs/lokalizace.md) — lokalizované cesty per jazyk
 * (`/cs/produkt/:id`, `/sk/produkt/:id`, …) by při RUNTIME přepnutí jazyka (LanguageService)
 * musely přepisovat aktuální URL včetně historie a routerLinkActive, a zisk (SEO) je dnes
 * nulový (frontend nemá SSR ani žádnou konfiguraci nasazení). Staré české cesty zůstávají
 * jako trvalé redirecty kvůli existujícím odkazům/záložkám. Až přijde SSR/prerender, mají se
 * lokalizované aliasy přidat jako ADITIVUM, ne náhrada.
 */
export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./features/search/search-page').then((m) => m.SearchPage),
  },
  {
    path: 'product/:id',
    loadComponent: () =>
      import('./features/product-detail/product-detail-page').then((m) => m.ProductDetailPage),
  },
  {
    path: 'store/:id',
    loadComponent: () =>
      import('./features/store-detail/store-detail-page').then((m) => m.StoreDetailPage),
  },
  {
    path: 'price',
    loadComponent: () =>
      import('./features/price-entry/price-entry-page').then((m) => m.PriceEntryPage),
  },
  {
    path: 'login',
    loadComponent: () => import('./features/login/login-page').then((m) => m.LoginPage),
  },
  {
    path: 'settings',
    loadComponent: () => import('./features/settings/settings-page').then((m) => m.SettingsPage),
  },
  {
    path: 'profile',
    loadComponent: () => import('./features/profile/profile-page').then((m) => m.ProfilePage),
  },
  {
    path: 'my',
    loadComponent: () =>
      import('./features/my-contributions/my-contributions-page').then(
        (m) => m.MyContributionsPage,
      ),
  },
  {
    path: 'about',
    loadComponent: () => import('./features/about/about-page').then((m) => m.AboutPage),
  },
  {
    path: 'feedback',
    loadComponent: () => import('./features/feedback/feedback-page').then((m) => m.FeedbackPage),
  },
  {
    path: 'terms',
    loadComponent: () => import('./features/terms/terms-page').then((m) => m.TermsPage),
  },
  {
    path: 'privacy',
    loadComponent: () => import('./features/privacy/privacy-page').then((m) => m.PrivacyPage),
  },
  {
    // Nástroj pro T4 (docs/reputace.md, "Moderace") — jen web, appka roli sama nevynucuje,
    // jen podle ní zobrazuje odkaz (features/login/login-page.html); server je autoritativní.
    path: 'moderation',
    loadComponent: () =>
      import('./features/moderation/moderation-page').then((m) => m.ModerationPage),
  },
  // Staré české cesty — zachovány kvůli existujícím odkazům/záložkám.
  { path: 'produkt/:id', redirectTo: 'product/:id' },
  { path: 'obchod/:id', redirectTo: 'store/:id' },
  { path: 'zadat-cenu', redirectTo: 'price' },
  { path: 'prihlaseni', redirectTo: 'login' },
  { path: 'nastaveni', redirectTo: 'settings' },
  { path: 'profil', redirectTo: 'profile' },
  { path: 'moje-prispevky', redirectTo: 'my' },
  { path: 'podminky-uziti', redirectTo: 'terms' },
  { path: 'zasady-ochrany-osobnich-udaju', redirectTo: 'privacy' },
  { path: 'moderace', redirectTo: 'moderation' },
  { path: 'zpetna-vazba', redirectTo: 'feedback' },
  { path: '**', redirectTo: '' },
];
