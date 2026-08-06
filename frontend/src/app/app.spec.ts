import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideNzIcons } from 'ng-zorro-antd/icon';
import { App } from './app';
import { icons } from './icons-provider';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      // provideNzIcons je potřeba, protože hlavička teď má menu s ikonami (search/setting/user) —
      // bez registrace by NzIconModule na neznámý typ shodil test (IconNotFoundError).
      providers: [provideRouter([]), provideHttpClient(), provideNzIcons(icons)],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it('should render app title', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.app-title')?.textContent).toContain('Kvalita a cena');
  });
});
