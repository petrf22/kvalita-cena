import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./features/search/search-page').then((m) => m.SearchPage),
  },
  {
    path: 'produkt/:id',
    loadComponent: () =>
      import('./features/product-detail/product-detail-page').then((m) => m.ProductDetailPage),
  },
  {
    path: 'prihlaseni',
    loadComponent: () => import('./features/login/login-page').then((m) => m.LoginPage),
  },
  {
    path: 'nastaveni',
    loadComponent: () => import('./features/settings/settings-page').then((m) => m.SettingsPage),
  },
  { path: '**', redirectTo: '' },
];
